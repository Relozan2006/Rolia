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
        return enabled;
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
        enabled = Boolean.parseBoolean(props.getProperty(KEY_ENABLED, "true"));
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
        loaded = true;
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
