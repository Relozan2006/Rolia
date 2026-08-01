package io.rolia;

import io.rolia.config.ConfigWriter;
import io.rolia.config.Opt;
import io.rolia.config.Opt.BoolOpt;
import io.rolia.config.Opt.Reload;
import io.rolia.config.Opt.StringOpt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rolia - {@code rolia.yml}: everything Rolia adds on top of Canvas, in one file.
 *
 * <h2>The rule this file follows</h2>
 *
 * <p>Stock Rolia behaves exactly like stock Canvas, plus the secure seed. Every behaviour Rolia changes
 * is a key here, and every one of those keys is {@code false} by default. The single exception is
 * {@code secure-seed.enabled}, which is the reason this fork exists; it too can be turned off.</p>
 *
 * <p>Bug fixes are NOT keys. Rolia fixes a number of genuine defects inherited from Canvas - a null
 * dereference in the spawn path, a file-descriptor leak, ender pearls lost on world unload, a
 * projectile reading its X coordinate as Z. Those are always on and have no switch, because shipping a
 * known crash behind an opt-in flag would be a strange kind of configurability. They are listed in the
 * generated file for reference so it is clear what is happening and why there is no key for it.</p>
 *
 * <h2>How options are declared</h2>
 *
 * <p>Once, below, as an {@link Opt}. The parser, the generated YAML, the startup line and the key
 * list CI checks the Russian documentation against are all derived from that single declaration, so
 * they cannot fall out of step. See {@link Opt} for why that matters.</p>
 *
 * <p>This file also holds the 1024-bit secret, so it is created owner-only (0600). Keep it secret and
 * back it up with the world.</p>
 */
public final class RoliaConfig {
    // Rolia - one logger name for the whole fork. Canvas-inherited code logs as "Rolia" too (see
    // io.canvasmc.canvas.GlobalConfiguration), so an operator reading the console sees one project.
    private static final Logger LOGGER = LoggerFactory.getLogger("Rolia");
    private static final String FILE_NAME = "rolia.yml";
    private static final String LEGACY_FILE = "rolia-seed.properties";

    private static volatile boolean loaded;
    // Rolia - build 40: once loading has failed we must NOT retry on every getter. The getters sit on
    // the hottest paths in the server (dab per mob per tick from every region thread, faster-network per
    // long[] from Netty threads), so re-entering the synchronized loader and re-reading the file on each
    // call turned "refuse to start" into an exception storm at an arbitrary later moment, with a global
    // monitor and file I/O per throw. Latch the failure and rethrow cheaply.
    private static volatile RuntimeException poisoned;

    private RoliaConfig() {
    }

    // =============================================================================================
    // THE OPTION REGISTRY
    //
    // Declaration order below IS the order of the generated rolia.yml. Keep related options together
    // and put the honest warning in the comment, not in a changelog nobody reads.
    // =============================================================================================

    // ---------------------------------------------------------------------------------------------
    // secure-seed
    // ---------------------------------------------------------------------------------------------
    static {
        Opt.section("secure-seed",
            "THE SECURE 1024-BIT WORLD SEED - the reason this fork exists.",
            "",
            "Ordinary Minecraft derives everything from one 64-bit level seed. That seed can be",
            "recovered from a handful of observed structures, after which every stronghold, every",
            "ancient city, every buried treasure and every ore vein in the world is known to whoever",
            "recovered it. Rolia keeps a separate 1024-bit secret, stored only in this file, and derives",
            "the parts of the world worth hiding from it instead.",
            "",
            "WHAT IS PUBLIC: terrain SHAPE. Continentalness, erosion, ridges, offset, jagged noise and",
            "blended noise all still come from the level-seed in server.properties, so the landscape is",
            "reproducible by anyone who knows it, and you can still pick a landscape you like from a",
            "seed-finding site.",
            "",
            "WHAT IS SECRET: everything else. Biome climate (which biome sits on a given landform),",
            "caves, ravines, ore veins, aquifers, surface rules, structures, decorations, loot and slime",
            "chunks - all derived from the 1024-bit secret below.",
            "",
            "THE HONEST LIMIT: since Minecraft 1.18 the biome source reads six parameters, and four of",
            "them (continentalness, erosion, depth, ridges) are the same noises that build the terrain.",
            "Anyone with the level-seed therefore knows four of the six. Temperature and vegetation stay",
            "secret, and those are what decide whether a given mountain is snowy or jungle. This is a",
            "deliberate, documented consequence of keeping terrain public - not an oversight.");

        Opt.note(
            "BACK THIS FILE UP, TOGETHER WITH YOUR WORLD.",
            "The salt and feature-seed below exist nowhere else. They are no longer stored in",
            "server.properties (world-readable) or in level.dat (which travels with any world download).",
            "level.dat keeps only a fingerprint, which proves a world matches this secret without",
            "revealing it. Lose this file and the caves, ores and structures of an existing world are",
            "gone - newly generated chunks will not match the ones already on disk, and it cannot be",
            "repaired afterwards.");
    }

