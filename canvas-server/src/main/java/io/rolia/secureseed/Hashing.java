package io.rolia.secureseed;

/**
 * Rolia - cryptographic hashing for the secure world seed.
 * The compression function is real BLAKE2b (RFC 7693): SHA-512 IV, 12 rounds, the standard sigma
 * message schedule and G mixing. Replaces the previous homemade primitive. Inputs of up to 16 longs
 * (one 128-byte BLAKE2b block) are hashed to 8 output longs (512 bits).
 */
public class Hashing {
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

    private static volatile long[] cachedSaltHash = null;
    private static volatile String lastSalt = null;

    private static long[] getSaltHash() {
        String currentSalt = Globals.getSecureSeedSalt();
        if (cachedSaltHash == null || !currentSalt.equals(lastSalt)) {
            long[] saltLongs = new long[8];
            byte[] saltBytes = currentSalt.getBytes();

            for (int i = 0; i < Math.min(saltBytes.length, 64); i++) {
                int longIndex = i / 8;
                int byteIndex = i % 8;
                saltLongs[longIndex] |= ((long) (saltBytes[i] & 0xFF)) << (byteIndex * 8);
            }

            cachedSaltHash = hashWorldSeedInternal(saltLongs);
            lastSalt = currentSalt;
        }
        return cachedSaltHash;
    }

    // BLAKE2b of up to 16 input longs (one 128-byte block), returning the first 8 output longs.
    private static long[] hashWorldSeedInternal(long[] input) {
        long[] h = BLAKE2B_IV.clone();
        h[0] ^= 0x01010040L; // parameter block: digest length 64, key length 0
        int n = Math.min(input.length, 16);
        long[] m = new long[16];
        System.arraycopy(input, 0, m, 0, n);
        compress(h, m, (long) n * 8L, true);
        long[] out = new long[8];
        System.arraycopy(h, 0, out, 0, 8);
        return out;
    }

    private static void compress(long[] h, long[] m, long t, boolean last) {
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(BLAKE2B_IV, 0, v, 8, 8);
        v[12] ^= t;
        // v[13] ^= (t >>> 64) is always 0 for our <= 128-byte inputs
        if (last) {
            v[14] ^= 0xFFFFFFFFFFFFFFFFL;
        }
        for (int r = 0; r < 12; r++) {
            byte[] s = SIGMA[r];
            G(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            G(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            G(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            G(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            G(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            G(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            G(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            G(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void G(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    // Rolia - secure seed is always on; the previous non-secure passthrough/weak-expansion
    // fallbacks were dead code and have been removed so a weak path can never be revived.
    public static long[] hashWorldSeed(long[] worldSeed) {
        long[] saltHashValue = getSaltHash();
        long[] saltedSeed = new long[worldSeed.length];

        for (int i = 0; i < worldSeed.length; i++) {
            saltedSeed[i] = worldSeed[i] ^ saltHashValue[i % saltHashValue.length];
        }

        return hashWorldSeedInternal(saltedSeed);
    }

    public static long[] expandLevelSeedTo1024Bits(long levelSeed) {
        long[] result = new long[Globals.WORLD_SEED_LONGS];
        long[] saltHashValue = getSaltHash();

        for (int segment = 0; segment < Globals.WORLD_SEED_LONGS; segment++) {
            long[] segmentInput = new long[8];
            segmentInput[0] = levelSeed ^ saltHashValue[segment % saltHashValue.length];
            segmentInput[1] = (0x243F6A8885A308D3L + segment) ^ saltHashValue[(segment + 1) % saltHashValue.length];
            segmentInput[2] = (0x13198A2E03707344L + segment) ^ saltHashValue[(segment + 2) % saltHashValue.length];
            segmentInput[3] = (0xA4093822299F31D0L + segment) ^ saltHashValue[(segment + 3) % saltHashValue.length];
            segmentInput[4] = (segment * 0x9E3779B97F4A7C15L) ^ saltHashValue[(segment + 4) % saltHashValue.length];
            segmentInput[5] = (~segment) ^ saltHashValue[(segment + 5) % saltHashValue.length];
            segmentInput[6] = Long.rotateLeft(levelSeed, segment % 64) ^ saltHashValue[(segment + 6) % saltHashValue.length];
            segmentInput[7] = (levelSeed ^ ((long) segment << 32)) ^ saltHashValue[(segment + 7) % saltHashValue.length];

            long[] segmentHash = hashWorldSeedInternal(segmentInput);

            result[segment] = segmentHash[0];

            if (segment > 0) {
                segmentInput[0] = result[segment - 1];
                segmentInput[1] = segmentHash[1];
                segmentHash = hashWorldSeedInternal(segmentInput);
                result[segment] ^= segmentHash[0];
            }
        }
        return hashWorldSeedInternal(result);
    }

    public static void hash(long[] message, long[] output, long[] state, int outputBytes, boolean finalBlock) {
        long[] result = hashWorldSeedInternal(message);
        System.arraycopy(result, 0, output, 0, Math.min(result.length, output.length));
    }
}
