package io.rolia;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
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

    // secure-seed
    private static String salt = "";
    // dab
    private static boolean dabEnabled = false;
    private static int dabStartDistance = 12;
    private static int dabMaxTickInterval = 20;
    private static Set<String> dabBlacklist = Set.of();
    // villager lobotomization
    private static boolean lobotomizeEnabled = true;
    private static boolean lobotomizeWaitUntilTradeLocked = true;

    private RoliaConfig() {
    }

    public static String salt() { load(); return salt; }
    public static boolean dabEnabled() { load(); return dabEnabled; }
    public static int dabStartDistance() { load(); return dabStartDistance; }
    public static int dabMaxTickInterval() { load(); return dabMaxTickInterval; }
    public static boolean dabBlacklisted(String typeId) { load(); return dabBlacklist.contains(typeId); }
    public static boolean dabHasBlacklist() { load(); return !dabBlacklist.isEmpty(); }
    public static boolean lobotomizeEnabled() { load(); return lobotomizeEnabled; }
    public static boolean lobotomizeWaitUntilTradeLocked() { load(); return lobotomizeWaitUntilTradeLocked; }

    @SuppressWarnings("unchecked")
    private static synchronized void load() {
        if (loaded) return;
        File file = new File(FILE_NAME);
        Map<String, Object> root = null;
        String legacySalt = null;

        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                Object parsed = new Yaml().load(in);
                if (parsed instanceof Map) {
                    root = (Map<String, Object>) parsed;
                }
            } catch (Exception e) {
                LOGGER.warn("Rolia: failed to read {}, using defaults", FILE_NAME, e);
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
        if (cfgSalt.length() < 64) {
            if (!cfgSalt.isEmpty()) {
                LOGGER.warn("Rolia: configured secure seed salt is too short (< 64 chars), regenerating.");
            } else {
                LOGGER.info("Rolia: no secure seed salt found. Generating a new cryptographically secure salt...");
            }
            cfgSalt = generateSecureSalt(64);
            firstGen = true;
        }
        salt = cfgSalt;

        // dab
        dabEnabled = bool(dab.get("enabled"), false);
        dabStartDistance = clamp(intv(dab.get("start-distance"), 12), 1, 256);
        dabMaxTickInterval = clamp(intv(dab.get("max-tick-interval"), 20), 1, 200);
        dabBlacklist = strSet(dab.get("blacklist"));

        // villager lobotomization
        Map<String, Object> villagerLobo = section(optimizations, "villager-lobotomize");
        lobotomizeEnabled = bool(villagerLobo.get("enabled"), true);
        lobotomizeWaitUntilTradeLocked = bool(villagerLobo.get("wait-until-trade-locked"), true);

        // Only (re)write the file on first generation / migration - never clobber a user-edited file.
        if (firstGen) {
            createPrivate(file); // Rolia - create the file owner-only (0600) BEFORE the secret salt is written
            writeConfig(file);
            LOGGER.info("Rolia: settings saved to {}", FILE_NAME);
            LOGGER.warn("Rolia: [IMPORTANT] {} holds the secret salt. Keep it secret and back it up with your world!", FILE_NAME);
        }
        if (file.isFile()) {
            restrictPermissions(file);
        }
        loaded = true;
        LOGGER.info("Rolia: config loaded (secure seed active; DAB {}).", dabEnabled ? "ON" : "off");
    }

    private static void writeConfig(File file) {
        String yaml = ""
            + "# Rolia configuration - everything Rolia adds on top of Canvas lives here.\n"
            + "# Canvas keeps its own configs (canvas-server.yml, canvas-worlds.yml, paper-*.yml) - do not put Rolia options there.\n"
            + "# IMPORTANT: this file contains the secret salt. Keep it secret and BACK IT UP together with your world.\n"
            + "# If the salt is lost, newly generated structures/ores in an existing world will no longer match the old ones.\n"
            + "\n"
            + "secure-seed:\n"
            + "  # 64+ char secret salt, auto-generated on first run. The master key of the 1024-bit seed protection.\n"
            + "  salt: \"" + salt + "\"\n"
            + "\n"
            + "# Optimizations - Folia-safe, vanilla-preserving performance toggles.\n"
            + "# Everything here is OFF by default, so the server behaves exactly like vanilla Canvas until you opt in.\n"
            + "optimizations:\n"
            + "  # Dynamic Activation of Brain (DAB): throttles the AI of mobs far from ANY player\n"
            + "  # (villagers/piglins/zombies/skeletons etc.). Mobs near players always tick full AI every\n"
            + "  # tick, so there is no observable gameplay change - only a CPU saving on mob-heavy servers.\n"
            + "  dab:\n"
            + "    enabled: " + dabEnabled + "\n"
            + "    # Mobs closer than this many blocks to a player always tick every tick (full AI).\n"
            + "    start-distance: " + dabStartDistance + "\n"
            + "    # The farthest mobs tick their AI at most once per this many ticks.\n"
            + "    max-tick-interval: " + dabMaxTickInterval + "\n"
            + "    # Entity type ids never throttled (useful for mob farms), e.g. [\"minecraft:villager\"].\n"
            + "    blacklist: []\n"
            + "  # Lobotomize stuck villagers: a villager boxed in a 1x1 cell (a trading hall) cannot path\n"
            + "  # anywhere, so its expensive AI/pathfinding tick is skipped. It STILL restocks trades, so\n"
            + "  # trading halls behave exactly like vanilla - only wasted pathfinding is removed.\n"
            + "  villager-lobotomize:\n"
            + "    enabled: " + lobotomizeEnabled + "\n"
            + "    # Keep full AI for villagers that have not been traded with yet (0 xp) so they can still\n"
            + "    # gain their first profession level. Recommended true.\n"
            + "    wait-until-trade-locked: " + lobotomizeWaitUntilTradeLocked + "\n";

        try (FileWriter w = new FileWriter(file)) {
            w.write(yaml);
        } catch (Exception e) {
            LOGGER.error("Rolia: failed to save {}", FILE_NAME, e);
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
