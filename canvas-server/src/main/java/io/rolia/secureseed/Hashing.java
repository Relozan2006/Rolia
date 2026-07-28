package io.rolia.secureseed;

import java.nio.charset.StandardCharsets;

/**
 * Rolia - cryptographic hashing for the secure world seed.
 *
 * <p>The primitive is BLAKE2b (RFC 7693): SHA-512 IV, 12 rounds, the standard sigma message schedule
 * and G mixing, verified against the official test vectors by a CI step on every build.</p>
 *
 * <p><b>Build 40: keyed mode.</b> Everything now goes through {@link #mac}, which is RFC 7693 keyed
 * BLAKE2b - a real MAC. Before build 40 the construction was a homemade {@code H(K xor M)}. That was
 * not broken (a single block plus the finalization flag rules out length extension) but it is
 * related-key malleable: someone who learns two derived values knows the exact XOR difference of the
 * two inputs without knowing the key. Keyed mode removes that class of concern for free - the
 * parameter block already carried a "key length" field that was simply being set to 0.</p>
 *
 * <p>Why BLAKE2b and not BLAKE3: BLAKE3's speed comes from SIMD and a Merkle tree over 1 KiB chunks.
 * Our inputs are a single 128-byte block, so neither applies, and there is no SIMD in plain Java.
 * BLAKE3 also targets 128-bit security regardless of output length, while BLAKE2b-512 targets 256-bit
 * - and this key derivation produces 512 bits of key material. So BLAKE2b is both faster here and the
 * stronger choice, and it is the same function the reference SecureSeed implementation uses.</p>
 */
