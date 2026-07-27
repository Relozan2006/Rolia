package io.rolia.optimization;

import com.mojang.logging.LogUtils;
import io.rolia.RoliaConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Rolia - "lobotomize" stuck villagers (ported from Purpur, made Folia-safe).
 * A villager that is boxed in (cannot move to any horizontally adjacent column, e.g. a 1x1 trading-hall
 * cell) skips the expensive brain/pathfinding tick. Crucially it STILL restocks its trades, so trading
 * halls keep working exactly like vanilla - the only thing skipped is pathfinding a villager can't use.
 * Folia-safe: the decision is per-entity and reads only blocks around the villager on its own region
 * thread (via getBlockStateIfLoaded), and the cached answer lives in a per-thread table keyed by entity
 * id. Enabled by default; see rolia.yml -> optimizations.villager-lobotomize.
 */
public final class Lobotomize {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Rolia - one-shot kill switch, see disable().
    private static final AtomicBoolean BROKEN = new AtomicBoolean();

    // Rolia - the boxed-in probe costs up to 8 block lookups + 8 getCollisionShape calls, and it used
    // to run for EVERY villager on EVERY tick - including the overwhelming majority that are not boxed
    // in at all, for which it is pure added cost on top of the full brain tick that then runs anyway.
    // Purpur (where this is ported from) caches the answer and only re-probes every check-interval
    // ticks; so do we now. The old "stateless for Folia safety" comment was simply wrong - a villager
    // is owned by exactly one region thread at a time, so per-entity state needs no synchronization.
    // The table is sized generously because unlike DAB's memo (3-4 hits within one entity tick) an
    // entry here has to survive check-interval ticks of other villagers hashing into the same table.
    private static final ThreadLocal<Dab.EntityDecisionCache> BOXED_IN =
        ThreadLocal.withInitial(() -> new Dab.EntityDecisionCache(4096));

    private Lobotomize() {
    }

    /** Replacement for {@code villager.getBrain().tick(level, villager)} in Villager.customServerAiStep. */
    public static void tickVillagerBrain(final Villager villager, final ServerLevel level) {
        if (!BROKEN.get()) { // Rolia - skipped for good once lobotomization has thrown, see disable()
            try {
                if (RoliaConfig.lobotomizeEnabled() && isLobotomized(villager, level)) {
                    if (villager.shouldRestock(level)) {
                        villager.restock(); // keep trading-hall restocking working (vanilla-preserving)
                    }
                    return;
                }
            } catch (final Exception e) { // Rolia - Error still propagates; never swallow a Throwable
                disable(e);
                // fall through to the full brain tick
            }
        }
        villager.getBrain().tick(level, villager);
    }

    private static boolean isLobotomized(final Villager villager, final ServerLevel level) {
        // optionally keep full AI for villagers that are not yet trade-locked (0 xp) so they can level up
        if (RoliaConfig.lobotomizeWaitUntilTradeLocked() && villager.getVillagerXp() == 0) {
            return false;
        }
        // Rolia - re-probe at most once per check-interval ticks (Purpur does the same), and stagger the
        // re-probe by entity id so a trading hall does not re-probe every villager on the same tick.
        // Cost of the cache: a villager freed from its cell keeps its old state for up to check-interval
        // ticks - that is the documented trade-off of the config option.
        final int interval = Math.max(1, RoliaConfig.lobotomizeCheckInterval()); // config is clamped, belt and braces
        final int id = villager.getId();
        final long phase = (id & 0x7fffffffL) % interval;
        final long stamp = (level.getGameTime() + phase) / interval;
        final Dab.EntityDecisionCache cache = BOXED_IN.get();
        final int cached = cache.get(id, stamp);
        if (cached >= 0) {
            return cached != 0;
        }
        final boolean boxedIn = probeBoxedIn(villager, level);
        cache.put(id, stamp, boxedIn);
        return boxedIn;
    }

    /** The actual probe: can the villager step into any horizontally adjacent column? */
    private static boolean probeBoxedIn(final Villager villager, final ServerLevel level) {
        // feet position, nudged up slightly for short blocks (dirt_path/farmland), matching Purpur
        final BlockPos feet = BlockPos.containing(
            villager.position().x, villager.getBoundingBox().minY + 0.0625D, villager.position().z);
        return !(canTravelTo(level, feet.east()) || canTravelTo(level, feet.west())
              || canTravelTo(level, feet.north()) || canTravelTo(level, feet.south()));
    }

    private static boolean canTravelTo(final ServerLevel level, final BlockPos pos) {
        final BlockState bottom = level.getBlockStateIfLoaded(pos);
        if (bottom == null) {
            return true; // unloaded -> assume reachable, never wrongly lobotomize (conservative)
        }
        final BlockState top = level.getBlockStateIfLoaded(pos.above());
        if (top == null) {
            return true;
        }
        return bottom.getCollisionShape(level, pos).isEmpty()
            && top.getCollisionShape(level, pos.above()).isEmpty();
    }

    // Rolia - log the failure exactly once (with the throwable) and then stop lobotomizing entirely.
    // Silently retrying every tick after e.g. a region-ownership violation costs exception construction
    // plus a stack capture per villager per tick, forever, and leaves the operator with a clean log.
    private static void disable(final Exception e) {
        if (BROKEN.compareAndSet(false, true)) {
            LOGGER.error("Rolia: villager lobotomization (optimizations.villager-lobotomize) threw.", e);
            LOGGER.error("Rolia: it is now DISABLED for the rest of this run - villagers are back to");
            LOGGER.error("Rolia: ticking their full brain. Please report the stack trace above.");
        }
    }
}
