package io.rolia.optimization;

import io.rolia.RoliaConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Rolia - Dynamic Activation of Brain (DAB).
 * Decides whether a mob's AI (brain behaviors) should run on the current tick, based on how far the
 * mob is from the nearest player. Mobs close to players always run full AI every tick; distant mobs
 * have their AI throttled to save CPU. Disabled by default (see rolia.yml -> dab). Folia-safe: the
 * decision is per-entity and local to the region thread ticking that entity - no cross-region state.
 */
public final class Dab {
    private Dab() {
    }

    /** @return true if the entity's brain behaviors should be ticked this game-tick. */
    public static boolean shouldTickBrain(final LivingEntity entity) {
        try {
            if (!RoliaConfig.dabEnabled()) {
                return true; // feature off -> vanilla behaviour, near-zero overhead
            }
            if (!(entity.level() instanceof ServerLevel level)) {
                return true;
            }
            // never throttle blacklisted types (e.g. mobs used by farms)
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (RoliaConfig.dabBlacklisted(id)) {
                return true;
            }

            final int start = RoliaConfig.dabStartDistance();
            final int maxInterval = RoliaConfig.dabMaxTickInterval();
            final double startSq = (double) start * (double) start;

            final Player nearest = level.getNearestPlayer(entity, -1.0);
            final int interval;
            if (nearest == null) {
                interval = maxInterval; // no players -> maximum throttle
            } else {
                final double distSq = entity.distanceToSqr(nearest);
                if (distSq <= startSq) {
                    return true; // close to a player -> full AI every tick
                }
                final double dist = Math.sqrt(distSq);
                int i = 1 + (int) ((dist - start) / Math.max(1, start));
                if (i < 1) i = 1;
                if (i > maxInterval) i = maxInterval;
                interval = i;
            }
            if (interval <= 1) {
                return true;
            }
            // stagger by entity id so distant mobs do not all tick on the same game-tick
            final long phase = (entity.getId() & 0x7fffffffL) % interval;
            return (level.getGameTime() + phase) % interval == 0L;
        } catch (final Throwable t) {
            return true; // never break the tick loop; default to vanilla behaviour
        }
    }
}
