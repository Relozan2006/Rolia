package io.rolia.secureseed;

import com.mojang.logging.LogUtils;
import io.rolia.RoliaConfig;

import net.minecraft.server.level.ServerLevel;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * Rolia - the secure world seed.
 *
 * <h2>Build 40 model: terrain is PUBLIC, everything else is SECRET</h2>
 *
 * <p>Worldgen is deliberately split in two:</p>
 * <ul>
 *   <li><b>Public</b>, derived from the ordinary {@code level-seed} in server.properties: the terrain
 *       SHAPE. Concretely the {@code continentalness}, {@code erosion}, {@code ridge}, {@code offset}
 *       and {@code jagged} noises, {@code BlendedNoise}, and End-island shape. Anyone who knows the
 *       level seed can reproduce the landscape.</li>
 *   <li><b>Secret</b>, derived from the 1024-bit feature seed plus the salt: everything else. Biome
 *       climate ({@code temperature}/{@code vegetation} - i.e. WHICH biome sits on a given landform),
 *       caves, ravines, ore veins, aquifers, surface rules, structures, decorations, loot and slime
 *       chunks.</li>
 * </ul>
 *
 * <p>Terrain shape and biome selection are coupled in 1.18+: the multi-noise biome source reads
 * continentalness, erosion, depth and ridges, which are the same noises that build the heightmap. So a
 * player who knows the level seed knows four of the six biome parameters. Temperature and vegetation
 * stay secret, which is what decides whether a given mountain is snowy or jungle. This is a deliberate,
 * documented limit, not an oversight.</p>
 *
 * <p>The public/secret routing is a <b>whitelist</b>: {@link #isPublicTerrainNoise} names the public
 * noises and everything else falls to the secret side. That way a Minecraft update that adds a new
 * noise fails safe - the new noise is secret - rather than silently leaking.</p>
 */
public class Globals {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    public static final int WORLD_SEED_LONGS = 16;
    public static final int WORLD_SEED_BITS = WORLD_SEED_LONGS * 64;

    public static final long[] worldSeed = new long[WORLD_SEED_LONGS];

    /**
     * Rolia - the dimension domain separator for the CURRENT worldgen thread.
     *
     * <p>Set by {@link #setupGlobals} and read once per {@link WorldgenCryptoRandom} construction. It is
     * only correct on threads that went through {@code ChunkStep.apply} / {@code getGenerator()} - which
     * covers every worldgen path. Anything reached from elsewhere (the Bukkit API, {@code /summon}
     * spawn checks, stronghold ring generation) must pass its dimension EXPLICITLY through the
     * five-argument {@link WorldgenCryptoRandom} constructor; relying on the ambient value there gave
     * wrong and unstable answers before build 40.</p>
     */
    public static final ThreadLocal<Integer> dimension = ThreadLocal.withInitial(() -> 0);

    private static volatile boolean seedInitialized = false;
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Integer> DIMENSION_ID_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setupGlobals(ServerLevel world) {
        if (!seedInitialized) {
            synchronized (Globals.class) { // Rolia - publish the shared world seed exactly once, safely
                if (!seedInitialized) {
                    // Rolia - the PUBLIC level seed, needed by WorldgenCryptoRandom to reproduce vanilla
                    // exactly when the secure seed is switched off. Written before seedInitialized, so
                    // the volatile write below publishes it too.
                    levelSeed = world.getSeed();
                    long[] seed = normalizeLength(RoliaConfig.featureSeed());
                    System.arraycopy(seed, 0, worldSeed, 0, WORLD_SEED_LONGS);
                    seedInitialized = true; // Rolia - volatile write; publishes the array contents above
                    // Rolia - the one authoritative startup line, emitted at the point of publication and
                    // derived from real state, so it cannot report "active" for a seed that is not.
                    LOGGER.info("Rolia: config loaded (secure seed {}; DAB {}).",
                        isActive() ? "ACTIVE fp=" + seedFingerprint()
                            : (RoliaConfig.secureSeedEnabled() ? "INACTIVE" : "DISABLED (secure-seed.enabled=false)"),
                        RoliaConfig.dabEnabled() ? "ON" : "off");
                    if (!RoliaConfig.secureSeedEnabled()) {
                        LOGGER.warn("Rolia: secure-seed.enabled is FALSE - worldgen is plain Vanilla and everything");
                        LOGGER.warn("Rolia: in this world is computable from the public level-seed alone.");
                    }
                    // Rolia - one line per non-default option, so a support request never has to ask
                    // "what does your rolia.yml say?" and a mystery is one log grep away.
                    logNonDefaults();
                    // Rolia - CI asserts this against hashlib.blake2b; see Hashing#selfTestHex.
                    LOGGER.info("Rolia: blake2b-selftest {}", Hashing.selfTestHex());
                    LOGGER.info("Rolia: slime-selftest {}", slimeSelfTestHex());
                }
            }
        }
        // Rolia - refuse to keep generating into a world that was made with a different secret.
        //
        // PERFORMANCE, read before touching this. setupGlobals is called from
        // ServerChunkCache#getGenerator(), which runs constantly while chunks generate. Build 40.0 ran
        // the fingerprint check unconditionally here, so every single call paid a full BLAKE2b MAC, a
        // String.format, a DIRECTORY LISTING and several stat() syscalls. Chunk generation fell from
        // ~44 chunks/s to ~7. Nothing on this path may touch the filesystem or allocate per call.
        //
        // It still cannot be a one-shot inside the publish block: on the first boot of a brand-new world
        // the world directory does not exist yet at publication time, so the fingerprint would never be
        // written and the guard would only arm on the second boot. So: retry until it actually handles a
        // world, with a hard cap, then never look again.
        if (!fingerprintDone) {
            verifyFingerprintOnce();
        }
        dimension.set(stableDimensionId(world.dimension()));
    }

    /**
     * Rolia - list every option that is NOT at its default, once, at startup.
     *
     * <p>Stock Rolia is stock Canvas plus the secure seed, so this line is normally very short. When it
     * is not, the operator (and anyone reading their log in a bug report) can see immediately which
     * switches are responsible for whatever behaviour is being investigated. Secrets are excluded.</p>
     */
    private static void logNonDefaults() {
        try {
            final StringBuilder sb = new StringBuilder();
            int n = 0;
            for (final io.rolia.config.Opt<?> o : RoliaConfig.allOptions()) {
                if (o.isSecret() || o.isDefault()) {
                    continue;
                }
                if (n++ > 0) {
                    sb.append(", ");
                }
                sb.append(o.path).append('=').append(o.yamlValue());
            }
            if (n == 0) {
                LOGGER.info("Rolia: all {} options are at their defaults (stock Canvas behaviour + the secure seed).",
                    RoliaConfig.allOptions().size());
            } else {
                LOGGER.info("Rolia: {} non-default option(s): {}", n, sb);
            }
        } catch (final Throwable t) {
            LOGGER.warn("Rolia: could not list non-default options", t);
        }
    }

    private static volatile boolean fingerprintDone = false;
    private static final java.util.concurrent.atomic.AtomicInteger FINGERPRINT_ATTEMPTS = new java.util.concurrent.atomic.AtomicInteger();

    private static void verifyFingerprintOnce() {
        // Cap the scans so a server whose world directory never appears where we look cannot keep
        // hitting the disk forever. 64 attempts is far more than the handful of getGenerator() calls
        // that happen before the level storage exists.
        if (FINGERPRINT_ATTEMPTS.incrementAndGet() > 64) {
            fingerprintDone = true;
            return;
        }
        if (RoliaConfig.verifyWorldFingerprint(seedFingerprintCached())) {
            fingerprintDone = true;
        }
    }

    // Rolia - the fingerprint is a pure function of the (immutable) seed and salt, so format it once.
    private static volatile String cachedFingerprint;

    private static String seedFingerprintCached() {
        String fp = cachedFingerprint;
        if (fp == null) {
            fp = seedFingerprint();
            cachedFingerprint = fp;
        }
        return fp;
    }

    /**
     * Rolia - a STABLE domain separator for a dimension, derived from its identifier.
     *
     * <p>This used to be the dimension's ordinal POSITION in {@code levelKeys()}. That position is not
     * stable: {@code unloadWorld} + {@code createWorld} re-inserts a world at the end and shifts every
     * later world down by one, so those worlds would then generate NEW chunks from a different RNG
     * stream than the chunks already on disk - seams, doubled structures, ore discontinuities, with no
     * error. It also raced: two worlds registering concurrently both read {@code levelKeys().size()} and
     * got IDENTICAL separators. The identifier never moves, so the separator never moves.</p>
     */
    public static int stableDimensionId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        Integer cached = DIMENSION_ID_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        // Rolia - hash the FULL identifier. The old version packed only the first 64 bytes, so two
        // worlds whose ids agreed on a 64-byte prefix shared a separator - the very collision this
        // method exists to prevent.
        final byte[] idBytes = key.identifier().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final int id = (int) Hashing.mac(null, idBytes)[0];
        DIMENSION_ID_CACHE.put(key, id);
        return id;
    }

    /** Rolia - convenience for call sites that hold a level and must not rely on the ambient value. */
    public static int stableDimensionId(final ServerLevel level) {
        return stableDimensionId(level.dimension());
    }

    // ---------------------------------------------------------------------------------------------
    // Publication
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - a volatile read of the publication flag, establishing the happens-before edge to the
     * array writes inside the synchronized block above. Without it a worldgen thread may legally observe
     * a partially written (or all-zero) seed on weakly-ordered hardware (ARM/Graviton).
     */
    public static boolean seedPublished() {
        return seedInitialized;
    }

    /**
     * Rolia - the correct way to read the shared seed from a thread that did not itself call
     * {@link #setupGlobals}. The branch consumes the volatile read, so it cannot be optimised away.
     */
    public static long[] publishedWorldSeed() {
        if (seedInitialized) {
            return worldSeed; // published - the volatile read above orders the writes before this point
        }
        return worldSeed;
    }

    /** Rolia - true once a real (non-zero) 1024-bit seed is in effect. Used by the startup log + CI gate. */
    public static boolean isActive() {
        if (!isSecureSeedEnabled()) {
            // Rolia - switched off in rolia.yml. This also makes seedFingerprint() return the all-zero
            // value, so a world generated WITH the secret is detected on the next boot instead of
            // silently continuing with vanilla generation against secret-generated chunks.
            return false;
        }
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
     * Rolia - a 16-hex-char fingerprint of the active secret, safe to print.
     *
     * <p>Domain-separated from the RNG key (see {@link Hashing#fingerprint}). Until build 40 this
     * returned word 0 of the LIVE key, which published 64 key bits into every log and gave an attacker
     * a one-hash offline oracle for brute-forcing the salt.</p>
     */
    public static String seedFingerprint() {
        if (!isActive()) {
            return "0000000000000000";
        }
        return String.format("%016x", Hashing.fingerprint(worldSeed));
    }

    /** Rolia - pad/truncate a stored feature seed to exactly WORLD_SEED_LONGS so a malformed value cannot throw. */
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

    // ---------------------------------------------------------------------------------------------
    // Public / secret worldgen routing (build 40)
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - the noises that stay on the PUBLIC level seed because they define terrain SHAPE.
     *
     * <p>Deliberately a whitelist: anything not named here - including any noise a future Minecraft
     * version introduces - is routed to the secret. Failing safe matters more than failing convenient.</p>
     *
     * <p>Note {@code temperature} and {@code vegetation} are NOT here: they select which biome sits on a
     * landform, which is exactly the thing we want hidden. They do not feed the overworld heightmap.</p>
     */
    public static boolean isPublicTerrainNoise(final net.minecraft.resources.ResourceKey<?> noise) {
        if (!isSecureSeedEnabled()) {
            return true; // Rolia - switched off: every noise goes back to the public root, i.e. vanilla
        }
        final String path = noise.identifier().getPath();
        return switch (path) {
            case "continentalness", "continentalness_large",
                 "erosion", "erosion_large",
                 "ridge", "offset", "jagged" -> true;
            default -> false;
        };
    }

    /** Rolia - the same whitelist for the named positional factories (BlendedNoise uses "terrain"). */
    public static boolean isPublicTerrainFactory(final net.minecraft.resources.Identifier name) {
        if (!isSecureSeedEnabled()) {
            return true; // Rolia - switched off: vanilla routing
        }
        return "terrain".equals(name.getPath());
    }

    /**
     * Rolia - the root positional factory for every SECRET worldgen system.
     *
     * <p>Seeded with 128 bits derived from the 1024-bit secret through the keyed MAC. Vanilla itself
     * uses a 128-bit Xoroshiro positional factory, so this matches the engine's own strength while
     * being keyed by material an attacker does not have. This replaces build 39's arrangement, where
     * terrain security funnelled through a single 64-bit long and two vanilla code paths truncated it
     * to 48 bits - a routinely-executed seed-cracking workload.</p>
     */
    public static net.minecraft.world.level.levelgen.PositionalRandomFactory secretPositionalFactory(final String domain) {
        final long[] k = Hashing.derive(domain, publishedWorldSeed(), 0L);
        return new net.minecraft.world.level.levelgen.XoroshiroRandomSource(k[0], k[1]).forkPositional();
    }

    /**
     * Rolia - a full-width secret RandomSource for the two legacy Nether climate noises.
     *
     * <p>Vanilla builds those from {@code new LegacyRandomSource(seed + offset)}, which keeps 48 bits of
     * state. Nether biome climate is a SECRET system in the build 40 model, so it must not go through a
     * 48-bit funnel derived from anything public.</p>
     */
    public static net.minecraft.util.RandomSource secretClimateSource(final long offset) {
        final long[] k = Hashing.derive("climate-legacy", publishedWorldSeed(), offset);
        return new net.minecraft.world.level.levelgen.XoroshiroRandomSource(k[0], k[1]);
    }

    // ---------------------------------------------------------------------------------------------
    // Seed derivation for vanilla systems that would otherwise leak the raw level seed
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - a salt-protected 64-bit seed for vanilla systems that take a plain {@code long}
     * (loot random sequences, end spikes). Deterministic per (worldSeed, domain, salt).
     */
    public static long transformSeed(long levelSeed, long domain) {
        return Hashing.derive("legacy-transform", publishedWorldSeed(), levelSeed ^ domain)[0];
    }

    // Rolia - guarantee a real random 1024-bit feature seed: null/empty/all-zero means "uninitialised".
    public static long[] ensureRandomSeed(long[] seed) {
        if (seed == null || seed.length == 0) {
            return createRandomWorldSeed();
        }
        for (long v : seed) {
            if (v != 0L) {
                return normalizeLength(seed);
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

    /**
     * Rolia - derive a stable 1024-bit feature seed from a world's level seed, for a world that carries
     * no stored secret yet (an import from vanilla/Paper). Keyed by the salt, so it is still not
     * computable without it - but its real entropy is the salt's, not 1024 bits. Fresh Rolia worlds
     * never take this path; they get a fresh random seed written into rolia.yml.
     */
    public static long[] deriveFeatureSeedFromLevel(long levelSeed) {
        long[] seed = new long[WORLD_SEED_LONGS];
        long[] lo = Hashing.derive("import-lo", new long[WORLD_SEED_LONGS], levelSeed);
        long[] hi = Hashing.derive("import-hi", new long[WORLD_SEED_LONGS], levelSeed);
        System.arraycopy(lo, 0, seed, 0, 8);
        System.arraycopy(hi, 0, seed, 8, 8);
        return seed;
    }

    public static Optional<long[]> parseSeed(String seedStr) {
        if (seedStr == null || seedStr.isEmpty()) return Optional.empty();
        // Rolia - bound the input: an operator-supplied or file-supplied value of unbounded length
        // would otherwise stall or OOM startup inside BigInteger.
        if (seedStr.length() > 400) return Optional.empty();

        try {
            long[] seed = new long[WORLD_SEED_LONGS];
            BigInteger seedBigInt = new BigInteger(seedStr.trim());
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

    /**
     * Rolia - is the secure seed switched on? ({@code secure-seed.enabled} in rolia.yml, default true.)
     *
     * <p>This is the single gate for the whole system. When it is false, four things happen and nothing
     * else needs to know about it:</p>
     * <ul>
     *   <li>{@link #isPublicTerrainNoise} and {@link #isPublicTerrainFactory} answer true for
     *       everything, so {@code RandomState} routes every noise back through the public root;</li>
     *   <li>{@link #secretPositionalFactory} and {@link #secretClimateSource} are not called at all -
     *       the hooks in {@code RandomState} pick the vanilla expression instead;</li>
     *   <li>{@link WorldgenCryptoRandom} degrades to the plain {@code WorldgenRandom} it extends, so
     *       every one of the fifteen worldgen call sites becomes vanilla without being touched;</li>
     *   <li>{@link #isActive} is false, so the fingerprint written beside {@code level.dat} is the
     *       all-zero one - which means flipping this switch on a world that already exists is detected
     *       and reported exactly like any other secret change.</li>
     * </ul>
     *
     * <p>The result is an ordinary Minecraft world, derived from the ordinary level-seed, that can be
     * moved to any Paper or Folia server.</p>
     */
    public static boolean isSecureSeedEnabled() {
        return RoliaConfig.secureSeedEnabled();
    }

    /**
     * Rolia - the public level seed of the world being generated.
     *
     * <p>Captured in {@link #setupGlobals} because {@link WorldgenCryptoRandom} needs it to reproduce
     * vanilla exactly when the secure seed is switched off, and it has no level reference of its own.
     * All dimensions of a server share one level seed, so a single value is correct.</p>
     */
    public static long levelSeed() {
        return levelSeed;
    }

    private static volatile long levelSeed;

    public static String getSecureSeedSalt() {
        return RoliaConfig.salt();
    }

    /**
     * Rolia - digest over isSlimeChunk for a fixed 32x32 chunk window, logged once at startup.
     *
     * <p>CI asserts three things about it, and between them they cover the whole secret pipeline
     * cheaply, without generating a world:</p>
     * <ul>
     *   <li>identical across two boots of the same world - catching any regression to an ambient or
     *       thread-dependent dimension separator;</li>
     *   <li>different between two different secrets - proving the secret actually reaches worldgen;</li>
     *   <li>with {@code secure-seed.enabled: false}, identical for two DIFFERENT secrets and equal to
     *       what plain Vanilla would produce - proving the switch really does bypass the secret rather
     *       than merely hiding it.</li>
     * </ul>
     *
     * <p>The slime seed passed here is vanilla's own 987234911L rather than {@code spigotConfig.slimeSeed},
     * because this runs before any world config is available and it only has to be a fixed constant to
     * be comparable between runs.</p>
     */
    public static String slimeSelfTestHex() {
        long acc = 0x9E3779B97F4A7C15L;
        for (int x = -16; x < 16; x++) {
            for (int z = -16; z < 16; z++) {
                acc = Long.rotateLeft(acc, 1) ^ (WorldgenCryptoRandom.seedSlimeChunk(0, x, z, levelSeed, 987234911L).nextInt(10) == 0 ? 0x5BD1E995L : 0x27D4EB2FL);
                acc *= 0xBF58476D1CE4E5B9L;
            }
        }
        return String.format("%016x", acc);
    }

    /**
     * Rolia - domain separators for the secure RNG.
     *
     * <p><b>DO NOT REORDER, RENUMBER OR DELETE ANY CONSTANT.</b> The {@code id} below is mixed into
     * every generated chunk's random stream, so changing one silently reshuffles every structure,
     * decoration and slime chunk in every existing world - with no error and no migration path. The ids
     * were originally {@code ordinal()}; they are explicit precisely so a future reordering (or a
     * tidy-up of the currently-unused constants) cannot corrupt live worlds. Append new domains only.</p>
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
        // Rolia - build 38 added this for cave/ravine carvers but setLargeFeatureSeed overwrote the
        // domain on the next line, so it was dead. Build 40 makes it real: carvers keep their own
        // domain, so cave shape - which players can simply look at - no longer shares a keystream with
        // structure placement.
        CARVER(18),
        // Rolia - build 40: the Bukkit BlockPopulator random, previously sharing UNDEFINED with
        // structure-set placement (a datapack set with salt 0 collided exactly).
        BUKKIT_POPULATOR(19);

        public final int id;

        Salt(final int id) {
            this.id = id;
        }
    }
}
