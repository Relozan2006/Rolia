package io.rolia.optimization;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import ca.spottedleaf.moonrise.common.misc.NearbyPlayers;
import com.mojang.logging.LogUtils;
import io.rolia.RoliaConfig;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Rolia - Dynamic Activation of Brain (DAB).
 * Decides whether a mob's AI (brain behaviors) should run on the current tick, based on how far the
 * mob is from the nearest player. Mobs close to players always run full AI every tick; distant mobs
 * have their AI throttled to save CPU. Disabled by default (see rolia.yml -> optimizations.dab).
 * Folia-safe: the decision is per-entity and local to the region thread ticking that entity - no
 * cross-region state. The per-tick memo below is a ThreadLocal, which is safe for exactly the same
 * reason vanilla's plain Entity.tickCount field is: an entity is owned by (and ticked on) exactly
 * one region thread at a time.
 */
public final class Dab {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Rolia - one-shot kill switch, see disable(). A throwing optimization used to be swallowed by a
    // bare `catch (Throwable)`, which meant the server paid exception construction + stack capture
    // 3-4x per mob per tick, forever, with nothing in the log to explain the lost CPU.
    private static final AtomicBoolean BROKEN = new AtomicBoolean();

    // Rolia - shouldTickBrain is called THREE times per brain-mob per tick (Brain.tickSensors,
    // Brain.startEachNonRunningBehavior, Brain.tickEachRunningBehavior) plus once per goal-mob in
    // Mob.serverAiStep. Recomputing the same answer 3-4 times is pure waste, so it is memoized for
    // the rest of that game-tick. The natural home for this would be two plain fields on Entity
    // (non-volatile, exactly like vanilla's Entity.tickCount - the owning region thread is the only
    // writer), but io.rolia cannot add fields to Entity, so the memo is a small per-thread table.
    // Side benefit: the three brain call sites can no longer disagree with each other inside one
    // tick (previously a mob could run its sensors and then skip its behaviors, or vice versa, if
    // the nearest player crossed a distance boundary mid-tick).
    private static final ThreadLocal<EntityDecisionCache> MEMO =
        ThreadLocal.withInitial(() -> new EntityDecisionCache(512));

    private Dab() {
    }

    /** @return true if the entity's brain behaviors should be ticked this game-tick. */
    public static boolean shouldTickBrain(final LivingEntity entity) {
        if (BROKEN.get()) {
            return true; // Rolia - DAB failed once already; stay out of the tick loop permanently
        }
        try {
            if (!RoliaConfig.dabEnabled()) {
                return true; // feature off -> vanilla behaviour, near-zero overhead
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                return true;
            }
            // Rolia - NEVER throttle a mob that is in water. FloatGoal lives in goalSelector, and the
            // DAB gate in Mob.serverAiStep wraps the whole goalSelector block - so a throttled land mob
            // in water never runs FloatGoal, never calls jumpControl.jump(), and DROWNS. Paper's
            // goalFloat fallback only covers the !aware path, which returns before this code. This is
            // the same bug Pufferfish #58 reported and the reason Leaf ships a config for it.
            // isInWater() is a single boolean field read - cheaper than the memo probe it precedes.
            if (entity.isInWater()) {
                return true;
            }
            // never throttle blacklisted types (e.g. mobs used by farms). The blacklist is resolved
            // once into EntityType objects (see RoliaConfig#dabBlacklisted), so this is a plain
            // hash-set lookup - the old code built a fresh String from the registry key for every
            // mob on every call. dabHasBlacklist() keeps even that off the default (empty) path.
            if (RoliaConfig.dabHasBlacklist() && RoliaConfig.dabBlacklisted(entity.getType())) {
                return true;
            }

            final long gameTime = level.getGameTime();
            final int id = entity.getId();
            final EntityDecisionCache memo = MEMO.get();
            final int cached = memo.get(id, gameTime);
            if (cached >= 0) {
                return cached != 0;
            }
            final boolean decision = computeShouldTick(entity, level, gameTime, id);
            memo.put(id, gameTime, decision);
            return decision;
        } catch (final Exception e) { // Rolia - Error (OOM, region-ownership assertions, ...) still propagates
            disable(e);
            return true; // never break the tick loop; default to vanilla behaviour
        }
    }

    private static boolean computeShouldTick(final LivingEntity entity, final ServerLevel level,
                                             final long gameTime, final int id) {
        final int start = RoliaConfig.dabStartDistance();
        final int maxInterval = RoliaConfig.dabMaxTickInterval();
        final double startSq = (double) start * (double) start;

        final double nearestSq = nearestPlayerDistanceSq(entity, level);
        final int interval;
        if (nearestSq < 0.0D) {
            interval = maxInterval; // no players -> maximum throttle
        } else if (nearestSq <= startSq) {
            return true; // close to a player -> full AI every tick
        } else {
            final double dist = Math.sqrt(nearestSq);
            int i = 1 + (int) ((dist - start) / Math.max(1, start));
            if (i < 1) i = 1;
            if (i > maxInterval) i = maxInterval;
            interval = i;
        }
        if (interval <= 1) {
            return true;
        }
        // stagger by entity id so distant mobs do not all tick on the same game-tick
        final long phase = (id & 0x7fffffffL) % interval;
        return (gameTime + phase) % interval == 0L;
    }