    public static final BoolOpt SECURE_SEED_ENABLED = new BoolOpt(
        "secure-seed.enabled", true, Reload.RESTART,
        "Master switch. This is the ONLY option in this file that defaults to true.",
        "",
        "Set to false and worldgen goes back to plain Vanilla: everything is derived from the ordinary",
        "level-seed, exactly as on Paper or Folia, and the secret below is ignored entirely. A world",
        "generated with this false is an ordinary Minecraft world and can be moved to any server.",
        "",
        "Do not flip this on a world that already exists. See on-secret-mismatch below.");

    public static final StringOpt SECURE_SEED_SALT = (StringOpt) new StringOpt(
        "secure-seed.salt", "", Set.of(), Reload.RESTART,
        "The 64+ character secret salt, generated with SecureRandom on first run.",
        "Half of the master key. Never share it, never paste it into a bug report.").markSecret();

    public static final StringOpt SECURE_SEED_FEATURE_SEED = (StringOpt) new StringOpt(
        "secure-seed.feature-seed", "", Set.of(), Reload.RESTART,
        "The 1024-bit feature seed as a decimal integer, generated on first run.",
        "The other half of the master key.").markSecret();

    public static final StringOpt SECURE_SEED_ON_MISMATCH = new StringOpt(
        "secure-seed.on-secret-mismatch", "block", Set.of("warn", "block", "ignore"), Reload.RESTART,
        "What to do when this file's secret does not match the world on disk - because the file was",
        "lost and regenerated, or because secure-seed.enabled was flipped after the world existed.",
        "",
        "  block  refuse to start, so nothing is generated until you have decided (default)",
        "  warn   log a very loud warning and start anyway",
        "  ignore say nothing",
        "",
        "The consequence of continuing is not subtle: newly generated chunks get different caves, ores",
        "and structures from the ones already on disk, with a hard seam between them, and there is no",
        "way to repair it afterwards. That is why the default is 'block' - a server that will not start",
        "is an inconvenience, a world quietly generating against the wrong secret is unrecoverable. On a",
        "brand new world nothing is stored yet, so this can only trigger when there is a real mismatch.");

    public static final StringOpt SECURE_SEED_SEED_COMMAND = new StringOpt(
        "secure-seed.seed-command", "fingerprint", Set.of("fingerprint", "hidden", "vanilla"), Reload.LIVE,
        "What /seed shows.",
        "",
        "  fingerprint  the public level-seed plus a one-way fingerprint of the secret (default).",
        "               The fingerprint proves two worlds share a secret without revealing it.",
        "  hidden       the public level-seed only.",
        "  vanilla      Vanilla behaviour. Harmless when secure-seed.enabled is false; when it is true",
        "               this still only shows the public level-seed, because the secret is never in",
        "               level.dat to begin with.");

    // =============================================================================================
    // Typed accessors used by the rest of the server.
    //
    // These exist so call sites read like English and so the hot ones compile to a single volatile
    // field read with no map lookup, no string hashing and no lock.
    // =============================================================================================

    public static boolean secureSeedEnabled() { load(); return SECURE_SEED_ENABLED.get0(); }
    public static String salt() { load(); return SECURE_SEED_SALT.get0(); }
    public static String onSecretMismatch() { load(); return SECURE_SEED_ON_MISMATCH.get0(); }
    public static String seedCommandMode() { load(); return SECURE_SEED_SEED_COMMAND.get0(); }



    /**
     * Rolia - the 1024-bit secret feature seed, as 16 longs.
     *
     * <p>A defensive copy is returned so no caller can mutate the shared secret in place.</p>
     */
    public static long[] featureSeed() {
        load();
        final long[] s = featureSeedParsed;
        return s == null ? new long[io.rolia.secureseed.Globals.WORLD_SEED_LONGS] : s.clone();
    }

