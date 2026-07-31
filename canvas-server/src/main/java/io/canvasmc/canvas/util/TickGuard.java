package io.canvasmc.canvas.util;

import ca.spottedleaf.moonrise.common.util.EntityUtil;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.common.util.WorldUtil;
import io.canvasmc.canvas.GlobalConfiguration;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NonNull;

import java.util.function.BooleanSupplier;

import static ca.spottedleaf.moonrise.common.util.TickThread.getThreadContext;
import static io.canvasmc.canvas.GlobalConfiguration.LOGGER;

public class TickGuard {

    public static void guard(final @NonNull BlockPos pos, final Level level, final String reason) {
        guard(pos.getX() >> 4, pos.getZ() >> 4, level, reason);
    }

    // Rolia - build 45: each severity now does what the config file tells the operator it does.
    //
    // The generated documentation for this option reads, verbatim:
    //     SILENT - "Doesn't say anything or do anything"
    //     LOG    - "Just logs a warning in console, but continues the operation"
    //     THROW  - "Throws an exception, can crash the server"
    //
    // Both of the first two were false. SILENT and LOG each began by calling ensureIsTickThread,
    // which logs at ERROR with a full stack trace and then throws IllegalStateException. So the
    // mode named SILENT was louder than LOG and, like LOG, aborted the very operation LOG promises
    // to continue. An operator who picked SILENT specifically to stop a guard from taking the
    // server down got an ERROR and a crash anyway.
    //
    // isTickThreadFor() already returns false when the caller is on no tick thread at all, so LOG
    // still reports that case - it simply reports it instead of throwing, which is the contract.
    public static void guard(final int chunkX, final int chunkZ, final Level level, final String reason) {
        switch (GlobalConfiguration.getInstance().regionScheduler.guardSeverity) {
            case SILENT -> { } // no check, no output: the operator asked for the guard to be off
            case LOG -> {
                if (!TickThread.isTickThreadFor(level, chunkX, chunkZ)) {
                    LOGGER.warn("Thread failed main thread check: {}, context={}, world={}, chunk_pos={}", reason, getThreadContext(), WorldUtil.getWorldName(level), new ChunkPos(chunkX, chunkZ), new Throwable());
                }
            }
            case THROW -> TickThread.ensureTickThread(level, chunkX, chunkZ, reason);
        }
    }

    public static void guard(final Entity entity, final String reason) {
        switch (GlobalConfiguration.getInstance().regionScheduler.guardSeverity) {
            case SILENT -> { } // as above
            case LOG -> {
                if (!TickThread.isTickThreadFor(entity)) {
                    LOGGER.warn("Thread failed main thread check: {}, context={}, entity={}", reason, getThreadContext(), EntityUtil.dumpEntity(entity), new Throwable());
                }
            }
            case THROW -> TickThread.ensureTickThread(entity, reason);
        }
    }

    public static void ensureGlobalOrStartup(final String reason) {
        if (TickRegions.started) {
            RegionizedServer.ensureGlobalTickThread(reason);
        }
        else {
            final Thread currentThread = Thread.currentThread();
            final Thread startupThread = MinecraftServer.getServer().serverThread;
            if (currentThread != startupThread) {
                LOGGER.error("Thread failed startup thread check: {}, context={}", reason, getThreadContext(), new Throwable());
                throw new IllegalStateException(reason);
            }
        }
    }

    public static void hardThrowIfStarted(final BooleanSupplier isTickThreadFor, final String reason) {
        if (TickRegions.started && !isTickThreadFor.getAsBoolean()) {
            LOGGER.error("Thread failed main thread check: {}, context={}", reason, getThreadContext(), new Throwable());
            throw new IllegalStateException(reason);
        }
    }

    // Rolia - build 45: ensureIsTickThread was removed with its last two callers. It was the reason
    // SILENT and LOG both threw. ensureGlobalOrStartup and hardThrowIfStarted above are unaffected:
    // those are unconditional invariants with no severity setting, and they still throw.
}
