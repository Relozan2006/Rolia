package io.rolia.secureseed;

import com.mojang.logging.LogUtils;
import io.rolia.RoliaConfig;

import com.google.common.collect.Iterables;

import net.minecraft.server.level.ServerLevel;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Optional;

public class Globals {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    public static final int WORLD_SEED_LONGS = 16;
    public static final int WORLD_SEED_BITS = WORLD_SEED_LONGS * 64;

    public static final long[] worldSeed = new long[WORLD_SEED_LONGS];
    public static final ThreadLocal<Integer> dimension = ThreadLocal.withInitial(() -> 0);

    // Rolia start - avoid linear scan + allocations on the hot path (called from getGenerator()/ChunkStep)
    private static volatile boolean seedInitialized = false;
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Integer> DIMENSION_INDEX_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setupGlobals(ServerLevel world) {
        if (!seedInitialized) {
            synchronized (Globals.class) { // Rolia - publish the shared world seed exactly once, safely
                if (!seedInitialized) {
                    long[] seed = normalizeLength(world.getServer().getWorldGenSettings().options().featureSeed());
                    System.arraycopy(seed, 0, worldSeed, 0, WORLD_SEED_LONGS);
                    seedInitialized = true; // Rolia - volatile write; publishes the array contents written above
                    // Rolia - the one authoritative startup line. Emitted at the point of publication and
                    // derived from real state (fingerprint of seed+salt), so it cannot report "active" for
                    // a seed that is not. The CI gate asserts on the fingerprint, not on a fixed string.
                    LOGGER.info("Rolia: config loaded (secure seed {}; DAB {}).",
                        isActive() ? "ACTIVE fp=" + seedFingerprint() : "INACTIVE",
                        RoliaConfig.dabEnabled() ? "ON" : "off");
                }
            }
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
     * Rolia - a volatile read of the publication flag. Callers that touch {@link #worldSeed} without
     * having gone through {@link #setupGlobals} must call this first: reading the volatile {@code true}
     * establishes the happens-before edge to the array writes inside the synchronized block above.
     * Without it a worldgen thread may legally observe a partially written (or all-zero) seed on
     * weakly-ordered hardware (ARM/Graviton), which would silently generate chunks off the wrong seed.
     */
    public static boolean seedPublished() {
        return seedInitialized;
    }

    /**
     * Rolia - the correct way to read the shared seed from a thread that did not itself call
     * {@link #setupGlobals}. The branch below consumes the volatile {@code seedInitialized} read, so
     * it cannot be optimised away, and that read is what orders the array writes in setupGlobals
     * before this thread's copy of them.
     */
    public static long[] publishedWorldSeed() {
        if (seedInitialized) {
            return worldSeed; // published - the volatile read above orders the writes before this point
        }
        // Not published yet (very early boot); the array is all-zero by definition, nothing to order.
        return worldSeed;
    }

    /** Rolia - true once a real (non-zero) 1024-bit seed is in effect. Used by the startup log + CI gate. */
    public static boolean isActive() {
        if (!seedInitialized) {
            return false;
        }
        for (long v : worldSeed) {
            if (v != 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rolia - a 16-hex-char fingerprint of the ACTIVE secret (seed + salt), safe to print.
     * It is the truncated BLAKE2b of the salted seed, so it reveals nothing about either input but
     * differs for every distinct (seed, salt) pair. This is what the startup line and the CI assertion
     * use, so that check depends on real state instead of a hardcoded literal.
     */
    public static String seedFingerprint() {
        if (!isActive()) {
            return "0000000000000000";
        }
        long[] h = Hashing.hashWorldSeed(worldSeed);
        return String.format("%016x", h[0]);
    }

    /** Rolia - pad/truncate a stored feature seed to exactly WORLD_SEED_LONGS so a malformed level.dat cannot throw. */
    public static long[] normalizeLength(long[] seed) {
        if (seed != null && seed.length == WORLD_SEED_LONGS) {
            return seed;
        }
        long[] out = new long[WORLD_SEED_LONGS];
        if (seed != null && seed.length > 0) {
            System.arraycopy(seed, 0, out, 0, Math.min(seed.length, WORLD_SEED_LONGS));
        }
        return out;
    }

    /**
     * Derives a salt-protected 64-bit seed for vanilla systems that would otherwise
     * leak the raw level seed (loot random sequences, end spikes, ...).
     * Deterministic per (levelSeed, domain, salt).
     */
    public static long transformSeed(long levelSeed, long domain) {
        long[] expanded = Hashing.expandLevelSeedTo1024Bits(levelSeed ^ domain);
        return expanded[(int) (domain & 7)];
    }

    // Rolia - Seed V2: derive the TERRAIN seed from the secret (salt-keyed), so terrain/biomes/caves/
    // aquifers/ore-noise are no longer reproducible from the public level seed - only with the secret salt.
    // Applied once at the root of RandomState, so it cascades to every terrain sub-system (aquifer/ore/
    // climate/surface all fork from this root). Part of the always-on 1024-bit protection; not disableable.
    public static final long TERRAIN_DOMAIN = 0x5445525241494E00L; // "TERRAIN\0"
    public static long secureTerrainSeed(long levelSeed) {
        return transformSeed(levelSeed, TERRAIN_DOMAIN);
    }
    // Rolia end

    // Rolia - guarantee a real random 1024-bit feature seed: null/empty/all-zero means "uninitialised"
    // (e.g. a world created without a feature seed), so generate a fresh cryptographically random one.
    public static long[] ensureRandomSeed(long[] seed) {
        if (seed == null || seed.length == 0) {
            return createRandomWorldSeed();
        }
        for (long v : seed) {
            if (v != 0L) {
                return normalizeLength(seed); // Rolia - a short/long stored seed must not reach System.arraycopy
            }
        }
        return createRandomWorldSeed();
    }

    public static long[] createRandomWorldSeed() {
        long[] seed = new long[WORLD_SEED_LONGS];
        SecureRandom rand = new SecureRandom();
        for (int i = 0; i < WORLD_SEED_LONGS; i++) {
            seed[i] = rand.nextLong();
        }
        return seed;
    }

    // Rolia - derive a STABLE, salt-keyed 1024-bit feature seed from a world's level seed.
    // Used only for worlds that carry no stored feature seed yet (a world imported from vanilla/Paper,
    // or an in-place upgrade): it keeps structure/ore placement identical across restarts even before the
    // first save, while staying un-computable without the secret salt (both halves are keyed by the salt
    // hash inside expandLevelSeedTo1024Bits). Fresh Rolia worlds never reach this path - they always get a
    // fresh random feature seed from server.properties (feature-level-seed) instead.
    public static long[] deriveFeatureSeedFromLevel(long levelSeed) {
        long[] seed = new long[WORLD_SEED_LONGS];
        long[] lo = Hashing.expandLevelSeedTo1024Bits(levelSeed);
        long[] hi = Hashing.expandLevelSeedTo1024Bits(levelSeed ^ 0x9E3779B97F4A7C15L);
        System.arraycopy(lo, 0, seed, 0, 8);
        System.arraycopy(hi, 0, seed, 8, 8);
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

    /**
     * Rolia - domain separators for the secure RNG.
     *
     * <p><b>DO NOT REORDER, RENUMBER OR DELETE ANY CONSTANT.</b> The {@code id} below is mixed into
     * every generated chunk's random stream, so changing one silently reshuffles every structure,
     * decoration and slime chunk in every existing world - with no error and no migration path.
     * The ids were originally {@code ordinal()}; they are now explicit precisely so that a future
     * reordering (or a tidy-up of the currently-unused constants) cannot corrupt live worlds.
     * New domains must be appended with the next free id.</p>
     */
    public enum Salt {
        UNDEFINED(0),
        // Reserved: kept for id stability, not currently referenced by any patch. Do not delete.
        BASTION_FEATURE(1),
        WOODLAND_MANSION_FEATURE(2),
        MINESHAFT_FEATURE(3),
        BURIED_TREASURE_FEATURE(4),
        NETHER_FORTRESS_FEATURE(5),
        PILLAGER_OUTPOST_FEATURE(6),
        GEODE_FEATURE(7),
        NETHER_FOSSIL_FEATURE(8),
        OCEAN_MONUMENT_FEATURE(9),
        RUINED_PORTAL_FEATURE(10),
        POTENTIONAL_FEATURE(11),
        GENERATE_FEATURE(12),
        JIGSAW_PLACEMENT(13),
        STRONGHOLDS(14),
        POPULATION(15),
        DECORATION(16),
        SLIME_CHUNK(17),
        // Rolia - build 38: cave/ravine carvers, previously left on the public 64-bit level seed.
        CARVER(18);

        public final int id;

        Salt(final int id) {
            this.id = id;
        }
    }
}