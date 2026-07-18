package io.rolia.secureseed;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public class WorldgenCryptoRandom extends WorldgenRandom {
    private static final long[] HASHED_ZERO_SEED = Hashing.hashWorldSeed(new long[Globals.WORLD_SEED_LONGS]);
    private static final ThreadLocal<long[]> LAST_SEEN_WORLD_SEED = ThreadLocal.withInitial(() -> new long[Globals.WORLD_SEED_LONGS]);
    private static final ThreadLocal<long[]> HASHED_WORLD_SEED = ThreadLocal.withInitial(() -> HASHED_ZERO_SEED);

    private static final int MAX_RANDOM_BIT_INDEX = 64 * 8;
    private static final int LOG2_MAX_RANDOM_BIT_INDEX = 9;

    private final long[] worldSeed = new long[Globals.WORLD_SEED_LONGS];
    private final long[] randomBits = new long[8];
    private final long[] message = new long[16];
    private final long[] keyedMessage = new long[16]; // Rolia - reused keying buffer
    private int randomBitIndex;
    private long counter;

    public WorldgenCryptoRandom(int x, int z, Globals.Salt typeSalt, long salt) {
        super(new LegacyRandomSource(0L));

        if (typeSalt == null) {
            return;
        }

        this.setSecureSeed(x, z, typeSalt, salt);
    }

    public static RandomSource seedSlimeChunk(int chunkX, int chunkZ) {
        return new WorldgenCryptoRandom(chunkX, chunkZ, Globals.Salt.SLIME_CHUNK, 0);
    }

    public void setSecureSeed(int x, int z, Globals.Salt typeSalt, long salt) {
        System.arraycopy(Globals.worldSeed, 0, this.worldSeed, 0, Globals.WORLD_SEED_LONGS);
        message[0] = ((long) x << 32) | ((long) z & 0xffffffffL);
        message[1] = ((long) Globals.dimension.get() << 32) | ((long) salt & 0xffffffffL);
        message[2] = typeSalt.ordinal();
        message[3] = counter = 0;
        randomBitIndex = MAX_RANDOM_BIT_INDEX;
    }

    private long[] getHashedWorldSeed() {
        if (!Arrays.equals(worldSeed, LAST_SEEN_WORLD_SEED.get())) {
            HASHED_WORLD_SEED.set(Hashing.hashWorldSeed(worldSeed));
            System.arraycopy(worldSeed, 0, LAST_SEEN_WORLD_SEED.get(), 0, Globals.WORLD_SEED_LONGS);
        }
        return HASHED_WORLD_SEED.get();
    }

    private void moreRandomBits() {
        message[3] = counter++;
        // Rolia start - Feature secure seed FIX: actually key the random stream on the hashed
        // world seed (+ secret salt). Previously getHashedWorldSeed() was copied into randomBits
        // and then immediately overwritten by hash(message), so worldgen placement was independent
        // of both the world seed and the salt. Fold the hashed world seed into the message so the
        // output depends on the secret seed.
        final long[] hashedWorldSeed = getHashedWorldSeed();
        for (int i = 0; i < 8; i++) {
            keyedMessage[i] = message[i] ^ hashedWorldSeed[i % hashedWorldSeed.length];
        }
        Hashing.hash(keyedMessage, randomBits);
        // Rolia end
    }

    // Rolia - Java shift counts are taken mod 64, so (1L << 64) - 1 == 0. Guard the full-width
    // mask so getBits(64) / nextLong() are not silently zeroed.
    private static long lowMask(int bits) {
        return bits >= 64 ? -1L : (1L << bits) - 1L;
    }

    private long getBits(int count) {
        if (randomBitIndex >= MAX_RANDOM_BIT_INDEX) {
            moreRandomBits();
            randomBitIndex -= MAX_RANDOM_BIT_INDEX;
        }

        int alignment = randomBitIndex & 63;
        if ((randomBitIndex >>> 6) == ((randomBitIndex + count) >>> 6)) {
            long result = (randomBits[randomBitIndex >>> 6] >>> alignment) & lowMask(count);
            randomBitIndex += count;
            return result;
        } else {
            long result = (randomBits[randomBitIndex >>> 6] >>> alignment) & lowMask(64 - alignment);
            randomBitIndex += count;
            if (randomBitIndex >= MAX_RANDOM_BIT_INDEX) {
                moreRandomBits();
                randomBitIndex -= MAX_RANDOM_BIT_INDEX;
            }
            alignment = randomBitIndex & 63;
            result <<= alignment;
            result |= (randomBits[randomBitIndex >>> 6] >>> (64 - alignment)) & lowMask(alignment);

            return result;
        }
    }

    @Override
    public @NotNull RandomSource fork() {
        WorldgenCryptoRandom fork = new WorldgenCryptoRandom(0, 0, null, 0);

        System.arraycopy(this.worldSeed, 0, fork.worldSeed, 0, Globals.WORLD_SEED_LONGS);
        System.arraycopy(this.message, 0, fork.message, 0, this.message.length);
        System.arraycopy(this.randomBits, 0, fork.randomBits, 0, this.randomBits.length); // Rolia - fix fork() losing the random bit buffer
        fork.randomBitIndex = this.randomBitIndex;
        fork.counter = this.counter;

        return fork;
    }

    // Rolia - do NOT let positional randoms fall back to the wrapped constant-0 delegate (that would
    // make them independent of the secret seed). Derive a secret-dependent positional factory instead.
    @Override
    public PositionalRandomFactory forkPositional() {
        final long[] hashed = getHashedWorldSeed();
        long secure = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < hashed.length; i++) {
            secure ^= hashed[i];
            secure = Long.rotateLeft(secure, 17) * 0xBF58476D1CE4E5B9L;
        }
        secure ^= message[0] ^ Long.rotateLeft(message[1], 32) ^ (message[2] * 0x94D049BB133111EBL) ^ counter;
        return new LegacyRandomSource(secure).forkPositional();
    }

    @Override
    public int next(int bits) {
        return (int) getBits(bits);
    }

    @Override
    public void consumeCount(int count) {
        randomBitIndex += count;
        if (randomBitIndex >= MAX_RANDOM_BIT_INDEX * 2) {
            randomBitIndex -= MAX_RANDOM_BIT_INDEX;
            counter += randomBitIndex >>> LOG2_MAX_RANDOM_BIT_INDEX;
            randomBitIndex &= MAX_RANDOM_BIT_INDEX - 1;
            randomBitIndex += MAX_RANDOM_BIT_INDEX;
        }
    }

    @Override
    public int nextInt(int bound) {
        int bits = Mth.ceillog2(bound);
        int result;
        do {
            result = (int) getBits(bits);
        } while (result >= bound);

        return result;
    }

    @Override
    public long nextLong() {
        return getBits(64);
    }

    @Override
    public double nextDouble() {
        return getBits(53) * 0x1.0p-53;
    }

    @Override
    public long setDecorationSeed(long worldSeed, int blockX, int blockZ) {
        setSecureSeed(blockX, blockZ, Globals.Salt.POPULATION, 0);
        return ((long) blockX << 32) | ((long) blockZ & 0xffffffffL);
    }

    @Override
    public void setFeatureSeed(long populationSeed, int index, int step) {
        setSecureSeed((int) (populationSeed >> 32), (int) populationSeed, Globals.Salt.DECORATION, index + 10000L * step);
    }

    @Override
    public void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
        // Rolia - route through the secure stream instead of the raw level seed (was: super)
        setSecureSeed(chunkX, chunkZ, Globals.Salt.GENERATE_FEATURE, (int) (worldSeed ^ (worldSeed >>> 32)));
    }

    @Override
    public void setLargeFeatureWithSalt(long worldSeed, int regionX, int regionZ, int salt) {
        // Rolia - route through the secure stream instead of the raw level seed (was: super)
        setSecureSeed(regionX, regionZ, Globals.Salt.POTENTIONAL_FEATURE, salt);
    }
}