    private static volatile long[] featureSeedParsed;

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    // Rolia - fast path: a volatile read, no monitor. This method is on the hottest paths in the
    // server; a `private static synchronized void load()` took a global monitor on every single call,
    // even after loading finished and even with every feature disabled. Java 25 has no biased locking,
    // so that was a real CAS on one shared mark word from every region thread at once.
    private static void load() {
        if (loaded) {
            return;
        }
        final RuntimeException dead = poisoned;
        if (dead != null) {
            throw dead;
        }
        loadSlow();
    }

    private static synchronized void loadSlow() {
        if (loaded) {
            return;
        }
        if (poisoned != null) {
            throw poisoned;
        }
        try {
            loadOnce();
        } catch (final RuntimeException e) {
            poisoned = e;
            throw e;
        }
    }

    /**
     * Rolia - read {@code rolia.yml}, or return null if and only if it does not exist.
     *
     * <p>Returning null for anything else would be a world-destroying bug, and was one until build 46.
     * {@code Yaml.load} returns {@code null} rather than throwing for a file that is empty, contains
     * only comments, or is truncated before its first mapping key, and returns a String or a List for
     * a file clobbered with something else. All of those used to collapse into the same "null" that
     * means "no file yet", so the caller generated a fresh secret and overwrote the file - destroying
     * the only copy of the old one, with an INFO line for a gravestone.</p>
     *
     * <p>That is not a theoretical window. The generated file opens with roughly sixty lines of
     * comments before the salt, so a truncated write or a power cut that lands anywhere in that header
     * leaves a file which is perfectly valid YAML for {@code null}.</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(final File file) {
        if (!file.isFile()) {
            return null; // the ONLY "there is no config yet" answer this method may give
        }
        Object parsed;
        try (final InputStream in = new FileInputStream(file)) {
            parsed = new Yaml().load(in);
        } catch (final Exception e) {
            // Rolia - build 46: the exception is deliberately NOT logged and NOT attached as a cause.
            // SnakeYAML's MarkedYAMLException embeds a ~75-character snippet of the offending source
            // line, and the likeliest line to be malformed in this file is the salt or the 309-digit
            // feature seed - the two strings that must never reach latest.log or a pasted crash report.
            // The position is pulled out separately, without the snippet, because "fix the YAML" with
            // no line number is not much of an instruction.
            refuseUnreadable(file, e.getClass().getSimpleName() + describePosition(e));
            throw new IllegalStateException("Rolia: unreadable " + FILE_NAME + " - refusing to start");
        }
        if (!(parsed instanceof Map)) {
            refuseUnreadable(file, parsed == null
                ? "the file is empty, contains only comments, or was truncated before the first key"
                : "the top level is a " + parsed.getClass().getSimpleName() + ", not a mapping");
            throw new IllegalStateException("Rolia: " + FILE_NAME + " contains no configuration mapping");
        }
        return (Map<String, Object>) parsed;
    }

    /**
     * Rolia - " at line N, column M" from a SnakeYAML error, and nothing else.
     *
     * <p>{@code Mark.toString()} - which is what the exception's own message contains - includes a
     * snippet of the source line. That is the one thing that must not be logged here. The coordinates
     * on their own carry no content.</p>
     */
    private static String describePosition(final Exception e) {
        if (e instanceof org.yaml.snakeyaml.error.MarkedYAMLException m && m.getProblemMark() != null) {
            return " at line " + (m.getProblemMark().getLine() + 1)
                + ", column " + (m.getProblemMark().getColumn() + 1);
        }
        return "";
    }

    private static void refuseUnreadable(final File file, final String why) {
        LOGGER.error("############################################################");
        LOGGER.error("{} exists but could not be read: {}", file, why);
        LOGGER.error("Refusing to start. Fix the YAML (or restore it from backup) and retry.");
        LOGGER.error("Continuing would generate a NEW secret and overwrite this file, which");
        LOGGER.error("re-generates caves, ores and structures for every newly loaded chunk");
        LOGGER.error("and destroys the only copy of the old secret.");
        LOGGER.error("If this world is genuinely new and you want a fresh secret, delete the file.");
        LOGGER.error("############################################################");
    }

