package io.rolia;

import com.mojang.logging.LogUtils;
import io.rolia.config.ConfigWriter;
import io.rolia.config.Opt;
import io.rolia.config.Opt.BoolOpt;
import io.rolia.config.Opt.IntOpt;
import io.rolia.config.Opt.Reload;
import io.rolia.config.Opt.StringListOpt;
import io.rolia.config.Opt.StringOpt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Once, below, as an {@link Opt}. The parser, the generated YAML, {@code /rolia status} and the key
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
        "secure-seed.on-secret-mismatch", "warn", Set.of("warn", "block", "ignore"), Reload.RESTART,
        "What to do when this file's secret does not match the world on disk - because the file was",
        "lost and regenerated, or because secure-seed.enabled was flipped after the world existed.",
        "",
        "  warn   log a very loud warning and start anyway (default)",
        "  block  refuse to start, so nothing is generated until you have decided",
        "  ignore say nothing",
        "",
        "The consequence of continuing is not subtle: newly generated chunks get different caves, ores,",
        "biomes and structures from the ones already on disk, with a hard seam between them, and there",
        "is no way to repair it afterwards. 'block' is the safe choice for a production server; 'warn'",
        "is the default because refusing to boot is a harsh response to a config mistake.");

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

    // ---------------------------------------------------------------------------------------------
    // optimizations
    // ---------------------------------------------------------------------------------------------
    static {
        Opt.section("optimizations",
            "PERFORMANCE OPTIONS - all off by default.",
            "",
            "Every option here is off so that a stock Rolia server behaves exactly like a stock Canvas",
            "server. Turn on what you need. Each one states honestly whether it changes behaviour that",
            "players can observe, because most performance options do, and a server owner deserves to",
            "know which farm is about to stop working.",
            "",
            "Canvas has its own performance options in config/canvas-server.yml and",
            "config/canvas-worlds.yml, and Paper has its own in config/paper-global.yml and",
            "config/paper-world-defaults.yml. Rolia does not duplicate or override them - this file only",
            "contains options Rolia itself adds.",
            "",
            "BEFORE TUNING ANYTHING HERE, check config/paper-global.yml chunk-system. Paper ships",
            "worker-threads: -1 and io-threads: -1, and -1 does NOT mean 'use all cores': io-threads",
            "resolves to exactly one thread on every machine, and worker-threads resolves to one on any",
            "box with 7 or fewer cores. That single setting is worth more than everything below it.");
    }

    public static final BoolOpt DAB_ENABLED = new BoolOpt(
        "optimizations.dab.enabled", false, Reload.LIVE,
        "Dynamic Activation of Brain: mobs far from every player think LESS OFTEN. A mob's AI (brain",
        "sensors and behaviours, or the goal selector) runs once every N ticks instead of every tick,",
        "where N scales from 1 up to max-tick-interval with distance to the nearest player.",
        "",
        "CHANGES BEHAVIOUR. A throttled mob reacts late: it notices targets, changes path, flees and",
        "re-aims on a coarser clock, so distant mobs drift and converge differently from Vanilla.",
        "Movement, physics, damage, despawning and mob spawning are untouched, so mob-farm RATES are",
        "usually unaffected - but any farm that depends on precise distant pathing can change.",
        "",
        "This is the single biggest CPU saving available on a server with many mobs.");

    public static final IntOpt DAB_START_DISTANCE = new IntOpt(
        "optimizations.dab.start-distance", 12, 1, 256, Reload.LIVE,
        "Mobs closer than this many blocks to a player always run full AI every tick.");

    public static final IntOpt DAB_MAX_TICK_INTERVAL = new IntOpt(
        "optimizations.dab.max-tick-interval", 20, 1, 200, Reload.LIVE,
        "The farthest mobs run their AI at most once per this many ticks.");

    public static final StringListOpt DAB_BLACKLIST = new StringListOpt(
        "optimizations.dab.blacklist", Set.of(), Reload.LIVE,
        "Entity type ids that are never throttled, e.g. [\"minecraft:villager\", \"minecraft:piglin\"].",
        "Use this to exempt whatever a farm depends on instead of turning DAB off entirely.");

    public static final BoolOpt LOBOTOMIZE_ENABLED = new BoolOpt(
        "optimizations.villager-lobotomize.enabled", false, Reload.LIVE,
        "Skip the brain tick of villagers that are boxed in and cannot path anywhere. Trades and",
        "RESTOCKING still work, so a pure trading hall behaves normally.",
        "",
        "CHANGES BEHAVIOUR. Skipping the brain skips every sensor and behaviour: a lobotomized villager",
        "does NOT detect hostiles (it will not flee or scream when a zombie arrives), does NOT sleep,",
        "does NOT gossip, does NOT breed, and does NOT count towards IRON GOLEM spawning. Leave this",
        "off if you run villager-based iron farms, breeders, or anything relying on panic or gossip.");

    public static final BoolOpt LOBOTOMIZE_WAIT_UNTIL_TRADE_LOCKED = new BoolOpt(
        "optimizations.villager-lobotomize.wait-until-trade-locked", true, Reload.LIVE,
        "Keep full AI for villagers that have never been traded with (0 xp), so they can still gain",
        "their first profession level. Recommended; only consulted when enabled is true.");

    public static final IntOpt LOBOTOMIZE_CHECK_INTERVAL = new IntOpt(
        "optimizations.villager-lobotomize.check-interval", 100, 1, 1200, Reload.LIVE,
        "Ticks between re-checks of whether a villager is boxed in; the answer is cached in between,",
        "because the check costs up to 8 block and collision-shape lookups per villager. Lower means a",
        "villager freed from its cell wakes up sooner; higher is cheaper. 100 ticks = 5 seconds.");

    public static final BoolOpt FASTER_NETWORK = new BoolOpt(
        "optimizations.faster-network.enabled", false, Reload.LIVE,
        "Write long arrays (chunk light data and heightmaps) in one bulk copy instead of a loop.",
        "",
        "Behaviour-neutral: the bytes on the wire are byte-for-byte identical, this is purely faster",
        "serialization on the Netty threads. Safe to turn on.");

    public static final IntOpt AI_LINE_OF_SIGHT_INTERVAL = new IntOpt(
        "optimizations.ai.line-of-sight-interval", 1, 1, 40, Reload.LIVE,
        "Reuse a mob's line-of-sight results for this many ticks. 1 is Vanilla.",
        "",
        "Vanilla clears the seen/unseen cache every tick, so every mob re-raycasts every target every",
        "tick. Raycasting is one of the more expensive things a mob does, and it happens per mob per",
        "target, so this is likely the largest single saving available on a mob-dense server.",
        "",
        "CHANGES BEHAVIOUR at any value above 1: a mob notices a target appearing, or losing cover, up",
        "to this many ticks late. What that looks like in practice is skeletons and blazes firing a",
        "fraction of a second behind, and mobs re-acquiring targets slightly later after you break line",
        "of sight. 2 or 3 is a reasonable trade; 20 is a mob that reacts a second late.");

    public static final IntOpt AI_INACTIVE_GOAL_INTERVAL = new IntOpt(
        "optimizations.ai.inactive-goal-selector-interval", 3, 1, 40, Reload.LIVE,
        "Run the goal selector of INACTIVE mobs once per this many ticks. 3 is what Paper does today.",
        "",
        "Inactive means the mob is outside entity-activation range - Paper already ticks those mobs on",
        "a reduced schedule, and this only changes the divisor. Active mobs are untouched, and so is",
        "the target selector's own rate.",
        "",
        "Raising it makes far-away mobs pick new goals less often. They still move, still despawn and",
        "still count towards mob caps; what changes is how promptly one that is out of range starts",
        "wandering somewhere new. Anything relying on distant mobs re-pathing quickly may be affected.");

    public static final BoolOpt COLLISION_CACHE_SHAPE_COORDS = new BoolOpt(
        "optimizations.collision.cache-shape-coords", false, Reload.LIVE,
        "Cache each cube collision shape's coordinate lists instead of rebuilding them on every query.",
        "",
        "Behaviour-neutral: a cube shape's size never changes after construction, so the list is the",
        "same object every time - Vanilla just builds a new one on each call. Collision queries are hot",
        "on any server with a lot of moving entities.",
        "",
        "Costs one small array per distinct cube shape, which is a fixed and very small set.");

    // ---------------------------------------------------------------------------------------------
    // vanilla-parity
    // ---------------------------------------------------------------------------------------------
    static {
        Opt.section("vanilla-parity",
            "VANILLA PARITY - all off by default.",
            "",
            "Canvas deviates from Vanilla in the four places below. Rolia can put them back. They are",
            "off by default so that a stock Rolia server matches a stock Canvas server exactly; turn on",
            "whichever matters to you.",
            "",
            "These are not bug fixes in the sense of crashes - they are deliberate Canvas trade-offs",
            "that cost Vanilla behaviour. Rolia's actual bug fixes have no keys and are always applied.");
    }

    public static final BoolOpt PARITY_RANDOM_TICK = new BoolOpt(
        "vanilla-parity.random-tick-selection", false, Reload.RESTART,
        "Re-read the ticking-block list on every iteration of the random-tick loop, as Paper does.",
        "",
        "Canvas hoists the list size out of the loop. Paper re-reads it deliberately, because a random",
        "tick can add or remove randomly-ticking blocks in the same chunk section. With a stale count,",
        "blocks that no longer qualify get ticked and newly qualifying ones do not get selected.",
        "",
        "What this visibly affects: CROP GROWTH, grass and mycelium spread, and fire spread. If your",
        "players say farms grow slowly, this is the option.",
        "",
        "Costs a list-size read per iteration. That is the price Paper decided was worth paying.");

    public static final BoolOpt PARITY_MOB_SPAWN_PLACEMENT = new BoolOpt(
        "vanilla-parity.mob-spawn-placement", false, Reload.RESTART,
        "Restore Vanilla's mob spawn placement.",
        "",
        "Canvas replaced Vanilla's cumulative triangular walk (x += nextInt(6) - nextInt(6), repeated)",
        "with a single uniform draw around a fixed centre, and clamped the result into the chunk. Packs",
        "therefore cluster more tightly and pile up on chunk borders. This restores the Vanilla walk,",
        "and with it the isRightDistanceToPlayerAndSpawnPoint check that Canvas had to replace with an",
        "unconditional 'true' precisely because of the clamping. It also picks the genuinely nearest",
        "player rather than the one furthest from its own mob cap.",
        "",
        "Honest cost: positions may again fall outside the chunk, so block lookups go through the level",
        "rather than a masked read inside the chunk - exactly as on Paper and Folia.");

    public static final BoolOpt PARITY_ENDER_PEARL = new BoolOpt(
        "vanilla-parity.ender-pearl-persistence", false, Reload.RESTART,
        "Force Canvas's restoreVanillaEnderPearlBehavior on, so ender pearls in flight are saved with",
        "the player who threw them and survive a restart or a world unload, as in Vanilla.",
        "",
        "false does NOT force it off - it means Rolia does not touch the setting and Canvas's own value",
        "in config/canvas-server.yml decides. Set that directly if you want finer control.");

    public static final BoolOpt PARITY_PROJECTILE_DEFLECTION = new BoolOpt(
        "vanilla-parity.cross-region-projectile-deflection", false, Reload.RESTART,
        "Force Canvas's crossRegionRedirectableProjectileDeflection on, so deflecting a projectile (a",
        "wind charge knocking an arrow aside, for instance) works when the projectile crosses a region",
        "boundary. Region threading silently disables it otherwise.",
        "",
        "false means Rolia does not touch the setting; Canvas's own value decides.");

    // ---------------------------------------------------------------------------------------------
    // canvas-overrides
    // ---------------------------------------------------------------------------------------------
    static {
        Opt.section("canvas-overrides",
            "CANVAS DEFAULTS THAT ROLIA CAN CHANGE - all off by default.",
            "",
            "Builds 40-42 shipped several of Canvas's own options with different defaults, on the",
            "grounds that Canvas had chosen badly. That was Rolia quietly deciding for the operator, in",
            "a file the operator was not reading. Build 43 hands every one of them back: Canvas's",
            "defaults are Canvas's again, and each change Rolia used to make silently is a key here.",
            "",
            "HOW THESE WORK: setting one to true changes the DEFAULT of the corresponding option in",
            "config/canvas-server.yml or config/canvas-worlds.yml. If you have set that option",
            "explicitly in Canvas's own file, YOUR value still wins - this only moves the default. false",
            "means Rolia does not touch it at all.",
            "",
            "Each one records what Canvas does, what changes, and why anyone would want it.");
    }

    public static final BoolOpt CANVAS_GUARD_SEVERITY_LOG = new BoolOpt(
        "canvas-overrides.log-instead-of-throwing-on-guard-violation", false, Reload.RESTART,
        "Canvas adds extra tick-thread checks to catch plugins touching the wrong region, and ships",
        "guardSeverity: THROW. Canvas's own documentation for that option says it can crash the server.",
        "",
        "true switches the default to LOG: the violation is still reported, loudly and with a stack",
        "trace, but a badly written plugin degrades your server instead of stopping it. Recommended on",
        "a production server that runs third-party plugins; leave it off while developing them, where",
        "an exception at the point of failure is exactly what you want.");

    public static final BoolOpt CANVAS_TILE_ENTITY_SNAPSHOT = new BoolOpt(
        "canvas-overrides.tile-entity-snapshot-creation", false, Reload.RESTART,
        "Canvas ships tileEntitySnapshotCreation: false, which makes BlockState.getOwner() hand back a",
        "live-backed object rather than a snapshot.",
        "",
        "CraftBukkit's documented contract is that it returns a SNAPSHOT. With snapshots off, a plugin",
        "that reads a chest's inventory and modifies its copy is unknowingly modifying the real block",
        "entity. Turning this on costs an object copy per getOwner() call and restores the contract.",
        "Worth it if you run inventory-manipulating plugins and see phantom item changes.");

    public static final BoolOpt CANVAS_CACHE_ENTITY_TYPE_CONVERSION = new BoolOpt(
        "canvas-overrides.cache-entity-type-conversion", false, Reload.RESTART,
        "Memoize the Minecraft-to-Bukkit EntityType conversion, which is a pure function of its input",
        "and is called on every entity event. Behaviour-neutral by construction; the only cost is one",
        "small lookup table.");

    public static final BoolOpt CANVAS_FILTER_MOVE_PACKETS = new BoolOpt(
        "canvas-overrides.filter-zero-delta-move-packets", false, Reload.RESTART,
        "Drop movement packets that carry no movement at all - identical position, identical rotation.",
        "A stationary player sends about 20 of these a second.",
        "",
        "Distinct from Canvas's filterVelocityPacket, which Rolia leaves off because it changes",
        "client-side motion smoothing and players can feel it. This one drops packets that say nothing.");

    public static final BoolOpt CANVAS_ALT_PLAYERLIST_TICK = new BoolOpt(
        "canvas-overrides.alternative-player-list-tick", false, Reload.RESTART,
        "Spread the tab-list ping refresh across ticks instead of updating every player in one. Starts",
        "to matter somewhere above a hundred players; below that it is noise.");

    public static final BoolOpt CANVAS_SUFFOCATION_OPTIMIZATION = new BoolOpt(
        "canvas-overrides.suffocation-optimization", false, Reload.RESTART,
        "Check suffocation less often than every tick.",
        "",
        "CHANGES BEHAVIOUR, though less than it sounds: Vanilla already rate-limits suffocation damage",
        "through invulnerableTime, so the damage RATE is unchanged - it is phase-shifted by up to nine",
        "ticks. In practice a brief crush of under half a second that would have dealt one point of",
        "damage may deal none. Anything that actually traps a player still kills them on schedule.");

    public static final BoolOpt CANVAS_DISABLE_REGION_BARS = new BoolOpt(
        "canvas-overrides.disable-region-bars", false, Reload.RESTART,
        "Canvas ships its regionized TPS-bar and RAM-bar enabled, and they tick once a second per",
        "region whether or not any player has switched them on. true disables both by default.",
        "",
        "This is the one override that turns something OFF rather than on. Leave it false if you use",
        "the bars; turn it on if you have never heard of them, which is the common case.");

    // ---------------------------------------------------------------------------------------------
    // advisory
    // ---------------------------------------------------------------------------------------------
    static {
        Opt.section("advisory",
            "STARTUP ADVICE - log lines only, no behaviour attached.");
    }

    public static final BoolOpt ADVISORY_CHUNK_THREADS = new BoolOpt(
        "advisory.warn-chunk-system-threads", true, Reload.RESTART,
        "Warn once at startup when Paper's chunk-system thread pools are about to run on a single",
        "thread. Paper ships worker-threads: -1 and io-threads: -1 in config/paper-global.yml, and -1",
        "resolves to one I/O thread on EVERY machine and one worker thread on anything with 7 or fewer",
        "cores. Almost nobody discovers this, which is why the warning exists. Purely a log line.");

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

    public static boolean dabEnabled() { load(); return DAB_ENABLED.get0(); }
    public static int dabStartDistance() { load(); return DAB_START_DISTANCE.get0(); }
    public static int dabMaxTickInterval() { load(); return DAB_MAX_TICK_INTERVAL.get0(); }
    public static boolean dabHasBlacklist() { load(); return !DAB_BLACKLIST.get0().isEmpty(); }
    public static boolean lobotomizeEnabled() { load(); return LOBOTOMIZE_ENABLED.get0(); }
    public static boolean lobotomizeWaitUntilTradeLocked() { load(); return LOBOTOMIZE_WAIT_UNTIL_TRADE_LOCKED.get0(); }
    public static int lobotomizeCheckInterval() { load(); return LOBOTOMIZE_CHECK_INTERVAL.get0(); }
    public static boolean fasterNetwork() { load(); return FASTER_NETWORK.get0(); }
    public static int lineOfSightInterval() { load(); return AI_LINE_OF_SIGHT_INTERVAL.get0(); }
    public static int inactiveGoalSelectorInterval() { load(); return AI_INACTIVE_GOAL_INTERVAL.get0(); }
    public static boolean cacheShapeCoords() { load(); return COLLISION_CACHE_SHAPE_COORDS.get0(); }

    public static boolean parityRandomTick() { load(); return PARITY_RANDOM_TICK.get0(); }
    public static boolean parityMobSpawnPlacement() { load(); return PARITY_MOB_SPAWN_PLACEMENT.get0(); }
    public static boolean parityEnderPearl() { load(); return PARITY_ENDER_PEARL.get0(); }
    public static boolean parityProjectileDeflection() { load(); return PARITY_PROJECTILE_DEFLECTION.get0(); }

    // Rolia - these seven feed the DEFAULT of a Canvas option. They are read while Canvas's config
    // class is initialising, which is early but strictly after rolia.yml can be loaded (the loader
    // only touches the filesystem and SnakeYAML). Canvas's own file still overrides them.
    public static boolean canvasGuardSeverityLog() { load(); return CANVAS_GUARD_SEVERITY_LOG.get0(); }
    public static boolean canvasTileEntitySnapshot() { load(); return CANVAS_TILE_ENTITY_SNAPSHOT.get0(); }
    public static boolean canvasCacheEntityTypeConversion() { load(); return CANVAS_CACHE_ENTITY_TYPE_CONVERSION.get0(); }
    public static boolean canvasFilterMovePackets() { load(); return CANVAS_FILTER_MOVE_PACKETS.get0(); }
    public static boolean canvasAltPlayerListTick() { load(); return CANVAS_ALT_PLAYERLIST_TICK.get0(); }
    public static boolean canvasSuffocationOptimization() { load(); return CANVAS_SUFFOCATION_OPTIMIZATION.get0(); }
    public static boolean canvasDisableRegionBars() { load(); return CANVAS_DISABLE_REGION_BARS.get0(); }

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
    // DAB blacklist resolution
    // ---------------------------------------------------------------------------------------------

    // Rolia - the blacklist is CONFIGURED as entity-type ids but TESTED per mob per tick, so it is
    // resolved once into the EntityType objects themselves and then tested with a plain set lookup
    // (zero allocation). Resolution must be LAZY: this config is loaded very early, long before
    // BuiltInRegistries is populated and frozen, so resolving during load would silently produce an
    // empty set. volatile: written once by whichever region thread resolves it first.
    private static volatile Set<EntityType<?>> dabBlacklistTypes;

    /** Rolia - is this entity type excluded from DAB throttling? Allocation-free. */
    public static boolean dabBlacklisted(final EntityType<?> type) {
        load();
        Set<EntityType<?>> types = dabBlacklistTypes;
        if (types == null) {
            types = resolveDabBlacklist();
        }
        return types.contains(type);
    }

    private static synchronized Set<EntityType<?>> resolveDabBlacklist() {
        Set<EntityType<?>> types = dabBlacklistTypes;
        if (types != null) {
            return types; // another thread already resolved it
        }
        final Set<EntityType<?>> resolved = new HashSet<>();
        for (final String id : DAB_BLACKLIST.get0()) {
            if (id.isEmpty()) {
                continue;
            }
            EntityType<?> match = null;
            try {
                // Identifier.parse supplies the default namespace, so both "minecraft:villager" and
                // "villager" resolve.
                final Identifier key = Identifier.parse(id);
                final EntityType<?> candidate = BuiltInRegistries.ENTITY_TYPE.getValue(key);
                // ENTITY_TYPE is a DEFAULTED registry: an unknown id silently returns the default type
                // (minecraft:pig) rather than null, so verify the round-trip instead of trusting the
                // lookup - otherwise a single typo would quietly exempt every pig on the server.
                final var resolvedKey = candidate == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(candidate);
                if (key.equals(resolvedKey)) {
                    match = candidate;
                }
            } catch (final Exception ignored) {
                // malformed id - reported below
            }
            if (match == null) {
                LOGGER.warn("unknown entity type '{}' in {} -> optimizations.dab.blacklist; ignoring it.", id, FILE_NAME);
            } else {
                resolved.add(match);
            }
        }
        types = Set.copyOf(resolved);
        dabBlacklistTypes = types;
        return types;
    }

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(final File file) {
        if (!file.isFile()) {
            return null;
        }
        try (final InputStream in = new FileInputStream(file)) {
            final Object parsed = new Yaml().load(in);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (final Exception e) {
            LOGGER.error("{} exists but could not be parsed.", file, e);
            LOGGER.error("Refusing to start. Fix the YAML (or restore it from backup) and retry.");
            LOGGER.error("Continuing would generate a NEW secret and overwrite this file, which");
            LOGGER.error("re-generates caves, ores, biomes and structures for every newly loaded chunk");
            LOGGER.error("and destroys the only copy of the old secret.");
            throw new IllegalStateException("Rolia: unreadable " + FILE_NAME + " - refusing to start", e);
        }
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
            createPrivate(file); // owner-only (0600) BEFORE the secret is written into it
            writeConfig(file);
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
            final String legacy = readLegacySalt(file);
            if (legacy != null) {
                cfgSalt = legacy;
            }
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

        long[] cfgSeed = parseFeatureSeed(root == null ? null : Opt.resolve(root, SECURE_SEED_FEATURE_SEED.path));
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

    /** Rolia - import the secret from the pre-build-40 rolia-seed.properties so an existing world survives. */
    private static String readLegacySalt(final File configFile) {
        final File legacy = new File(LEGACY_FILE);
        if (!legacy.isFile()) {
            return null;
        }
        final Properties props = new Properties();
        try (final InputStream in = new FileInputStream(legacy)) {
            props.load(in);
        } catch (final Exception ignored) {
            return null;
        }
        final String s = props.getProperty("secure-seed.salt", "");
        if (s != null && s.length() >= 64) {
            LOGGER.info("migrating secure seed salt from {} to {}", LEGACY_FILE, FILE_NAME);
            return s;
        }
        return null;
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

    // ---------------------------------------------------------------------------------------------
    // Reload (/rolia reload)
    // ---------------------------------------------------------------------------------------------

    /** Rolia - the outcome of a reload, for the command to render. */
    public record ReloadResult(List<String> applied, List<String> needsRestart, String error) {
    }

    /**
     * Rolia - re-read the file and apply only what can safely change at runtime.
     *
     * <p>RESTART options are compared but not assigned. Telling an operator "reloaded" and then
     * quietly not applying half of it is worse than telling them a restart is needed, so the command
     * lists exactly which keys were skipped.</p>
     *
     * <p>The secret is never re-read: changing it mid-run would mean chunks generated after the reload
     * disagree with chunks generated before it, in the same session, with no warning.</p>
     */
    public static synchronized ReloadResult reload() {
        final File file = new File(FILE_NAME).getAbsoluteFile();
        final Map<String, Object> root;
        try {
            root = parse(file);
        } catch (final RuntimeException e) {
            return new ReloadResult(List.of(), List.of(), e.getMessage());
        }
        final List<String> applied = new ArrayList<>();
        final List<String> needsRestart = new ArrayList<>();
        for (final Opt<?> opt : Opt.options()) {
            if (opt.isSecret()) {
                continue;
            }
            final Object raw = Opt.resolve(root, opt.path);
            if (opt.reload == Reload.RESTART) {
                if (opt.apply(raw, true)) { // dry run: would it change?
                    needsRestart.add(opt.path);
                }
                continue;
            }
            if (opt.apply(raw, false)) {
                applied.add(opt.path + " = " + opt.yamlValue());
            }
        }
        // The blacklist is resolved into EntityType objects and cached; drop the cache so the next mob
        // tick rebuilds it from whatever was just loaded.
        dabBlacklistTypes = null;
        return new ReloadResult(applied, needsRestart, null);
    }

    /** Rolia - every option with its current value, for /rolia status. Secrets are never included. */
    public static List<Opt<?>> allOptions() {
        load();
        return Opt.options();
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

    private static void writeConfig(final File file) {
        final String yaml = ConfigWriter.render(HEADER);
        // Write atomically via a temp file, and never replace an existing config without first copying
        // it aside. A half-written or clobbered rolia.yml means a lost secret.
        try {
            final java.nio.file.Path target = file.toPath();
            if (java.nio.file.Files.exists(target)) {
                final java.nio.file.Path backup = target.resolveSibling(FILE_NAME + ".bak." + System.currentTimeMillis());
                try {
                    java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                    LOGGER.warn("previous {} backed up to {}", FILE_NAME, backup.getFileName());
                } catch (final Exception e) {
                    LOGGER.warn("could not back up the existing {} before rewriting it", FILE_NAME, e);
                }
            }
            final java.nio.file.Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
            java.nio.file.Files.deleteIfExists(tmp);
            createPrivate(tmp.toFile()); // owner-only before the secret is written into it
            java.nio.file.Files.writeString(tmp, yaml, java.nio.charset.StandardCharsets.UTF_8);
            restrictPermissions(tmp.toFile());
            try {
                java.nio.file.Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (final java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final Exception e) {
            LOGGER.error("failed to save {}", file, e);
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
     * <p>What happens on a mismatch is {@code secure-seed.on-secret-mismatch}. The default is to warn
     * very loudly and continue; set it to {@code block} on a production server.</p>
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
    // Chunk-system thread pool advisory
    //
    // Paper's config/paper-global.yml ships chunk-system.worker-threads: -1 and io-threads: -1, and -1
    // means "auto". Both autos are traps:
    //
    //   io-threads     Moonrise resolves it as `Math.max(1, configIoThreads)` (see
    //                  MoonriseCommon#adjustWorkerThreads). -1 therefore resolves to exactly ONE I/O
    //                  thread on every machine, no matter how many cores it has. All region file reads
    //                  and writes for every world funnel through that single thread.
    //
    //   worker-threads Moonrise's auto ladder is roughly `d = cores / 2; d = d <= 4 ? (d <= 3 ? 1 : 2)
    //                  : d / 2`, so anything with 7 or fewer cores gets exactly ONE chunk-generation
    //                  worker, and 8-9 cores get two.
    //
    // Almost nobody discovers this, so say it out loud, once, at startup.
    // ---------------------------------------------------------------------------------------------
    private static final java.util.concurrent.atomic.AtomicBoolean THREAD_ADVISORY_DONE =
        new java.util.concurrent.atomic.AtomicBoolean();

    /** Rolia - recommended chunk-system worker threads, leaving room for Folia's region threads. */
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
     * Returns null when the values cannot be reached, and the caller advises unconditionally.</p>
     */
    private static int[] readConfiguredChunkSystemThreads() {
        try {
            final Class<?> cfgClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            final Object cfg = cfgClass.getMethod("get").invoke(null);
            if (cfg == null) {
                return null;
            }
            final Object chunkSystem = cfgClass.getField("chunkSystem").get(cfg);
            if (chunkSystem == null) {
                return null;
            }
            final Class<?> csClass = chunkSystem.getClass();
            return new int[] {
                csClass.getField("workerThreads").getInt(chunkSystem),
                csClass.getField("ioThreads").getInt(chunkSystem)
            };
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * Rolia - warn once, loudly, when the chunk system is about to run on one worker and/or one I/O
     * thread on a machine that clearly has cores to spare. Safe to call from anywhere; the first caller
     * wins and every later call is a single volatile read.
     */
    public static void warnIfChunkSystemThreadsUnderconfigured() {
        if (!THREAD_ADVISORY_DONE.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!ADVISORY_CHUNK_THREADS.get0()) {
                return;
            }
            final int cores = Runtime.getRuntime().availableProcessors();
            if (cores < 4) {
                return; // nothing useful to recommend on a 1-3 core box
            }

            final int recWorker = recommendedWorkerThreads(cores);
            final int recIo = recommendedIoThreads(cores);

            final int[] configured = readConfiguredChunkSystemThreads();
            final String resolvedNote;
            if (configured == null) {
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

            LOGGER.warn("############################################################");
            LOGGER.warn("CHUNK SYSTEM THREADS - this machine reports {} available processors.", cores);
            if (resolvedNote != null) {
                LOGGER.warn("{}.", resolvedNote);
                LOGGER.warn("At least one of those pools is running on a SINGLE thread.");
            } else {
                LOGGER.warn("Could not read the configured values, so check them by hand.");
            }
            LOGGER.warn("Paper ships both of these as -1, and -1 does NOT mean 'use all cores':");
            LOGGER.warn("  io-threads     -1 resolves to max(1, -1) = 1 thread on EVERY machine.");
            LOGGER.warn("  worker-threads -1 resolves to 1 thread on any box with 7 or fewer cores.");
            LOGGER.warn("One I/O thread means every region-file read and write for every world queues");
            LOGGER.warn("behind one thread, which shows up as chunk-load stalls, not as MSPT.");
            LOGGER.warn("Set these in config/paper-global.yml and restart:");
            LOGGER.warn("    chunk-system:");
            LOGGER.warn("      worker-threads: {}", recWorker);
            LOGGER.warn("      io-threads: {}", recIo);
            LOGGER.warn("(sized for {} cores, deliberately leaving cores free for Folia's region threads)", cores);
            LOGGER.warn("############################################################");
        } catch (final Throwable t) {
            LOGGER.warn("could not run the chunk-system thread advisory", t);
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