    /**
     * Rolia - squared distance to the nearest player that should keep this mob's AI awake, or -1 if
     * there is none.
     * <p>
     * This used to be {@code level.getNearestPlayer(entity, -1.0)}. That call has two problems:
     * <ul>
     *   <li>{@code -1.0} means "no distance limit", so it walked the region's ENTIRE player list and
     *       computed a distance for every player - on a busy merged region that is a full scan per
     *       mob per tick, which can cost more than the AI it was supposed to save. Bounding the
     *       radius would not have helped: {@code getNearestPlayer} iterates the whole list either
     *       way and only uses the radius as a filter. So we query Moonrise's chunk-indexed
     *       nearby-player map instead, exactly like Canvas's own spawner (EntityGetter#canvas$...)
     *       and entity-push (Level#canvas$getIntersectingPlayers) code do from region threads.
     *       GENERAL is the widest map (33 chunks / 528 blocks); an entity only ticks at all when it
     *       is inside some player's simulation distance (max 32 chunks), so a ticking mob is always
     *       covered by it - an empty result genuinely means "no player near", which is precisely the
     *       maximum-throttle case.</li>
     *   <li>it resolves to the {@code (entity, distance)} overload, which forwards to
     *       {@code getNearestPlayer(x, y, z, distance, false)} - and that {@code false} selects
     *       NO_CREATIVE_OR_SPECTATOR (see NaturalSpawner, which passes it explicitly). So mobs
     *       around a creative-mode admin were throttled as if the server were empty, i.e. DAB looked
     *       broken to the one person most likely to be testing it. Creative players now count;
     *       spectators still do not.</li>
     * </ul>
     */
    private static double nearestPlayerDistanceSq(final LivingEntity entity, final ServerLevel level) {
        // Rolia - go through the ChunkSystemServerLevel interface explicitly, exactly as
        // EntityGetter/Level do. ServerLevel only gains moonrise$getNearbyPlayers() via a patch that
        // adds the interface, so the cast is the form that is guaranteed to keep compiling.
        final NearbyPlayers nearbyPlayers =
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) (Object) level).moonrise$getNearbyPlayers();
        if (nearbyPlayers == null) {
            return -1.0D;
        }
        final ReferenceList<ServerPlayer> players =
            nearbyPlayers.getPlayers(entity.chunkPosition(), NearbyPlayers.NearbyMapType.GENERAL);
        if (players == null) {
            return -1.0D; // no player anywhere near this chunk
        }
        final ServerPlayer[] raw = players.getRawDataUnchecked();
        final int len = Math.min(players.size(), raw.length);
        final Vec3 pos = entity.position();
        double nearest = -1.0D;
        for (int i = 0; i < len; ++i) {
            final ServerPlayer player = raw[i];
            if (player == null || player.isSpectator()) {
                continue;
            }
            final double distSq = player.distanceToSqr(pos.x, pos.y, pos.z);
            if (nearest < 0.0D || distSq < nearest) {
                nearest = distSq;
            }
        }
        return nearest;
    }

    // Rolia - log the failure exactly once (with the throwable) and then stop running DAB entirely.
    // Retrying every tick after a real Folia region-ownership violation would just burn CPU forever
    // while the log stays clean and the operator only sees "the server is mysteriously slow".
    private static void disable(final Exception e) {
        if (BROKEN.compareAndSet(false, true)) {
            LOGGER.error("Rolia: DAB (optimizations.dab) threw while deciding whether to tick mob AI.", e);
            LOGGER.error("Rolia: DAB is now DISABLED for the rest of this run - mob AI is back to vanilla");
            LOGGER.error("Rolia: behaviour. Please report the stack trace above.");
        }
    }

    /**
     * Rolia - a tiny per-thread, direct-mapped "entity id -> boolean" table used to memoize a decision
     * for a bounded stretch of game-time. Shared by this package (DAB memoizes per tick, Lobotomize
     * memoizes per check-interval); it lives here because DAB is by far its heaviest user.
     * <p>
     * Deliberately NOT a hash map: this is fixed-size (so it can never grow unboundedly and needs no
     * per-tick clearing pass), allocation-free after construction, and self-invalidating - an entry is
     * only a hit when both the entity id and the time stamp match, so stale entries for despawned or
     * migrated entities can never be read. A slot collision simply recomputes, i.e. the worst case is
     * exactly the old behaviour.
     */
    static final class EntityDecisionCache {
        private final int mask;
        private final int[] ids;
        private final long[] stamps;
        private final byte[] values;

        /** @param slots number of entries, MUST be a power of two. */
        EntityDecisionCache(final int slots) {
            this.mask = slots - 1;
            this.ids = new int[slots];
            this.stamps = new long[slots];
            this.values = new byte[slots];
            Arrays.fill(this.ids, -1); // -1 is never a valid entity id -> "empty slot"
        }

        /** @return 1 or 0 if this entity's decision for {@code stamp} is cached, -1 if it is not. */
        int get(final int entityId, final long stamp) {
            final int slot = entityId & this.mask;
            return this.ids[slot] == entityId && this.stamps[slot] == stamp ? this.values[slot] : -1;
        }

        void put(final int entityId, final long stamp, final boolean value) {
            final int slot = entityId & this.mask;
            this.ids[slot] = entityId;
            this.stamps[slot] = stamp;
            this.values[slot] = value ? (byte) 1 : (byte) 0;
        }
    }
}