    private static void loadOnce() {
        final File file = new File(FILE_NAME).getAbsoluteFile();
        final Map<String, Object> root = parse(file);

        // Apply every non-secret option from the registry. Missing keys keep their defaults, which is
        // what makes a hand-trimmed file safe: you can delete everything you do not care about.
        for (final Opt<?> opt : Opt.options()) {
            if (opt.isSecret()) {
                continue; // handled below, where the generate-on-absence rules live
            }
            opt.apply(Opt.resolve(root, opt.path), false);
        }

        final boolean firstGen = resolveSecret(file, root);

        // Only (re)write the file on first generation or migration - never clobber a user-edited file.
        if (firstGen) {
            // Rolia - build 46: no createPrivate(file) here any more. It called Files.createFile, so a
            // fresh install always passed through a state where rolia.yml existed and was EMPTY. That
            // used to be self-healing, because an empty file parsed as "no config yet"; now that an
            // empty file is a hard refusal - which it has to be, since it is indistinguishable from a
            // truncated one - the same window would brick the NEXT boot if the process died inside it.
            // writeConfig creates rolia.yml.tmp owner-only, writes the secret into that, and moves it
            // atomically, so the target is never world-readable and never exists empty.
            if (!writeConfig(file)) {
                // Rolia - build 44: refuse to start. See writeConfig's javadoc - continuing here means
                // generating a world against a secret that was never persisted, which is exactly the
                // unrecoverable state the fingerprint guard exists to report after the fact. Better to
                // stop now, while the operator can still fix the permissions and lose nothing.
                LOGGER.error("############################################################");
                LOGGER.error("A new secret was generated but {} could NOT be written.", file);
                LOGGER.error("The secret currently exists only in memory. If the server kept running it");
                LOGGER.error("would generate a world from a secret that is lost the moment it stops, and");
                LOGGER.error("that world could never be extended consistently again.");
                LOGGER.error("Fix write access to that path and start again.");
                LOGGER.error("############################################################");
                throw new IllegalStateException("Rolia: could not persist a newly generated secret to " + file);
            }
            LOGGER.info("settings saved to {}", file);
            LOGGER.warn("[IMPORTANT] {} holds the secret. Keep it secret and back it up with your world!", FILE_NAME);
        }
        if (file.isFile()) {
            restrictPermissions(file);
        }
        // Always log the ABSOLUTE path actually used. rolia.yml is resolved against the process working
        // directory (exactly as Vanilla resolves server.properties), so a service unit or panel that
        // starts the JVM from a different directory would otherwise silently pick up a different file -
        // and a missing secret means a re-generated world. Now it is visible in the log.
        LOGGER.info("using config {}", file);

        dumpKeysIfRequested();

        loaded = true; // MUST stay the last write: publishes every field above to the lock-free fast path
        // The authoritative "config loaded (secure seed ...)" line is emitted by
        // io.rolia.secureseed.Globals#setupGlobals, where the real seed state is known.
    }

    /**
     * Rolia - load or generate the two secret values.
     *
     * @return true when something was generated and the file therefore needs writing
     */
    private static boolean resolveSecret(final File file, final Map<String, Object> root) {
        boolean firstGen = !file.isFile();

        String cfgSalt = root == null ? "" : str(Opt.resolve(root, SECURE_SEED_SALT.path));
        if (cfgSalt.length() < 64) {
            refuseLegacyMigration();
        }
        if (!cfgSalt.isEmpty() && cfgSalt.length() < 64) {
            // Never silently replace a salt the operator actually set - that would re-generate the world.
            LOGGER.error("the configured secure-seed.salt in {} is only {} chars; 64+ are required.", file, cfgSalt.length());
            LOGGER.error("Refusing to start rather than replacing it, which would re-generate the world.");
            throw new IllegalStateException("Rolia: secure-seed.salt too short (" + cfgSalt.length() + " < 64)");
        }
        if (cfgSalt.isEmpty()) {
            LOGGER.info("no secure seed salt found. Generating a new cryptographically secure salt...");
            cfgSalt = generateSecureSalt(64);
            firstGen = true;
            warnIfWorldAlreadyExists(file);
        }
        SECURE_SEED_SALT.apply(cfgSalt, false);

        // Rolia - build 44: distinguish ABSENT from PRESENT-BUT-UNPARSEABLE. A too-short salt has always
        // been a hard refusal ("refusing to start rather than replacing it, which would re-generate the
        // world") while a corrupt feature seed was silently regenerated - and the feature seed IS the
        // 1024-bit secret. One stray character in a 309-digit number (a truncated line, an editor that
        // wrapped it, a bad merge) destroyed the world just as thoroughly as losing the salt, and the
        // salt surviving made the file look fine. The asymmetry was not defensible.
        final Object rawSeed = root == null ? null : Opt.resolve(root, SECURE_SEED_FEATURE_SEED.path);
        long[] cfgSeed = parseFeatureSeed(rawSeed);
        if (cfgSeed == null && rawSeed != null) {
            LOGGER.error("the stored secure-seed.feature-seed in {} could not be parsed.", file);
            LOGGER.error("Refusing to start rather than replacing it, which would re-generate the world.");
            LOGGER.error("Restore {} from backup, or delete the feature-seed line to accept a new world.", FILE_NAME);
            throw new IllegalStateException("Rolia: secure-seed.feature-seed is present but malformed");
        }
        if (cfgSeed == null) {
            LOGGER.info("no 1024-bit feature seed found. Generating a new cryptographically secure one...");
            cfgSeed = io.rolia.secureseed.Globals.createRandomWorldSeed();
            firstGen = true;
            warnIfWorldAlreadyExists(file);
        }
        featureSeedParsed = cfgSeed;
        SECURE_SEED_FEATURE_SEED.apply(io.rolia.secureseed.Globals.seedToString(cfgSeed), false);
        return firstGen;
    }

