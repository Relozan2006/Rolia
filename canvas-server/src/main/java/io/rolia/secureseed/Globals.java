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
 * <h2>Build 46 model: the MAP is public, the CONTENTS are secret</h2>
 *
 * <p>Worldgen is deliberately split in two:</p>
 * <ul>
 *   <li><b>Public</b>, derived from the ordinary {@code level-seed} in server.properties: the terrain
 *       SHAPE and the BIOME MAP. Concretely the {@code continentalness}, {@code erosion}, {@code ridge},
 *       {@code offset} and {@code jagged} noises, the {@code temperature} and {@code vegetation} climate
 *       noises, {@code BlendedNoise}, and End-island shape. Anyone who knows the level seed can
 *       reproduce the landscape and see which biome sits on it.</li>
 *   <li><b>Secret</b>, derived from the 1024-bit feature seed plus the salt: everything worth finding.
 *       Surface rules, decorations, caves, ravines, ore veins, aquifers, structures and their loot,
 *       slime chunks, End spike layout and stronghold rings.</li>
 * </ul>
 *
 * <p>Builds 40-45 kept biome climate on the secret side. Build 46 moved it, deliberately: a world you
 * cannot preview is hard to choose, and hiding the climate never hid the thing that matters. What
 * protects the ore and the structures is the placement roll, and that is still secret. The cost is
 * disclosed rather than hidden - because the biome map is public, anything that follows strictly from
 * biome is inferable, and the world spawn point becomes predictable.</p>
 *
 * <p>The public/secret routing is a <b>whitelist</b>: {@link #isPublicTerrainNoise} names the public
 * noises, namespace included, and everything else falls to the secret side. That way a Minecraft update
 * that adds a new noise, or a datapack that registers one under a name we know, fails safe - it is
 * secret - rather than silently leaking.</p>
 */
public class Globals {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    public static final int WORLD_SEED_LONGS = 16;
    public static final int WORLD_SEED_BITS = WORLD_SEED_LONGS * 64;

    /**
     * Rolia - build 44: private. This was {@code public static final}, and {@link #publishedWorldSeed}
     * handed back the LIVE array, so any plugin could print the 1024-bit secret to chat with
     * {@code Globals.seedToString(Globals.worldSeed)} - no reflection needed - or overwrite it and
     * silently change worldgen mid-session. That is outside the "attacker observes the world" threat
     * model but squarely inside "this server runs third-party plugins".
     */
    private static final long[] worldSeed = new long[WORLD_SEED_LONGS];

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
                    LOGGER.info("Rolia: config loaded (secure seed {}).",
                        isActive() ? "ACTIVE fp=" + seedFingerprint()
                            : (RoliaConfig.secureSeedEnabled() ? "INACTIVE" : "DISABLED (secure-seed.enabled=false)"));
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
    private static volatile long fingerprintDeadline = 0L;
    private static volatile long lastFingerprintAttempt = 0L;
    private static final long FINGERPRINT_RETRY_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);

    /**
     * Rolia - arm the "this world was made with a different secret" guard, giving up on a CLOCK.
     *
     * <p>Build 46 replaced a counter with a deadline, because the counter could not work on this
     * server. The budget was 64 attempts, justified as "far more than the handful of getGenerator()
     * calls that happen before the level storage exists" - which is a single-threaded estimate on a
     * fork that has no main thread. {@code setupGlobals} is called from every Folia chunk worker and
     * region thread at once, so on a 16-worker box the 64 attempts are spent in milliseconds, quite
     * possibly before the level storage directory exists at all. After that {@code fingerprintDone}
     * was true forever: no fingerprint was ever written beside {@code level.dat}, and
     * {@code on-secret-mismatch: block} silently protected nothing for the life of that world.</p>
     *
     * <p>A deadline cannot be exhausted by concurrency - it is the same 60 seconds no matter how many
     * threads are asking - and running out of it is now reported instead of being silent.</p>
     */
    private static void verifyFingerprintOnce() {
        final long now = System.nanoTime();
        long deadline = fingerprintDeadline;
        if (deadline == 0L) {
            deadline = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            fingerprintDeadline = deadline;
        }

        // Throttle the retries. This runs from ServerChunkCache#getGenerator(), which is called
        // constantly while chunks generate, and the check lists a directory. Build 40.0 ran it
        // unconditionally here and chunk generation fell from ~44 chunks/s to ~7. One attempt per
        // 250ms costs nothing and is bounded by wall clock rather than by thread count, so adding
        // workers cannot make it either more expensive or less likely to succeed.
        final long last = lastFingerprintAttempt;
        if (last != 0L && now - last < FINGERPRINT_RETRY_NANOS) {
            return;
        }
        lastFingerprintAttempt = now;

        if (RoliaConfig.verifyWorldFingerprint(seedFingerprintCached())) {
            fingerprintDone = true;
            return;
        }
        if (now - deadline >= 0L) {
            fingerprintDone = true;
            LOGGER.error("Rolia: no world directory was found within 60s, so the secret-mismatch guard is");
            LOGGER.error("Rolia: now DISABLED for this run. A world generated with a different rolia.yml");
            LOGGER.error("Rolia: will NOT be detected. This usually means the server was started from a");
            LOGGER.error("Rolia: directory that does not contain the world folder - check the 'using config'");
            LOGGER.error("Rolia: line above and start the server from beside your world.");
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
     * {@link #setupGlobals}. The volatile read of {@code seedInitialized} establishes the happens-before
     * edge to the array writes inside the publication block.
     *
     * <p>Build 46 made the guard real. Both branches used to {@code return worldSeed}, so the check was
     * a no-op that the JIT was free to drop entirely, and the javadoc's claim that "the branch consumes
     * the volatile read" described a branch that did not exist. That mattered because
     * {@code WorldgenCryptoRandom} caches the derived key for the whole process on first use: a single
     * read before publication would have baked the ALL-ZERO seed into every chunk the server ever
     * generates, silently, while the startup line still printed a healthy-looking fingerprint. Fail
     * loudly instead - if this ever throws, the alternative was an unrecoverable world.</p>
     */
    static long[] publishedWorldSeed() { // Rolia - build 44: package-private. It hands back the LIVE secret array; nothing outside io.rolia.secureseed has ever needed it, and leaving it public meant the private field above bought nothing.
        if (!seedInitialized && isSecureSeedEnabled()) {
            throw new IllegalStateException(
                "Rolia: worldgen asked for the secret before it was published. This is a bug in the "
                    + "publication hooks (ServerChunkCache#getGenerator, ServerLevel construction, "
                    + "ChunkStep#apply); generating now would key the entire world off zeros.");
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
     * <p>The namespace is checked, not just the path. Matching on the path alone was a fail-OPEN hole
     * in exactly the property this method advertises: a datapack or mod registering {@code mypack:erosion}
     * or {@code mypack:temperature} was routed to the public root and derived from the ordinary
     * level-seed. The secret side has always used the full namespaced id as its domain string, so only
     * this side was blind.</p>
     */
    public static boolean isPublicTerrainNoise(final net.minecraft.resources.ResourceKey<?> noise) {
        if (!isSecureSeedEnabled()) {
            return true; // Rolia - switched off: every noise goes back to the public root, i.e. vanilla
        }
        final net.minecraft.resources.Identifier id = noise.identifier();
        if (!net.minecraft.resources.Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            return false; // Rolia - build 46: a third-party noise of the same name is NOT the vanilla one
        }
        final String path = id.getPath();
        return switch (path) {
            // terrain shape
            case "continentalness", "continentalness_large",
                 "erosion", "erosion_large",
                 "ridge", "offset", "jagged" -> true;
            // Rolia - build 46: biome climate joins the public side.
            //
            // Since 1.18 the multi-noise biome source reads six parameters: continentalness, erosion,
            // weirdness (that is `ridge`), depth, temperature and vegetation. The first three are the
            // very noises the landscape is built from and were already public; depth is computed from
            // terrain, so it was public by construction. Only temperature and vegetation were secret -
            // and those two are exactly what decides whether a mountain is snowy or jungle. Moving them
            // here makes the biome map reproducible from the ordinary level-seed, which is the point:
            // a seed-finder site now shows the right biomes as well as the right landscape.
            //
            // Nothing else moves. Surface rules, decorations, carvers, aquifers, ore, structures, loot
            // and slime chunks keep their own independent secret domains, so the biome map still cannot
            // tell you where anything actually is.
            case "temperature", "temperature_large",
                 "vegetation", "vegetation_large" -> true;
            default -> false;
        };
    }

    /**
     * Rolia - the same whitelist for the named positional factories (BlendedNoise uses "terrain").
     *
     * <p>Namespace-checked for the same reason as {@link #isPublicTerrainNoise}. The vanilla id is a
     * constant so the exposure was theoretical, but a whitelist that is namespace-blind in one place
     * and not the other is a trap for whoever reads one and assumes the other.</p>
     */
    public static boolean isPublicTerrainFactory(final net.minecraft.resources.Identifier name) {
        if (!isSecureSeedEnabled()) {
            return true; // Rolia - switched off: vanilla routing
        }
        return net.minecraft.resources.Identifier.DEFAULT_NAMESPACE.equals(name.getNamespace())
            && "terrain".equals(name.getPath());
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
    /**
     * Rolia - build 44: pick the secret root for one worldgen domain, or the caller's VANILLA
     * expression when {@code secure-seed.enabled} is false.
     *
     * <p>Every secret worldgen system now names its own domain and gets an independent 128-bit seed,
     * instead of all of them being {@code fromHashOf} offsets of one shared root - see
     * {@link #secretPositionalFactory}. Passing the vanilla expression in means the disabled path is
     * literally the vanilla code, with no second copy of it to drift.</p>
     *
     * @param vanilla what Vanilla would have used here; evaluated either way, but only once per world
     * @param domain  a stable name for this system, mixed into the key derivation
     */
    public static net.minecraft.world.level.levelgen.PositionalRandomFactory secretOr(
        final net.minecraft.world.level.levelgen.PositionalRandomFactory vanilla, final String domain) {
        return isSecureSeedEnabled() ? secretPositionalFactory(domain) : vanilla;
    }

    public static net.minecraft.world.level.levelgen.PositionalRandomFactory secretPositionalFactory(final String domain) {
        // Rolia - build 44, two changes, both of which need a fresh world (which is why they land now).
        //
        // 1. THE LEVEL SEED IS MIXED IN. It was not, so the whole secret noise layer - biome climate,
        //    caves, ore veins, aquifers, surface rules - was a function of the 1024-bit secret alone.
        //    On a multiworld server that meant a "resource" world regenerated with a fresh level-seed
        //    got new terrain shape but the SAME caves, the same ore and the same climate at the same
        //    coordinates, where Vanilla changes all of it with the seed. It also weakened the threat
        //    model: mapping world A's caves handed you world B's for free.
        //
        // 2. EVERY DOMAIN GETS ITS OWN ROOT. Callers used to take one "worldgen-root" factory and
        //    derive from it with fromHashOf(...). Vanilla's XoroshiroPositionalRandomFactory is AFFINE
        //    in its seed - fromHashOf and at() are public XOR offsets - so all ~40 secret noises and
        //    every position in them were offsets of a single 128-bit unknown, and recovering it
        //    anywhere recovered it everywhere. The worst case was SurfaceSystem, which received the
        //    root directly and drew the badlands terracotta banding straight off it: ~200 draws whose
        //    results you can read off the terrain with your eyes.
        //
        //    Each domain now gets an independent 128-bit seed from the keyed MAC, so breaking one
        //    tells you nothing about the others. Within one domain at() is still affine, exactly as in
        //    Vanilla - that is a deliberate limit, not an oversight: this is called once per noise or
        //    per system (about forty times for a whole world), never per block, so it costs nothing,
        //    whereas making at() cryptographic would put a BLAKE2b compression on the per-position
        //    path and undo the build-41 performance work.
        final long[] k = Hashing.derive(domain, publishedWorldSeed(), levelSeed());
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
        // Rolia - build 44: mix the level seed, same reasoning as secretPositionalFactory above.
        final long[] k = Hashing.derive("climate-legacy", publishedWorldSeed(), offset ^ levelSeed());
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
        if (!isSecureSeedEnabled()) {
            // Rolia - build 44: this had NO enabled-check, and publishedWorldSeed() returns the real
            // secret regardless of the switch. Its two callers are loot random sequences and the End
            // spike layout, so with secure-seed.enabled=false a world still had non-Vanilla loot and
            // non-Vanilla obsidian pillars, both keyed to rolia.yml - directly contradicting the
            // config's own promise that such a world "is an ordinary Minecraft world and can be moved
            // to any server". Vanilla's own mixing for these two call sites is the plain XOR.
            return levelSeed ^ domain;
        }
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

    /**
     * Rolia - parse the stored 1024-bit feature seed, refusing anything that is not exactly one.
     *
     * <p>Build 46 made this strict. It used to accept up to 400 characters and then discard whatever
     * did not fit in 1024 bits, and to mask a negative value into range - so a corrupted line produced
     * a DIFFERENT, valid-looking secret instead of an error. That defeated the whole point of build
     * 44's "present but malformed is a hard refusal": the corruption modes that rule was written for
     * (a truncated line, an editor that wrapped the 309-digit number, a bad merge) are exactly the ones
     * that change its length or sign. Silently generating a different world is the worst possible
     * response to a damaged secret.</p>
     */
    public static Optional<long[]> parseSeed(String seedStr) {
        if (seedStr == null || seedStr.isEmpty()) return Optional.empty();
        final String trimmed = seedStr.trim();
        // A 1024-bit value is at most 309 decimal digits. Bounding the length before BigInteger also
        // stops a file- or operator-supplied value of unbounded length from stalling startup.
        if (trimmed.isEmpty() || trimmed.length() > 309) return Optional.empty();

        try {
            final BigInteger value = new BigInteger(trimmed);
            // Refuse rather than reinterpret: both of these used to be silently rewritten into some
            // other secret, which is indistinguishable from the operator's real one at a glance.
            if (value.signum() < 0 || value.bitLength() > WORLD_SEED_BITS) return Optional.empty();

            long[] seed = new long[WORLD_SEED_LONGS];
            BigInteger seedBigInt = value;
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
