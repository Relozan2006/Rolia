package io.rolia.secureseed;

import io.rolia.RoliaConfig;

import com.google.common.collect.Iterables;

import net.minecraft.server.level.ServerLevel;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Optional;

public class Globals {
    public static final int WORLD_SEED_LONGS = 16;
    public static final int WORLD_SEED_BITS = WORLD_SEED_LONGS * 64;

    public static final long[] worldSeed = new long[WORLD_SEED_LONGS];
    public static final ThreadLocal<Integer> dimension = ThreadLocal.withInitial(() -> 0);

    // Rolia start - avoid linear scan + allocations on the hot path (called from getGenerator()/ChunkStep)
    private static volatile boolean seedInitialized = false;
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Integer> DIMENSION_INDEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setupGlobals(ServerLevel world) {
        if (!seedInitialized) {
            long[] seed = world.getServer().getWorldGenSettings().options().featureSeed();
            System.arraycopy(seed, 0, worldSeed, 0, WORLD_SEED_LONGS);
            seedInitialized = true;
        }
        Integer cached = DIMENSION_INDEX_CACHE.get(world.dimension());
        if (cached == null) {
            int worldIndex = Iterables.indexOf(world.getServer().levelKeys(), it -> it == world.dimension());
            // prevent race condition where world is not yet added to levelKeys
            if (worldIndex == -1) {
                dimension.set(world.getServer().levelKeys().size()); // do not cache a value computed mid-registration
                return;
            }
            DIMENSION_INDEX_CACHE.put(world.dimension(), worldIndex);
            cached = worldIndex;
        }
        dimension.set(cached);
    }

    /**
     * Derives a salt-protected 64-bit seed for vanilla systems that would otherwise
     * leak the raw level seed (loot random sequences, end spikes, ...).
     * Deterministic per (levelSeed, domain, salt); falls back to the raw seed when disabled.
     */
    public static long transformSeed(long levelSeed, long domain) {
        if (!isSecureSeedEnabled()) {
            return levelSeed;
        }
        long[] expanded = Hashing.expandLevelSeedTo1024Bits(levelSeed ^ domain);
        return expanded[(int) (domain & 7)];
    }
    // Rolia end

    public static long[] createRandomWorldSeed() {
        long[] seed = new long[WORLD_SEED_LONGS];
        SecureRandom rand = new SecureRandom();
        for (int i = 0; i < WORLD_SEED_LONGS; i++) {
            seed[i] = rand.nextLong();
        }
        return seed;
    }

    public static Optional<long[]> parseSeed(String seedStr) {
        if (seedStr.isEmpty()) return Optional.empty();

        try {
            long[] seed = new long[WORLD_SEED_LONGS];
            BigInteger seedBigInt = new BigInteger(seedStr);
            if (seedBigInt.signum() < 0) {
                seedBigInt = seedBigInt.and(BigInteger.ONE.shiftLeft(WORLD_SEED_BITS).subtract(BigInteger.ONE));
            }
            for (int i = 0; i < WORLD_SEED_LONGS; i++) {
                BigInteger[] divRem = seedBigInt.divideAndRemainder(BigInteger.ONE.shiftLeft(64));
                seed[i] = divRem[1].longValue();
                seedBigInt = divRem[0];
            }
            return Optional.of(seed);
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public static String seedToString(long[] seed) {
        BigInteger seedBigInt = BigInteger.ZERO;
        for (int i = WORLD_SEED_LONGS - 1; i >= 0; i--) {
            BigInteger val = BigInteger.valueOf(seed[i]);
            if (val.signum() < 0) {
                val = val.add(BigInteger.ONE.shiftLeft(64));
            }
            seedBigInt = seedBigInt.shiftLeft(64).add(val);
        }

        return seedBigInt.toString();
    }

    public static boolean isSecureSeedEnabled() {
        // Rolia - the secure seed is ALWAYS on and cannot be disabled (hard-forced).
        return true;
    }


    public static String getSecureSeedSalt() {
        return RoliaConfig.salt();
    }

    public enum Salt {
        UNDEFINED,
        BASTION_FEATURE,
        WOODLAND_MANSION_FEATURE,
        MINESHAFT_FEATURE,
        BURIED_TREASURE_FEATURE,
        NETHER_FORTRESS_FEATURE,
        PILLAGER_OUTPOST_FEATURE,
        GEODE_FEATURE,
        NETHER_FOSSIL_FEATURE,
        OCEAN_MONUMENT_FEATURE,
        RUINED_PORTAL_FEATURE,
        POTENTIONAL_FEATURE,
        GENERATE_FEATURE,
        JIGSAW_PLACEMENT,
        STRONGHOLDS,
        POPULATION,
        DECORATION,
        SLIME_CHUNK
    }
}