    /**
     * Rolia - refuse to start on a pre-build-40 layout instead of half-migrating it.
     *
     * <p>Until build 46 this method imported the salt from {@code rolia-seed.properties} and logged
     * "migrating secure seed salt", above a javadoc promising "so an existing world survives". It did
     * not survive. Before build 40 the secret had two halves in two files: the salt in
     * {@code rolia-seed.properties} and the 1024-bit feature seed in {@code server.properties} as
     * {@code feature-level-seed}. This method only ever read the first, so the caller then generated a
     * brand-new feature seed - replacing half the master key while reporting success.</p>
     *
     * <p>Nothing downstream could catch it either: no fingerprint file existed before build 40, so the
     * mismatch guard wrote a fresh fingerprint rather than detecting anything. Migrating half a key is
     * strictly worse than refusing, so build 46 refuses and says exactly what to carry over by hand.</p>
     */
    private static void refuseLegacyMigration() {
        if (!new File(LEGACY_FILE).isFile()) {
            return; // no legacy layout: the caller's normal generate-on-absence rules apply
        }
        LOGGER.error("############################################################");
        LOGGER.error("Found {}, which is the pre-build-40 layout, and no usable secret in {}.", LEGACY_FILE, FILE_NAME);
        LOGGER.error("Rolia will NOT migrate it automatically. That secret has two halves and only one");
        LOGGER.error("of them lives in that file, so an automatic import would replace the other half");
        LOGGER.error("and silently destroy the world it was supposed to rescue.");
        LOGGER.error("");
        LOGGER.error("To migrate by hand, create {} with:", FILE_NAME);
        LOGGER.error("  secure-seed:");
        LOGGER.error("    salt: <the secure-seed.salt value from {}>", LEGACY_FILE);
        LOGGER.error("    feature-seed: <the feature-level-seed value from server.properties>");
        LOGGER.error("");
        LOGGER.error("If you do not have both values, that world cannot be extended consistently.");
        LOGGER.error("To start a NEW world instead, delete {}.", LEGACY_FILE);
        LOGGER.error("############################################################");
        throw new IllegalStateException("Rolia: pre-build-40 " + LEGACY_FILE + " found - migrate the secret by hand");
    }

