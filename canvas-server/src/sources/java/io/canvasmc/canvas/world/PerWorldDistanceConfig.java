package io.canvasmc.canvas.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.canvasmc.canvas.util.Codecs;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;

/**
 * Per world view and simulation distance override. When the value is <= 0, it uses the value from the server
 * properties.
 *
 * @param viewDistance
 *     the view distance override
 * @param simulationDistance
 *     the simulation distance override
 */
public record PerWorldDistanceConfig(AtomicInteger viewDistance, AtomicInteger simulationDistance) {
    // Rolia - build 45: this record's two components are MUTABLE, and both the codec default and the
    // shared DEFAULT instance used to hand out one single static AtomicInteger for all of it. The
    // consequences were not subtle. `/canvas worlddistance view overworld 12` calls
    // viewDistance().set(12) on the world's config; for any world that had no stored override that
    // config was PerWorldDistanceConfig.DEFAULT, whose viewDistance and simulationDistance were the
    // SAME object, which was in turn the SAME object every other such world was holding. So one
    // command set the view distance of the world you named, the SIMULATION distance of that world -
    // which drives mob spawning, redstone and chunk ticking, not just what you can see - and both
    // distances of every other world that had not been configured, including worlds created later.
    // Then it was written to each of their saved-data files.
    //
    // Two changes, and either one alone would be incomplete:
    //   1. the compact constructor copies its components, so no PerWorldDistanceConfig can ever
    //      share a counter with another one, or alias its own two counters together, no matter what
    //      a caller or a codec passes in;
    //   2. DEFAULT is gone. A mutable "constant" is the bug itself. defaults() returns a fresh
    //      instance per call, so each world owns its own.
    private static final int NO_OVERRIDE = -1;

    public PerWorldDistanceConfig {
        viewDistance = new AtomicInteger(viewDistance == null ? NO_OVERRIDE : viewDistance.get());
        simulationDistance = new AtomicInteger(simulationDistance == null ? NO_OVERRIDE : simulationDistance.get());
    }

    /**
     * A fresh, unshared override pair meaning "use the server.properties value for both".
     */
    public static PerWorldDistanceConfig defaults() {
        return new PerWorldDistanceConfig(new AtomicInteger(NO_OVERRIDE), new AtomicInteger(NO_OVERRIDE));
    }

    public static final Codec<PerWorldDistanceConfig> CODEC = RecordCodecBuilder.create(
        instance -> instance.group(
                // orElse still names a single instance, but the compact constructor above copies it
                // before it can ever be reached through a config, so it can no longer be written to.
                Codecs.ATOMIC_INTEGER.fieldOf("ViewDistance").orElse(new AtomicInteger(NO_OVERRIDE)).forGetter(PerWorldDistanceConfig::viewDistance),
                Codecs.ATOMIC_INTEGER.fieldOf("SimulationDistance").orElse(new AtomicInteger(NO_OVERRIDE)).forGetter(PerWorldDistanceConfig::simulationDistance)
            )
            .apply(instance, PerWorldDistanceConfig::new)
    );

    public int viewDistanceOrDefault() {
        return this.viewDistance.get() <= 0 ? ((DedicatedServer) MinecraftServer.getServer()).settings.getProperties().viewDistance.get() : this.viewDistance.get();
    }

    public int simulationDistanceOrDefault() {
        return this.simulationDistance.get() <= 0 ? ((DedicatedServer) MinecraftServer.getServer()).settings.getProperties().simulationDistance.get() : this.simulationDistance.get();
    }

    public boolean isViewDistanceOverridden() {
        return this.viewDistance.get() > 0;
    }

    public boolean isSimulationDistanceOverridden() {
        return this.simulationDistance.get() > 0;
    }
}
