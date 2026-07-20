package io.rolia.optimization;

import io.rolia.RoliaConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rolia - "lobotomize" stuck villagers (ported from Purpur, made Folia-safe and stateless).
 * A villager that is boxed in (cannot move to any horizontally adjacent column, e.g. a 1x1 trading-hall
 * cell) skips the expensive brain/pathfinding tick. Crucially it STILL restocks its trades, so trading
 * halls keep working exactly like vanilla - the only thing skipped is pathfinding a villager can't use.
 * Folia-safe: the decision is per-entity and reads only blocks around the villager on its own region
 * thread (via getBlockStateIfLoaded). Enabled by default; see rolia.yml -> optimizations.villager-lobotomize.
 */
public final class Lobotomize {
    private Lobotomize() {
    }

    /** Replacement for {@code villager.getBrain().tick(level, villager)} in Villager.customServerAiStep. */
    public static void tickVillagerBrain(final Villager villager, final ServerLevel level) {
        try {
            if (RoliaConfig.lobotomizeEnabled() && isLobotomized(villager, level)) {
                if (villager.shouldRestock(level)) {
                    villager.restock(); // keep trading-hall restocking working (vanilla-preserving)
                }
                return;
            }
        } catch (final Throwable ignored) {
            // never let the optimization break the tick - fall through to the full brain tick
        }
        villager.getBrain().tick(level, villager);
    }

    private static boolean isLobotomized(final Villager villager, final ServerLevel level) {
        // optionally keep full AI for villagers that are not yet trade-locked (0 xp) so they can level up
        if (RoliaConfig.lobotomizeWaitUntilTradeLocked() && villager.getVillagerXp() == 0) {
            return false;
        }
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
}