    /**
     * Rolia - parse the stored 1024-bit feature seed. Accepts the decimal form written by
     * {@link #writeConfig} and, defensively, a YAML list of 16 longs. Returns null when absent or
     * malformed, which makes the caller generate a fresh one.
     */
    private static long[] parseFeatureSeed(final Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            if (list.size() != io.rolia.secureseed.Globals.WORLD_SEED_LONGS) {
                return null;
            }
            final long[] out = new long[io.rolia.secureseed.Globals.WORLD_SEED_LONGS];
            for (int i = 0; i < out.length; i++) {
                if (!(list.get(i) instanceof Number n)) {
                    return null;
                }
                out[i] = n.longValue();
            }
            return isAllZero(out) ? null : out;
        }
        final String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        final long[] parsed = io.rolia.secureseed.Globals.parseSeed(s).orElse(null);
        return parsed == null || isAllZero(parsed) ? null : parsed;
    }

    private static boolean isAllZero(final long[] a) {
        for (final long v : a) {
            if (v != 0L) {
                return false;
            }
        }
        return true;
    }

    private static String str(final Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    // Rolia - build 46: reload() and ReloadResult were deleted here. They existed only to serve
    // `/rolia reload`, and build 46 removed that command along with the options it reported, so both
    // had zero callers repo-wide. Keeping a public API that nothing calls invites someone to wire it
    // back up without noticing that its last comment described an entity blacklist cache that no
    // longer exists either. The Reload.LIVE/RESTART distinction stays: it is what the generated file's
    // "(takes effect on the next server restart)" line is derived from.

    /**
     * Rolia - every NON-SECRET option with its current value, for the startup line.
     *
     * <p>Build 44: the filter used to live in each of the two callers, so the javadoc's promise that
     * "secrets are never included" was true only by the good behaviour of everyone who called it. A
     * method whose contract says "no secrets" and which hands you the secrets is a leak waiting for
     * its third caller.</p>
     */
    public static List<Opt<?>> allOptions() {
        load();
        final List<Opt<?>> out = new ArrayList<>();
        for (final Opt<?> o : Opt.options()) {
            if (!o.isSecret()) {
                out.add(o);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------

    private static final String[] HEADER = {
        "Rolia configuration - everything Rolia adds on top of Canvas lives here.",
        "",
        "Canvas keeps its own configuration in config/canvas-server.yml and config/canvas-worlds.yml,",
        "and Paper keeps its own in config/paper-global.yml and config/paper-world-defaults.yml.",
        "Rolia does not override either; do not put Rolia options there or Canvas options here.",
        "",
        "THE RULE THIS FILE FOLLOWS: a stock Rolia server behaves exactly like a stock Canvas server,",
        "plus the secure seed. Every behaviour Rolia changes is a key here, and every one of those keys",
        "defaults to false. The only exception is secure-seed.enabled, which is why this fork exists.",
        "",
        "Rolia's BUG FIXES are not keys and are always applied - a null dereference in the mob spawn",
        "path, a file-descriptor leak on config reload, ender pearls lost when a world unloads, a",
        "projectile reading its X coordinate as Z, and several data races. Shipping a known crash",
        "behind an opt-in flag would be a strange kind of configurability, so there is no switch.",
        "",
        "РУССКАЯ ВЕРСИЯ ЭТОГО ФАЙЛА (Russian translation of every comment below):",
        ConfigWriter.DOCS_URL_RU,
        "",
        "IMPORTANT: this file contains the secret that protects your world. Keep it secret and BACK IT",
        "UP together with the world folder.",
    };

    /**
     * Rolia - write the file. Returns false when it could not be written.
     *
     * <p>Build 44 made this report its outcome. It used to be {@code void} and swallow every failure
     * into a log line, while {@code loadOnce} logged "settings saved to ..." immediately afterwards
     * and carried on. On the FIRST run that is catastrophic: a brand-new 1024-bit secret has just been
     * generated, and if the write failed (read-only mount, full disk, a panel-managed volume) that
     * secret exists only in this JVM's heap. The server generates a world with it, the next restart
     * generates a different one, and the world is unrecoverable - by a condition the code had already
     * detected and merely written to the log.</p>
     */
    private static boolean writeConfig(final File file) {
        final String yaml = ConfigWriter.render(HEADER);
        // Rolia - build 46: no automatic backups. Earlier builds copied the existing file aside as
        // rolia.yml.bak.<timestamp> before rewriting it. That scattered copies of the 1024-bit secret
        // around the server directory - each one owner-readable, each one a thing to leak in a support
        // archive or a world download, and none of them ever cleaned up. Backing up a secret without
        // being asked is not a favour. The operator is told, loudly and once, that this file is the
        // only copy and that it is theirs to keep safe.
        //
        // The atomic temp-file write below stays: that is not a backup, it is what stops a power cut
        // in the middle of a write from leaving a truncated config and an unrecoverable world.
        //
        // Rolia - build 46: that sentence was false until the force() calls below were added. The old
        // code was Files.writeString followed by Files.move, and neither flushes anything: writeString
        // closes the stream but leaves the data in the page cache, and move renames a directory entry.
        // A crash could therefore make the RENAME durable while the DATA was not - the classic
        // zero-length-file-after-rename outcome, and a normal post-crash result on XFS and btrfs. Since
        // build 46 also removed the .bak copies, this file has no redundancy left at all, so its
        // durability has to be real rather than asserted. Fsync the data, then fsync the directory that
        // holds the new name.
        final java.nio.file.Path target = file.toPath();
        final java.nio.file.Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
        try {
            java.nio.file.Files.deleteIfExists(tmp);
            createPrivate(tmp.toFile()); // owner-only before the secret is written into it
            final byte[] bytes = yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (final java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                final java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true); // data and metadata on disk BEFORE anything points at it
            }
            restrictPermissions(tmp.toFile());
            try {
                java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            fsyncDirectory(target.getParent());
            return true;
        } catch (final Exception e) {
            LOGGER.error("failed to save {}", file, e);
            return false;
        } finally {
            // Rolia - build 46: on a failed move the temp file used to survive, holding the full salt
            // and 1024-bit seed. That is exactly the stray readable copy of the key that removing the
            // .bak files was meant to stop. After a successful move it no longer exists and this is a
            // no-op.
            try {
                java.nio.file.Files.deleteIfExists(tmp);
            } catch (final Exception ignored) {
                // nothing useful to do, and the caller already knows whether the write succeeded
            }
        }
    }

    /**
     * Rolia - make a rename durable. A POSIX rename is only on disk once the DIRECTORY entry is.
     *
     * <p>Windows neither allows opening a directory as a channel nor needs this, so a failure is
     * ignored rather than reported: the data itself was already forced before the rename, and the
     * caller must not fail a write that actually succeeded.</p>
     */
    private static void fsyncDirectory(final java.nio.file.Path dir) {
        if (dir == null) {
            return;
        }
        try (final java.nio.channels.FileChannel ch =
                 java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (final Exception ignored) {
            // non-POSIX filesystem, or a directory that cannot be opened; see the javadoc
        }
    }

    /**
     * Rolia - write the option key list when {@code -Drolia.dumpConfigKeys=<file>} is set.
     *
     * <p>CI uses this to prove that docs/rolia.yml.ru.md documents exactly the options that exist, no
     * more and no less. Without it the Russian documentation would rot silently the first time an
     * option was added, which is the normal fate of translated documentation.</p>
     */
    private static void dumpKeysIfRequested() {
        final String path = System.getProperty("rolia.dumpConfigKeys");
        if (path == null || path.isEmpty()) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), ConfigWriter.dumpKeys(),
                java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("wrote {} config keys to {}", Opt.options().size(), path);
        } catch (final Exception e) {
            LOGGER.error("could not write the config key dump to {}", path, e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // World fingerprint
    // ---------------------------------------------------------------------------------------------

    // Rolia - worlds whose fingerprint has already been written or checked in this run. Keyed by
    // absolute path, so the filesystem is touched once per world rather than on every call.
    private static final Set<String> fingerprintChecked = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Rolia - tie a world to the secret that generated it.
     *
     * <p>{@code level.dat} does not store the 1024-bit seed, so a world download cannot leak it. What it
     * does need is a way to detect that the secret has CHANGED - because if it has, every newly
     * generated chunk silently stops matching the ones on disk, and that is unrecoverable. So a
     * fingerprint file is dropped beside {@code level.dat} and compared on every boot.</p>
     *
     * <p>What happens on a mismatch is {@code secure-seed.on-secret-mismatch}. Since build 46 the
     * default is {@code block} - refuse to start. A server that will not start is an inconvenience,
     * while a world quietly generating against the wrong secret cannot be repaired afterwards. Set it
     * to {@code warn} if you would rather be told very loudly and continue anyway.</p>
     *
     * @return true when at least one world was actually examined, so the caller can stop retrying
     */
    public static boolean verifyWorldFingerprint(final String fingerprint) {
        boolean handledAny = false;
        try {
            final File dir = new File(FILE_NAME).getAbsoluteFile().getParentFile();
            if (dir == null) {
                return false;
            }
            final File[] candidates = dir.listFiles();
            if (candidates == null) {
                return false;
            }
            for (final File world : candidates) {
                if (!world.isDirectory()) {
                    continue;
                }
                // session.lock, not level.dat: level.dat does not exist yet during the FIRST boot of a
                // brand-new world (it is written at the first save), so keying on it meant the
                // fingerprint was never written for a fresh world and the guard only armed itself on the
                // second boot. session.lock is created the moment the level storage is opened.
                if (!new File(world, "session.lock").isFile() && !new File(world, "level.dat").isFile()) {
                    continue;
                }
                handledAny = true;
                if (!fingerprintChecked.add(world.getAbsolutePath())) {
                    continue;
                }
                final File fp = new File(world, "rolia-seed.fp");
                if (!fp.isFile()) {
                    java.nio.file.Files.writeString(fp.toPath(), fingerprint + System.lineSeparator(),
                        java.nio.charset.StandardCharsets.UTF_8);
                    continue;
                }
                final String stored = java.nio.file.Files.readString(fp.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!stored.equals(fingerprint)) {
                    reportMismatch(world.getName(), stored, fingerprint, fp.getName());
                }
            }
        } catch (final IllegalStateException e) {
            throw e;
        } catch (final Exception e) {
            LOGGER.warn("could not verify the world secret fingerprint", e);
        }
        return handledAny;
    }

    private static void reportMismatch(final String world, final String stored, final String active, final String fpName) {
        final String mode = onSecretMismatch();
        if ("ignore".equals(mode)) {
            return;
        }
        LOGGER.error("############################################################");
        LOGGER.error("SECRET MISMATCH - the world '{}' was generated with a different secret.", world);
        LOGGER.error("stored fingerprint {} does not match the active one {}.", stored, active);
        LOGGER.error("Every newly generated chunk will have different caves, ores, biomes and structures");
        LOGGER.error("than the chunks already on disk, with a hard seam between them, and it cannot be");
        LOGGER.error("repaired afterwards.");
        LOGGER.error("Restore the original {} from backup, or delete {} to accept the change.", FILE_NAME, fpName);
        if ("block".equals(mode)) {
            LOGGER.error("secure-seed.on-secret-mismatch is 'block', so the server is stopping now.");
            LOGGER.error("############################################################");
            // Halt rather than throw. This runs inside ServerLevel's constructor, so an exception here
            // leaves the server half-initialised: Minecraft writes a crash report and then hangs in
            // stopServer() on a world that was never finished. Give the async log appender a moment to
            // flush, then halt(1) - skipping shutdown hooks is the point, since it is those hooks that
            // hang.
            try {
                Thread.sleep(500L);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(1);
        }
        LOGGER.error("secure-seed.on-secret-mismatch is '{}', so the server is starting anyway.", mode);
        LOGGER.error("Set it to 'block' if you would rather the server refuse to boot in this situation.");
        LOGGER.error("############################################################");
    }

    /**
     * Rolia - generating a brand-new secret is correct on a fresh server and catastrophic on an
     * existing one: it keys caves, ores, biome climate and structures, so newly loaded chunks stop
     * matching the ones already on disk. If a world folder is already present, say so loudly rather
     * than proceeding in silence.
     */
    private static void warnIfWorldAlreadyExists(final File configFile) {
        try {
            final File dir = configFile.getParentFile();
            if (dir == null) {
                return;
            }
            final File[] candidates = dir.listFiles();
            if (candidates == null) {
                return;
            }
            for (final File c : candidates) {
                if (c.isDirectory() && new File(c, "level.dat").isFile()) {
                    LOGGER.warn("############################################################");
                    LOGGER.warn("A NEW secret was just generated, but the world '{}' already exists.", c.getName());
                    LOGGER.warn("If this world was generated with a DIFFERENT secret, every newly loaded chunk");
                    LOGGER.warn("will have different terrain, biomes, caves, ores and structures than the chunks");
                    LOGGER.warn("already on disk - you will see hard seams at the border.");
                    LOGGER.warn("Stop the server NOW and restore the original {} from backup if you have it.", FILE_NAME);
                    LOGGER.warn("############################################################");
                    return;
                }
            }
        } catch (final Exception ignored) {
            // best-effort diagnostic only
        }
    }

    // ---------------------------------------------------------------------------------------------
    // File permissions and secret generation
    // ---------------------------------------------------------------------------------------------

    // Rolia - create the config file with owner-only (0600) permissions up front, so the secret is
    // never written into a briefly world-readable file. On non-POSIX filesystems (Windows) this falls
    // back to a best-effort tighten; restrictPermissions() also runs again after the write.
    private static void createPrivate(final File file) {
        if (file.exists()) {
            restrictPermissions(file);
            return;
        }
        try {
            final java.nio.file.Path p = file.toPath();
            if (java.nio.file.Files.getFileAttributeView(p, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                java.nio.file.Files.createFile(p, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
            }
        } catch (final Exception e) {
            LOGGER.warn("could not pre-create {} with restricted permissions - protect it manually!", FILE_NAME);
        }
    }

    private static void restrictPermissions(final File file) {
        try {
            final java.nio.file.Path p = file.toPath();
            final java.nio.file.attribute.PosixFileAttributeView view =
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
        } catch (final Exception e) {
            LOGGER.warn("could not restrict permissions on {} - protect it manually!", FILE_NAME);
        }
    }

    private static String generateSecureSalt(final int length) {
        final SecureRandom random = new SecureRandom();
        // charset intentionally excludes " and \ so the value is safe inside a YAML double-quoted string
        final String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?";
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }
}
