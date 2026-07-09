package io.rolia.secureseed;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Properties;

/**
 * Rolia - standalone configuration for the 1024-bit secure world seed system.
 * The secure seed is ALWAYS enabled and cannot be turned off; only the salt is configurable.
 * Stored in rolia-seed.properties in the server working directory.
 */
public final class SeedConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "rolia-seed.properties";
    private static final String KEY_ENABLED = "secure-seed.enabled";
    private static final String KEY_SALT = "secure-seed.salt";

    private static volatile boolean loaded;
    private static boolean enabled = true;
    private static String salt = "";

    private SeedConfig() {
    }

    public static boolean enabled() {
        load();
        return true; // Rolia - secure seed is always on and cannot be disabled
    }

    public static String salt() {
        load();
        return salt;
    }

    private static synchronized void load() {
        if (loaded) return;
        Properties props = new Properties();
        File file = new File(FILE_NAME);
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("Rolia: failed to read {}, using defaults", FILE_NAME, e);
            }
        }
        // Rolia - secure seed is always on; a user-set secure-seed.enabled=false is ignored
        if ("false".equalsIgnoreCase(props.getProperty(KEY_ENABLED, "true").trim())) {
            LOGGER.warn("Rolia: '{}=false' in {} is ignored - the secure seed is always enabled and cannot be disabled.", KEY_ENABLED, FILE_NAME);
        }
        enabled = true;
        String configSalt = props.getProperty(KEY_SALT, "");
        boolean dirty = !file.isFile();
        if (configSalt.isEmpty() || configSalt.length() < 64) {
            if (!configSalt.isEmpty()) {
                LOGGER.warn("Rolia: configured secure seed salt is too short (< 64 chars), regenerating.");
            } else {
                LOGGER.info("Rolia: no secure seed salt found. Generating a new cryptographically secure salt...");
            }
            salt = generateSecureSalt(64);
            dirty = true;
        } else {
            salt = configSalt;
        }
        if (dirty) {
            props.setProperty(KEY_ENABLED, String.valueOf(enabled));
            props.setProperty(KEY_SALT, salt);
            try (FileOutputStream out = new FileOutputStream(file)) {
                props.store(out, "Rolia secure seed settings. Keep the salt secret to prevent world copying! Back this file up!");
                LOGGER.info("Rolia: secure seed settings saved to {}", FILE_NAME);
                LOGGER.warn("Rolia: [IMPORTANT] Keep this salt secret to prevent world copying! Back up {}!", FILE_NAME);
            } catch (IOException e) {
                LOGGER.error("Rolia: failed to save {}", FILE_NAME, e);
            }
        }
        // Rolia - the salt is the master secret; keep the file owner-only at rest
        if (file.isFile()) {
            restrictPermissions(file);
        }
        loaded = true;
    }

    // Rolia start - restrict the salt file so other local users cannot read the secret
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
                // non-POSIX (e.g. Windows): best-effort owner-only
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
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_+-=[]{}|;:,.<>?";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }
}