public final class Hashing {
    private static final long[] BLAKE2B_IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    };

    private static final byte[][] SIGMA = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
        {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
        {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
        {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
        {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
        {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
        {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
        {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
        {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}
    };

    private static final int BLOCK_BYTES = 128;
    private static final int OUT_LONGS = 8;   // 512-bit digest
    private static final int KEY_BYTES = 64;

    private Hashing() {
    }

    // ---------------------------------------------------------------------------------------------
    // RFC 7693 core
    // ---------------------------------------------------------------------------------------------

    private static void compress(final long[] h, final long[] m, final long t, final boolean last) {
        final long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(BLAKE2B_IV, 0, v, 8, 8);
        v[12] ^= t;
        // v[13] ^= (t >>> 64) is always 0 for our input sizes
        if (last) {
            v[14] ^= 0xFFFFFFFFFFFFFFFFL;
        }
        for (int r = 0; r < 12; r++) {
            final byte[] s = SIGMA[r];
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void g(final long[] v, final int a, final int b, final int c, final int d, final long x, final long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    /**
     * Rolia - RFC 7693 keyed BLAKE2b-512 over an arbitrary byte message.
     *
     * @param key     0..64 bytes; a zero-length key selects unkeyed mode, exactly as the RFC specifies
     * @param message the message, possibly empty
     * @return the 64-byte digest as 8 little-endian longs
     */
    public static long[] mac(final byte[] key, final byte[] message) {
        final int keyLen = key == null ? 0 : Math.min(key.length, KEY_BYTES);
        final int msgLen = message == null ? 0 : message.length;

        final long[] h = BLAKE2B_IV.clone();
        // parameter block: digest length | key length << 8 | fanout 1 << 16 | depth 1 << 24
        h[0] ^= 0x01010000L | ((long) keyLen << 8) | (OUT_LONGS * 8L);

        // RFC 7693 section 2.9: a keyed hash processes the key, zero-padded to one full block, first.
        final int msgBlocks = msgLen == 0 ? 0 : (msgLen + BLOCK_BYTES - 1) / BLOCK_BYTES;
        final int keyBlocks = keyLen > 0 ? 1 : 0;
        final int totalBlocks = Math.max(keyBlocks + msgBlocks, 1);

        final long[] m = new long[16];
        long counted = 0L;
        int block = 0;

        if (keyLen > 0) {
            java.util.Arrays.fill(m, 0L);
            packLittleEndian(key, 0, keyLen, m);
            counted = BLOCK_BYTES; // the key block always counts as a full block
            block++;
            compress(h, m, counted, block == totalBlocks);
        }

        for (int off = 0; off < msgLen; off += BLOCK_BYTES) {
            final int len = Math.min(BLOCK_BYTES, msgLen - off);
            java.util.Arrays.fill(m, 0L);
            packLittleEndian(message, off, len, m);
            counted += len;
            block++;
            compress(h, m, counted, block == totalBlocks);
        }

        if (keyLen == 0 && msgLen == 0) {
            java.util.Arrays.fill(m, 0L);
            compress(h, m, 0L, true);
        }

        final long[] out = new long[OUT_LONGS];
        System.arraycopy(h, 0, out, 0, OUT_LONGS);
        return out;
    }

    private static void packLittleEndian(final byte[] src, final int off, final int len, final long[] dest) {
        for (int i = 0; i < len; i++) {
            dest[i >>> 3] |= ((long) (src[off + i] & 0xFF)) << ((i & 7) * 8);
        }
    }

    private static byte[] toLittleEndianBytes(final long[] words) {
        final byte[] out = new byte[words.length * 8];
        for (int i = 0; i < words.length; i++) {
            final long w = words[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) (w >>> (b * 8));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Domain separators. Each derived value comes from its own domain, so publishing one (the startup
    // fingerprint, for instance) can never expose another. Baked into every generated chunk - DO NOT EDIT.
    // ---------------------------------------------------------------------------------------------

    private static final byte[] DOMAIN_SALT_KEY    = "rolia:salt-key:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN_WORLD_SEED  = "rolia:world-seed:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN_FINGERPRINT = "rolia:fingerprint:v1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN_DERIVE      = "rolia:derive:v1".getBytes(StandardCharsets.US_ASCII);

    // Rolia - the salt is immutable for the server lifetime (loaded once from rolia.yml), so derive the
    // MAC key from it exactly once behind a volatile double-checked lock.
    private static volatile byte[] saltKey;

    /**
     * Rolia - the 64-byte MAC key derived from the secret salt.
     *
     * <p>The salt is hashed rather than byte-packed, so the WHOLE salt contributes however long it is.
     * Before build 40 only the first 64 bytes were used, which silently made any two salts sharing a
     * 64-character prefix the same key. UTF-8 is explicit here: the old {@code String.getBytes()} used
     * the platform default charset, so a non-ASCII salt re-keyed the entire world if
     * {@code -Dfile.encoding} changed between restarts.</p>
     */
    private static byte[] getSaltKey() {
        final byte[] cached = saltKey;
        if (cached != null) {
            return cached;
        }
        synchronized (Hashing.class) {
            if (saltKey == null) {
                final byte[] saltBytes = Globals.getSecureSeedSalt().getBytes(StandardCharsets.UTF_8);
                saltKey = toLittleEndianBytes(mac(DOMAIN_SALT_KEY, saltBytes));
            }
            return saltKey;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Derivation API
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - the 512-bit key that drives the worldgen RNG stream.
     * Never print this or anything derived from it in the same domain; see {@link #fingerprint}.
     */
    public static long[] hashWorldSeed(final long[] worldSeed) {
        return mac(getSaltKey(), concat(DOMAIN_WORLD_SEED, toLittleEndianBytes(worldSeed)));
    }

    /**
     * Rolia - a publishable 64-bit fingerprint of the active secret.
     *
     * <p>Domain-separated from {@link #hashWorldSeed} deliberately. Until build 40 the printed
     * fingerprint was literally word 0 of the live RNG key, which handed out 64 key bits and gave an
     * attacker a one-hash offline oracle for brute-forcing the salt. The two derivations now share no
     * output, so publishing this reveals nothing usable about either the seed or the salt.</p>
     */
    public static long fingerprint(final long[] worldSeed) {
        return mac(getSaltKey(), concat(DOMAIN_FINGERPRINT, toLittleEndianBytes(worldSeed)))[0];
    }

    /**
     * Rolia - derive an independent 512-bit sub-key for a named domain (terrain, carvers, climate...).
     *
     * <p>Replaces {@code expandLevelSeedTo1024Bits}, which chained 32 BLAKE2b compressions over a fixed
     * public constant schedule. That chain added exactly zero entropy - the result was always a
     * deterministic function of (levelSeed, salt) - while costing 32x a single MAC, and it returned 512
     * bits despite the name promising 1024.</p>
     */
    public static long[] derive(final String domain, final long[] worldSeed, final long extra) {
        final byte[] domainBytes = domain.getBytes(StandardCharsets.US_ASCII);
        final byte[] seedBytes = toLittleEndianBytes(worldSeed);
        final byte[] extraBytes = toLittleEndianBytes(new long[]{extra});
        final byte[] message = new byte[DOMAIN_DERIVE.length + domainBytes.length + seedBytes.length + extraBytes.length];
        int p = 0;
        System.arraycopy(DOMAIN_DERIVE, 0, message, p, DOMAIN_DERIVE.length);
        p += DOMAIN_DERIVE.length;
        System.arraycopy(domainBytes, 0, message, p, domainBytes.length);
        p += domainBytes.length;
        System.arraycopy(seedBytes, 0, message, p, seedBytes.length);
        p += seedBytes.length;
        System.arraycopy(extraBytes, 0, message, p, extraBytes.length);
        return mac(getSaltKey(), message);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // Rolia - the compression state AFTER the key block. In keyed BLAKE2b the key block is compressed
    // first and depends only on the key, which is fixed for the server's lifetime - so it can be
    // computed once instead of on every call.
    private static volatile long[] keyedInitState;

    private static long[] keyedInitState() {
        final long[] cached = keyedInitState;
        if (cached != null) {
            return cached;
        }
        synchronized (Hashing.class) {
            if (keyedInitState == null) {
                final byte[] key = getSaltKey();
                final long[] h = BLAKE2B_IV.clone();
                h[0] ^= 0x01010000L | ((long) KEY_BYTES << 8) | (OUT_LONGS * 8L);
                final long[] m = new long[16];
                packLittleEndian(key, 0, KEY_BYTES, m);
                compress(h, m, BLOCK_BYTES, false);
                keyedInitState = h;
            }
            return keyedInitState;
        }
    }

    /**
     * Rolia - MAC exactly one 128-byte block, supplied as 16 little-endian longs.
     *
     * <p>Called once per reseed by {@code WorldgenCryptoRandom#setSecureSeed} - build 41 moved the
     * per-draw work onto Xoroshiro, so this is no longer invoked per 512 bits. Byte-for-byte identical to
     * {@code mac(getSaltKey(), toLittleEndianBytes(message))} - the key block is simply resumed from
     * {@link #keyedInitState()} instead of being recompressed. Build 40.0 went through the general
     * path, which meant TWO compressions plus three array allocations per call where build 39 needed
     * one and none; that showed up directly as chunk-generation throughput.</p>
     */
    public static void hash(final long[] message, final long[] output) {
        final long[] h = keyedInitState().clone();
        final long[] m = new long[16];
        System.arraycopy(message, 0, m, 0, Math.min(message.length, 16));
        // t counts the key block (128) plus this message block (128)
        compress(h, m, BLOCK_BYTES * 2L, true);
        System.arraycopy(h, 0, output, 0, Math.min(OUT_LONGS, output.length));
    }

    // ---------------------------------------------------------------------------------------------
    // Self-test
    // ---------------------------------------------------------------------------------------------

    /**
     * Rolia - RFC 7693 keyed BLAKE2b-512 of the message "abc" under the key 00 01 02 ... 3f.
     *
     * <p>Logged once at startup and asserted by CI against {@code hashlib.blake2b}. This is what turns
     * "the crypto is correct" into a checked fact rather than a claim: if the keyed construction ever
     * regresses to unkeyed, or to a {@code key || message} prefix-MAC, the digest changes and the build
     * goes red.</p>
     */
    public static String selfTestHex() {
        final byte[] key = new byte[64];
        for (int i = 0; i < 64; i++) {
            key[i] = (byte) i;
        }
        final byte[] bytes = toLittleEndianBytes(mac(key, "abc".getBytes(StandardCharsets.US_ASCII)));
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
