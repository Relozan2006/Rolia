package io.rolia;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Rolia - one unified configuration file (rolia.yml) for everything Rolia adds on top of Canvas:
 *  - the 1024-bit secure world seed (the secret salt),
 *  - Dynamic Activation of Brain (DAB),
 *  - misc Rolia optimizations.
 * Canvas keeps its own configs (canvas-server.yml, canvas-worlds.yml, paper configs) untouched.
 * This file holds the secret salt, so it is created owner-only (0600) and must be kept secret + backed up.
 */
public final class RoliaConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "rolia.yml";
    private static final String LEGACY_FILE = "rolia-seed.properties";

    private static volatile boolean loaded;
    // Rolia - build 40: once loading has failed we must NOT retry on every getter. The getters sit on
    // the hottest paths in the server (dabEnabled() per mob per tick from every region thread,
    // fasterNetwork() per long[] from Netty threads), so re-entering the synchronized loader and
    // re-reading the file on each call turned "refuse to start" into an exception storm at an arbitrary
    // later moment, with a global monitor and file I/O per throw. Latch the failure and rethrow cheaply.
    private static volatile RuntimeException poisoned;

    // secure-seed
    private static String salt = "";
    // Rolia - build 40: the 1024-bit secret lives HERE, in the 0600 rolia.yml, and nowhere else. It used
    // to be read from server.properties (world-readable, and shown by every hosting panel's file
    // browser) and persisted into level.dat (so any world download or backup handed over the whole
    // secret). level.dat now carries only a fingerprint, which proves the world matches the secret
    // without revealing it.
    private static long[] featureSeed = null;
    // dab
    private static boolean dabEnabled = true;
    private static int dabStartDistance = 12;
    private static int dabMaxTickInterval = 20;
    private static Set<String> dabBlacklist = Set.of();
    // Rolia - the blacklist is CONFIGURED as entity-type ids, but TESTED per mob per tick, so it is
    // resolved once into the EntityType objects themselves and then tested with a plain set lookup
    // (zero allocation). The old hot path built a fresh String from the registry key for every mob on
    // every call. The string form above is kept for parsing/round-tripping the YAML.
    // Resolution must be LAZY: RoliaConfig is loaded very early (the secret salt is needed before any
    // world exists), long before BuiltInRegistries is populated and frozen - resolving inside
    // loadSlow() would silently produce an empty set. volatile: written once by whichever region
    // thread happens to resolve it first, read by all of them afterwards.
    private static volatile Set<EntityType<?>> dabBlacklistTypes;
    // villager lobotomization
    private static boolean lobotomizeEnabled = true;
    private static boolean lobotomizeWaitUntilTradeLocked = true;
    private static int lobotomizeCheckInterval = 100;
    private static boolean fasterNetwork = true;

    private RoliaConfig() {
    }

    public static String salt() { load(); return salt; }

    /**
     * Rolia - the 1024-bit secret feature seed, as 16 longs.
     *
     * <p>Read from {@code rolia.yml} and generated with {@link java.security.SecureRandom} on first run.
     * A defensive copy is returned so no caller can mutate the shared secret in place.</p>
     */
    public static long[] featureSeed() {
        load();
        final long[] s = featureSeed;
        return s == null ? new long[io.rolia.secureseed.Globals.WORLD_SEED_LONGS] : s.clone();
    }

    public static boolean dabEnabled() { load(); return dabEnabled; }
    public static int dabStartDistance() { load(); return dabStartDistance; }
    public static int dabMaxTickInterval() { load(); return dabMaxTickInterval; }
    public static boolean dabHasBlacklist() { load(); return !dabBlacklist.isEmpty(); }
    public static boolean lobotomizeEnabled() { load(); return lobotomizeEnabled; }
    public static boolean lobotomizeWaitUntilTradeLocked() { load(); return lobotomizeWaitUntilTradeLocked; }
    public static int lobotomizeCheckInterval() { load(); return lobotomizeCheckInterval; }
    public static boolean fasterNetwork() { load(); return fasterNetwork; }

    /**
     * Rolia - parse the stored 1024-bit feature seed. Accepts the decimal form written by
     * {@code writeConfig} and, defensively, a YAML list of 16 longs. Returns null when absent or
     * malformed, which makes the caller generate a fresh one.
     */
    private static long[] parseFeatureSeed(final Object raw) {
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            if (list.size() != io.rolia.secureseed.Globals.WORLD_SEED_LONGS) return null;
            final long[] out = new long[io.rolia.secureseed.Globals.WORLD_SEED_LONGS];
            for (int i = 0; i < out.length; i++) {
                final Object v = list.get(i);
                if (!(v instanceof Number n)) return null;
                out[i] = n.longValue();
            }
            return isAllZero(out) ? null : out;
        }
        final String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        final long[] parsed = io.rolia.secureseed.Globals.parseSeed(s).orElse(null);
        return parsed == null || isAllZero(parsed) ? null : parsed;
    }

    private static boolean isAllZero(final long[] a) {
        for (final long v : a) {
            if (v != 0L) return false;
        }
        return true;
    }

    /** Rolia - is this entity type excluded from DAB throttling? Allocation-free; see dabBlacklistTypes. */
    public static boolean dabBlacklisted(EntityType<?> type) {
        load();
        Set<EntityType<?>> types = dabBlacklistTypes;
        if (types == null) {
            types = resolveDabBlacklist();
        }
        return types.contains(type);
    }

    // Rolia - one-shot resolution of the configured ids, run on the first mob tick (registries frozen).
    private static synchronized Set<EntityType<?>> resolveDabBlacklist() {
        Set<EntityType<?>> types = dabBlacklistTypes;
        if (types != null) {
            return types; // another thread already resolved it
        }
        Set<EntityType<?>> resolved = new HashSet<>();
        for (String id : dabBlacklist) {
            if (id.isEmpty()) continue;
            EntityType<?> match = null;
            try {
                // Identifier.parse also supplies the default namespace, so both "minecraft:villager"
                // and "villager" resolve (the old string compare required the fully qualified form).
                Identifier key = Identifier.parse(id);
                EntityType<?> candidate = BuiltInRegistries.ENTITY_TYPE.getValue(key);
                // ENTITY_TYPE is a DEFAULTED registry: an unknown id silently returns the default type
                // (minecraft:pig) rather than null, so verify the round-trip instead of trusting the
                // lookup - otherwise a single typo would quietly exempt every pig on the server.
                // 'var': the registry key type is the only thing here we cannot see spelled out in
                // this tree, and Identifier.equals(Object) compares safely whatever it turns out to be.
                var resolvedKey = candidate == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(candidate);
                if (key.equals(resolvedKey)) {
                    match = candidate;
                }
            } catch (Exception ignored) {
                // malformed id - reported below
            }
            if (match == null) {
                LOGGER.warn("Rolia: unknown entity type '{}' in {} -> optimizations.dab.blacklist; ignoring it.", id, FILE_NAME);
            } else {
                resolved.add(match);
            }
        }
        types = Set.copyOf(resolved);
        dabBlacklistTypes = types;
        return types;
    }

    // Rolia - fast path: a volatile read, no monitor. This method is on the hottest paths in the
    // server (DAB per mob per tick, faster-network per long[] on Netty threads); the previous
    // `private static synchronized void load()` took a global monitor on every single call, even
    // after loading finished and even with every feature disabled. Java 25 has no biased locking,
    // so that was a real CAS on one shared mark word from every region thread at once.
    private static void load() {
        if (loaded) return;
        final RuntimeException dead = poisoned;
        if (dead != null) {
            // Rolia - already failed once; do not re-read the file and re-throw from a hot path.
            throw dead;
        }
        loadSlow();
    }

    @SuppressWarnings("unchecked")
    private static synchronized void loadSlow() {
        if (loaded) return;
        if (poisoned != null) throw poisoned;
        try {
            loadOnce();
        } catch (RuntimeException e) {
            poisoned = e;
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private static void loadOnce() {
        File file = new File(FILE_NAME).getAbsoluteFile();
        Map<String, Object> root = null;
        String legacySalt = null;

        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                Object parsed = new Yaml().load(in);
                if (parsed instanceof Map) {
                    root = (Map<String, Object>) parsed;
                }
            } catch (Exception e) {
                LOGGER.error("Rolia: {} exists but could not be parsed.", file, e);
                LOGGER.error("Rolia: refusing to start. Fix the YAML (or restore it from backup) and retry.");
                LOGGER.error("Rolia: continuing would generate a NEW secret salt and overwrite this file,");
                LOGGER.error("Rolia: which under Seed V2 re-generates terrain, biomes, caves, ores and structures");
                LOGGER.error("Rolia: for every newly loaded chunk - and destroys the only copy of the old secret.");
                throw new IllegalStateException("Rolia: unreadable " + FILE_NAME + " - refusing to start", e);
            }
        } else {
            // migration: import the secret salt from the legacy rolia-seed.properties so an existing
            // world keeps generating identically (losing the salt would shift structures/ores).
            File legacy = new File(LEGACY_FILE);
            if (legacy.isFile()) {
                Properties props = new Properties();
                try (InputStream in = new FileInputStream(legacy)) {
                    props.load(in);
                } catch (Exception ignored) {
                }
                String s = props.getProperty("secure-seed.salt", "");
                if (s != null && s.length() >= 64) {
                    legacySalt = s;
                    LOGGER.info("Rolia: migrating secure seed salt from {} to {}", LEGACY_FILE, FILE_NAME);
                }
            }
        }

        Map<String, Object> secureSeed = section(root, "secure-seed");
        Map<String, Object> optimizations = section(root, "optimizations");
        Map<String, Object> dab = section(optimizations, "dab");
        if (dab.isEmpty()) {
            dab = section(root, "dab"); // Rolia - back-compat: also accept a top-level dab: block
        }

        // secure-seed
        String cfgSalt = str(secureSeed.get("salt"), "");
        if (cfgSalt.length() < 64 && legacySalt != null) {
            cfgSalt = legacySalt;
        }
        boolean firstGen = !file.isFile();
        if (!cfgSalt.isEmpty() && cfgSalt.length() < 64) {
            // Never silently replace a salt the operator actually set - that would re-generate the world.
            LOGGER.error("Rolia: the configured secure-seed.salt in {} is only {} chars; 64+ are required.", file, cfgSalt.length());
            LOGGER.error("Rolia: refusing to start rather than replacing it, which would re-generate the world.");
            throw new IllegalStateException("Rolia: secure-seed.salt too short (" + cfgSalt.length() + " < 64)");
        }
        if (cfgSalt.isEmpty()) {
            LOGGER.info("Rolia: no secure seed salt found. Generating a new cryptographically secure salt...");
            cfgSalt = generateSecureSalt(64);
            firstGen = true;
            warnIfWorldAlreadyExists(file);
        }
        salt = cfgSalt;

        // Rolia - build 40: the 1024-bit feature seed. Stored here and nowhere else.
        long[] cfgSeed = parseFeatureSeed(secureSeed.get("feature-seed"));
        if (cfgSeed == null) {
            LOGGER.info("Rolia: no 1024-bit feature seed found. Generating a new cryptographically secure one...");
            cfgSeed = io.rolia.secureseed.Globals.createRandomWorldSeed();
            firstGen = true;
            warnIfWorldAlreadyExists(file);
        }
        featureSeed = cfgSeed;

        // dab
        dabEnabled = bool(dab.get("enabled"), true);
        dabStartDistance = clamp(intv(dab.get("start-distance"), 12), 1, 256);
        dabMaxTickInterval = clamp(intv(dab.get("max-tick-interval"), 20), 1, 200);
        dabBlacklist = strSet(dab.get("blacklist"));

        // villager lobotomization
        Map<String, Object> villagerLobo = section(optimizations, "villager-lobotomize");
        lobotomizeEnabled = bool(villagerLobo.get("enabled"), true);
        lobotomizeWaitUntilTradeLocked = bool(villagerLobo.get("wait-until-trade-locked"), true);
        // Rolia - ticks between boxed-in re-probes; the answer is cached in between (Purpur default 100)
        lobotomizeCheckInterval = clamp(intv(villagerLobo.get("check-interval"), 100), 1, 1200);
        fasterNetwork = bool(section(optimizations, "faster-network").get("enabled"), true);

        if (!firstGen) {
            if (villagerLobo.isEmpty()) {
                LOGGER.warn("Rolia: '{}' has no optimizations.villager-lobotomize section; defaulting enabled=true.", FILE_NAME);
                LOGGER.warn("Rolia: lobotomized villagers do not detect hostiles, sleep, gossip, breed or contribute");
                LOGGER.warn("Rolia: to iron-golem spawning. Trades and restocking are unaffected. Set enabled: false");
                LOGGER.warn("Rolia: if you run villager-based iron farms.");
            }
            if (section(optimizations, "faster-network").isEmpty()) {
                LOGGER.warn("Rolia: '{}' has no optimizations.faster-network section; defaulting enabled=true.", FILE_NAME);
            }
        }

        // Only (re)write the file on first generation / migration - never clobber a user-edited file.
        if (firstGen) {
            createPrivate(file); // Rolia - create the file owner-only (0600) BEFORE the secret salt is written
            writeConfig(file);
            LOGGER.info("Rolia: settings saved to {}", file);
            LOGGER.warn("Rolia: [IMPORTANT] {} holds the secret salt. Keep it secret and back it up with your world!", FILE_NAME);
        }
        if (file.isFile()) {
            restrictPermissions(file);
        }
        // Rolia - always log the ABSOLUTE path actually used. rolia.yml is resolved against the process
        // working directory (exactly as vanilla resolves server.properties), so a service unit or panel
        // that starts the JVM from a different directory would otherwise silently pick up a different
        // file - and a missing salt means a re-generated world. Now it is visible in the log.
        LOGGER.info("Rolia: using config {}", file);
        loaded = true; // Rolia - MUST stay the last write: publishes every field above to the lock-free fast path
        // The authoritative "config loaded (secure seed ...)" line is emitted by
        // io.rolia.secureseed.Globals#setupGlobals, where the real seed state is known.
    }

    /**
     * Rolia - build 40: tie a world to the secret that generated it.
     *
     * <p>{@code level.dat} no longer stores the 1024-bit seed, so a world download cannot leak it. What
     * it does need is a way to detect that the secret has CHANGED - because if it has, every newly
     * generated chunk silently stops matching the ones on disk, and that is unrecoverable. So we drop a
     * fingerprint file beside {@code level.dat} and compare it on every boot.</p>
     *
     * <p>Must be called AFTER loading completes: computing a fingerprint needs the salt, and asking for
     * the salt from inside the loader would re-enter it.</p>
     */
    // Rolia - worlds whose fingerprint has already been written or checked in this run. Keyed by
    // absolute path, so the filesystem is touched once per world rather than on every call.
    private static final Set<String> fingerprintChecked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean verifyWorldFingerprint(final String fingerprint) {
        boolean handledAny = false;
        try {
            final File dir = new File(FILE_NAME).getAbsoluteFile().getParentFile();
            if (dir == null) return false;
            final File[] candidates = dir.listFiles();
            if (candidates == null) return false;
            for (final File world : candidates) {
                if (!world.isDirectory()) continue;
                // Rolia - session.lock, not level.dat: level.dat does not exist yet during the FIRST
                // boot of a brand-new world (it is written at the first save), so keying on it meant the
                // fingerprint was never written for a fresh world and the guard only armed itself on the
                // second boot. session.lock is created the moment the level storage is opened.
                if (!new File(world, "session.lock").isFile() && !new File(world, "level.dat").isFile()) continue;
                handledAny = true;
                if (!fingerprintChecked.add(world.getAbsolutePath())) continue;
                final File fp = new File(world, "rolia-seed.fp");
                if (!fp.isFile()) {
                    java.nio.file.Files.writeString(fp.toPath(), fingerprint + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    continue;
                }
                final String stored = java.nio.file.Files.readString(fp.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!stored.equals(fingerprint)) {
                    LOGGER.error("Rolia: ############################################################");
                    LOGGER.error("Rolia: SECRET MISMATCH - the world '{}' was generated with a different secret.", world.getName());
                    LOGGER.error("Rolia: stored fingerprint {} does not match the active one {}.", stored, fingerprint);
                    LOGGER.error("Rolia: Every newly generated chunk would have different caves, ores, biomes and");
                    LOGGER.error("Rolia: structures than the chunks already on disk, with a hard seam between them,");
                    LOGGER.error("Rolia: and it cannot be repaired afterwards.");
                    LOGGER.error("Rolia: Restore the original {} from backup, or delete {} to accept the change.",
                        FILE_NAME, fp.getName());
                    LOGGER.error("Rolia: ############################################################");
                    // Rolia - halt rather than throw. This runs inside ServerLevel's constructor, so an
                    // exception here leaves the server half-initialised: Minecraft writes a crash report
                    // and then hangs in stopServer() on a world that was never finished. The operator
                    // would see the message above and then a process that never exits, and CI had to
                    // SIGKILL it. Give the async log appender a moment to flush, then halt(1) - skipping
                    // shutdown hooks is the point, since it is those hooks that hang.
                    try {
                        Thread.sleep(500L);
                    } catch (final InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    Runtime.getRuntime().halt(1);
                }
            }
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            LOGGER.warn("Rolia: could not verify the world secret fingerprint", e);
        }
        return handledAny;
    }

    /**
     * Rolia - generating a brand-new salt is correct on a fresh server and catastrophic on an existing
     * one: the salt keys caves, ores, biome climate and structures, so newly loaded chunks stop matching
     * the ones already on disk. If a world folder is already present, say so loudly rather than
     * proceeding in silence.
     */
    private static void warnIfWorldAlreadyExists(File configFile) {
        try {
            File dir = configFile.getParentFile();
            if (dir == null) return;
            File[] candidates = dir.listFiles();
            if (candidates == null) return;
            for (File c : candidates) {
                if (c.isDirectory() && new File(c, "level.dat").isFile()) {
                    LOGGER.warn("Rolia: ############################################################");
                    LOGGER.warn("Rolia: A NEW secret salt was just generated, but the world '{}' already exists.", c.getName());
                    LOGGER.warn("Rolia: If this world was generated with a DIFFERENT salt, every newly loaded");
                    LOGGER.warn("Rolia: chunk will have different terrain, biomes, caves, ores and structures");
                    LOGGER.warn("Rolia: than the chunks already on disk - you will see hard seams at the border.");
                    LOGGER.warn("Rolia: Stop the server NOW and restore the original {} from backup if you have it.", FILE_NAME);
                    LOGGER.warn("Rolia: ############################################################");
                    return;
                }
            }
        } catch (Exception ignored) {
            // best-effort diagnostic only
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rolia - chunk-system thread pool advisory
    //
    // Paper's config/paper-global.yml ships chunk-system.worker-threads: -1 and io-threads: -1, and
    // -1 means "auto". Both autos are traps:
    //
    //   io-threads     Moonrise resolves it as `Math.max(1, configIoThreads)` (see
    //                  MoonriseCommon#adjustWorkerThreads). -1 therefore resolves to exactly ONE I/O
    //                  thread on every machine, no matter how many cores it has. All region file
    //                  reads and writes for every world funnel through that single thread.
    //
    //   worker-threads Moonrise's auto ladder is roughly `d = cores / 2; d = d <= 4 ? (d <= 3 ? 1 : 2)
    //                  : d / 2`, so anything with 7 or fewer cores gets exactly ONE chunk-generation
    //                  worker, and 8-9 cores get two.
    //
    // Almost nobody discovers this, so say it out loud, once, at startup.
    // ------------------------------------------------------------------------------------------
    private static final java.util.concurrent.atomic.AtomicBoolean THREAD_ADVISORY_DONE =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Rolia - recommended chunk-system worker threads for a dedicated box, leaving room for Folia's region threads. */
    private static int recommendedWorkerThreads(final int cores) {
        if (cores <= 2) return 1;
        if (cores <= 4) return 2;
        if (cores <= 8) return 3;
        if (cores <= 12) return 4;
        if (cores <= 16) return 6;
        if (cores <= 24) return 7;
        return 8;
    }

    /** Rolia - recommended chunk-system I/O threads. Disk-bound, so this saturates early. */
    private static int recommendedIoThreads(final int cores) {
        if (cores <= 12) return 2;
        if (cores <= 24) return 3;
        return 4;
    }

    /**
     * Rolia - read the CONFIGURED chunk-system thread counts out of Paper's global config.
     *
     * <p>Done reflectively on purpose. {@code io.papermc.paper.configuration.GlobalConfiguration} is
     * Paper-internal and its field names are not part of any API contract, so binding to them at
     * compile time would let an upstream rename break the whole build for the sake of a log line.
     * Returns {@code null} when the values cannot be reached, and the caller falls back to advising
     * unconditionally.</p>
     *
     * @return {@code {workerThreads, ioThreads}} exactly as configured (may be -1), or null.
     */
    private static int[] readConfiguredChunkSystemThreads() {
        try {
            final Class<?> cfgClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            final Object cfg = cfgClass.getMethod("get").invoke(null);
            if (cfg == null) return null;
            final Object chunkSystem = cfgClass.getField("chunkSystem").get(cfg);
            if (chunkSystem == null) return null;
            final Class<?> csClass = chunkSystem.getClass();
            final int worker = csClass.getField("workerThreads").getInt(chunkSystem);
            final int io = csClass.getField("ioThreads").getInt(chunkSystem);
            return new int[] { worker, io };
        } catch (final Throwable ignored) {
            // Paper config not loaded yet, renamed, or otherwise unreachable - advise unconditionally.
            return null;
        }
    }

    /**
     * Rolia - warn once, loudly, when the chunk system is about to run on one worker and/or one I/O
     * thread on a machine that clearly has cores to spare. Safe to call from anywhere; the first
     * caller wins and every later call is a single volatile read.
     */
    public static void warnIfChunkSystemThreadsUnderconfigured() {
        if (!THREAD_ADVISORY_DONE.compareAndSet(false, true)) return;
        try {
            final int cores = Runtime.getRuntime().availableProcessors();
            if (cores < 4) return; // nothing useful to recommend on a 1-3 core box

            final int recWorker = recommendedWorkerThreads(cores);
            final int recIo = recommendedIoThreads(cores);

            final int[] configured = readConfiguredChunkSystemThreads();
            final String resolvedNote;
            if (configured == null) {
                // Could not reach the values - state the recommendation without claiming to know
                // what is currently set.
                resolvedNote = null;
            } else {
                final int cfgWorker = configured[0];
                final int cfgIo = configured[1];
                // Moonrise's own resolution, mirrored here.
                final int resolvedIo = Math.max(1, cfgIo);
                int resolvedWorker = cfgWorker;
                if (resolvedWorker <= 0) {
                    int d = cores / 2;
                    d = d <= 4 ? (d <= 3 ? 1 : 2) : d / 2;
                    resolvedWorker = d;
                }
                if (resolvedWorker != 1 && resolvedIo != 1) {
                    return; // already configured sensibly, stay quiet
                }
                resolvedNote = "currently worker-threads=" + (cfgWorker <= 0 ? "-1 (auto -> " + resolvedWorker + ")" : String.valueOf(resolvedWorker))
                    + ", io-threads=" + (cfgIo <= 0 ? "-1 (auto -> " + resolvedIo + ")" : String.valueOf(resolvedIo));
            }

            LOGGER.warn("Rolia: ############################################################");
            LOGGER.warn("Rolia: CHUNK SYSTEM THREADS - this machine reports {} available processors.", cores);
            if (resolvedNote != null) {
                LOGGER.warn("Rolia: {}.", resolvedNote);
                LOGGER.warn("Rolia: At least one of those pools is running on a SINGLE thread.");
            } else {
                LOGGER.warn("Rolia: Could not read the configured values, so check them by hand.");
            }
            LOGGER.warn("Rolia: Paper ships both of these as -1, and -1 does NOT mean 'use all cores':");
            LOGGER.warn("Rolia:   io-threads     -1 resolves to max(1, -1) = 1 thread on EVERY machine.");
            LOGGER.warn("Rolia:   worker-threads -1 resolves to 1 thread on any box with 7 or fewer cores.");
            LOGGER.warn("Rolia: One I/O thread means every region-file read and write for every world");
            LOGGER.warn("Rolia: queues behind one thread, which shows up as chunk-load stalls, not as MSPT.");
            LOGGER.warn("Rolia: Set these in config/paper-global.yml and restart:");
            LOGGER.warn("Rolia:     chunk-system:");
            LOGGER.warn("Rolia:       worker-threads: {}", recWorker);
            LOGGER.warn("Rolia:       io-threads: {}", recIo);
            LOGGER.warn("Rolia: (sized for {} cores, deliberately leaving cores free for Folia's region threads)", cores);
            LOGGER.warn("Rolia: ############################################################");
        } catch (final Throwable t) {
            LOGGER.warn("Rolia: could not run the chunk-system thread advisory", t);
        }
    }

    private static void writeConfig(File file) {
        String yaml = ""
            + "# Rolia configuration - everything Rolia adds on top of Canvas lives here.\n"
            + "# Canvas keeps its own configs (canvas-server.yml, canvas-worlds.yml, paper-*.yml) - do not put Rolia options there.\n"
            + "# IMPORTANT: this file contains the secret salt. Keep it secret and BACK IT UP together with your world.\n"
            + "# If the salt is lost, newly generated structures/ores in an existing world will no longer match the old ones.\n"
            + "\n"
            + "secure-seed:\n"
            + "  # Seed V2: the secret protects the ENTIRE world - terrain, biomes, caves, structures, ores, dungeons.\n"
            + "  # Always on, cannot be disabled. Nothing's location can be computed from the public level seed.\n"
            + "  # 64+ char secret salt, auto-generated on first run. The master key of the 1024-bit seed protection.\n"
            + "  salt: \"" + salt + "\"\n"
            + "\n"
            + "  # The 1024-bit feature seed, as a decimal integer. Auto-generated on first run.\n"
            + "  # Build 40: this lives HERE and nowhere else. It is no longer read from server.properties\n"
            + "  # (world-readable, and shown by every hosting panel) and no longer written into level.dat\n"
            + "  # (so a world download or backup no longer hands over the secret). level.dat keeps only a\n"
            + "  # fingerprint, which proves a world matches this secret without revealing it.\n"
            + "  # BACK THIS FILE UP. Lose it and the structures/ores/caves of an existing world are gone.\n"
            + "  feature-seed: \"" + io.rolia.secureseed.Globals.seedToString(featureSeed) + "\"\n"
            + "\n"
            + "# Optimizations - Folia-safe performance toggles.\n"
            + "# DEFAULTS: dab, villager-lobotomize and faster-network are ALL ON by default.\n"
            + "# Of the three, only faster-network is behaviour-neutral. dab and villager-lobotomize DO\n"
            + "# change mob behaviour; both are described honestly below. Turn them off if you want strictly\n"
            + "# Vanilla mob behaviour everywhere and can afford the CPU.\n"
            + "optimizations:\n"
            + "  # Dynamic Activation of Brain (DAB): mobs far from every player think LESS OFTEN. A mob's\n"
            + "  # AI (brain sensors + behaviours, or the goal selector) is run once every N ticks instead of\n"
            + "  # every tick, where N scales from 1 up to max-tick-interval with distance to the nearest\n"
            + "  # player. Mobs within start-distance blocks of a player are never throttled.\n"
            + "  #\n"
            + "  # This is NOT behaviour-neutral. A throttled mob reacts late: it notices targets, changes\n"
            + "  # path, flees, and re-aims on a coarser clock, so distant mobs drift, wander and converge\n"
            + "  # differently than in Vanilla. Movement, physics, damage, despawning and mob spawning are\n"
            + "  # untouched, so mob-farm RATES are normally unaffected, but any farm that depends on\n"
            + "  # precise distant pathing can change. Use blacklist below to exempt specific types.\n"
            + "  dab:\n"
            + "    enabled: " + dabEnabled + "\n"
            + "    # Mobs closer than this many blocks to a player always tick every tick (full AI).\n"
            + "    start-distance: " + dabStartDistance + "\n"
            + "    # The farthest mobs tick their AI at most once per this many ticks.\n"
            + "    max-tick-interval: " + dabMaxTickInterval + "\n"
            + "    # Entity type ids never throttled (useful for mob farms), e.g. [\"minecraft:villager\"].\n"
            + "    blacklist: []\n"
            + "  # Lobotomize stuck villagers (ON by default): a villager boxed in a 1x1 cell cannot path\n"
            + "  # anywhere, so its whole brain tick is skipped. Trades and RESTOCKING are preserved, so pure\n"
            + "  # trading halls behave like Vanilla.\n"
            + "  #\n"
            + "  # This is NOT behaviour-neutral either. Skipping the brain skips every sensor and behaviour:\n"
            + "  # a lobotomized villager does NOT detect hostiles (so it will not flee or scream when a zombie\n"
            + "  # arrives), does NOT sleep, does NOT gossip, does NOT breed, and does NOT count towards IRON\n"
            + "  # GOLEM spawning. Set enabled: false if you run villager-based iron farms, breeders, or any\n"
            + "  # design that relies on villager panic or gossip.\n"
            + "  villager-lobotomize:\n"
            + "    enabled: " + lobotomizeEnabled + "\n"
            + "    # Keep full AI for villagers that have not been traded with yet (0 xp) so they can still\n"
            + "    # gain their first profession level. Recommended true.\n"
            + "    wait-until-trade-locked: " + lobotomizeWaitUntilTradeLocked + "\n"
            + "    # How often (in ticks) a villager is re-checked for being boxed in; the answer is cached\n"
            + "    # in between, because the check costs up to 8 block + collision-shape lookups per\n"
            + "    # villager. Lower = a villager freed from its cell wakes up sooner; higher = cheaper.\n"
            + "    # 100 ticks = 5 seconds (Purpur's default). Clamped to 1-1200.\n"
            + "    check-interval: " + lobotomizeCheckInterval + "\n"
            + "  # Faster network: bulk-write long arrays (chunk light/heightmap) in one copy instead of a loop.\n"
            + "  # Bytes on the wire are IDENTICAL - purely faster serialization.\n"
            + "  faster-network:\n"
            + "    enabled: " + fasterNetwork + "\n";

        // Rolia - write atomically via a temp file, and never replace an existing config without
        // first copying it aside. A half-written or clobbered rolia.yml means a lost secret.
        try {
            java.nio.file.Path target = file.toPath();
            if (java.nio.file.Files.exists(target)) {
                java.nio.file.Path backup = target.resolveSibling(FILE_NAME + ".bak." + System.currentTimeMillis());
                try {
                    java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    LOGGER.warn("Rolia: previous {} backed up to {}", FILE_NAME, backup.getFileName());
                } catch (Exception e) {
                    LOGGER.warn("Rolia: could not back up the existing {} before rewriting it", FILE_NAME, e);
                }
            }
            java.nio.file.Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
            java.nio.file.Files.deleteIfExists(tmp);
            createPrivate(tmp.toFile()); // owner-only before the secret is written into it
            java.nio.file.Files.writeString(tmp, yaml, java.nio.charset.StandardCharsets.UTF_8);
            restrictPermissions(tmp.toFile());
            try {
                java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOGGER.error("Rolia: failed to save {}", file, e);
        }
    }

    // ---- helpers ----
    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> root, String key) {
        if (root != null && root.get(key) instanceof Map) {
            return (Map<String, Object>) root.get(key);
        }
        return java.util.Collections.emptyMap();
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o).trim());
        return def;
    }

    private static int intv(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static Set<String> strSet(Object o) {
        if (o instanceof List<?> list) {
            Set<String> set = new HashSet<>();
            for (Object item : list) {
                if (item != null) set.add(String.valueOf(item).trim());
            }
            return set;
        }
        return Set.of();
    }

    // Rolia - create the config file with owner-only (0600) permissions up front, so the secret salt is
    // never written into a briefly world-readable file. On non-POSIX filesystems (Windows) this falls back
    // to a best-effort tighten; restrictPermissions() also runs again after the write.
    private static void createPrivate(File file) {
        if (file.exists()) {
            restrictPermissions(file);
            return;
        }
        try {
            java.nio.file.Path p = file.toPath();
            if (java.nio.file.Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                java.nio.file.Files.createFile(p, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
            }
        } catch (Exception e) {
            LOGGER.warn("Rolia: could not pre-create {} with restricted permissions - protect it manually!", FILE_NAME);
        }
    }

    private static void restrictPermissions(File file) {
        try {
            java.nio.file.Path p = file.toPath();
            java.nio.file.attribute.PosixFileAttributeView view =
                java.nio.file.Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } else {
                file.setReadable(false, false);
                file.setReadable(true, true);
                file.setWritable(false, false);
                file.setWritable(true, true);
            }
        } catch (Exception e) {
            LOGGER.warn("Rolia: could not restrict permissions on {} - protect it manually!", FILE_NAME);
        }
    }

    private static String generateSecureSalt(int length) {
        SecureRandom random = new SecureRandom();
        // charset intentionally excludes " and \ so the value is safe inside a YAML double-quoted string
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }
}
