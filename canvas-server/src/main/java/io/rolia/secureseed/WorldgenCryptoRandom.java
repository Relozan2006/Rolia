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
    private Globals.Salt typeSalt = Globals.Salt.UNDEFINED; // Rolia - remembered so setSeed() stays in-domain

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
        // Rolia - go through publishedWorldSeed(): its volatile read establishes happens-before with
        // the seed publication in Globals.setupGlobals, so this thread cannot observe a partially
        // written (or stale all-zero) seed on weakly-ordered hardware.
        System.arraycopy(Globals.publishedWorldSeed(), 0, this.worldSeed, 0, Globals.WORLD_SEED_LONGS);
        this.typeSalt = typeSalt;
        message[0] = ((long) x << 32) | ((long) z & 0xffffffffL);
        message[1] = ((long) Globals.dimension.get() << 32) | ((long) salt & 0xffffffffL);
        message[2] = typeSalt.id; // Rolia - explicit persistent id (see Globals.Salt), never ordinal()
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
            // Rolia - the stream is little-endian within each word: bit j of word i is stream bit i*64+j.
            // So the tail of a draw that spans a word boundary must come from the LOW bits of the next
            // word, shifted up above the bits already taken. The previous code took the HIGH bits
            // (>>> (64 - alignment)) and shifted the FIRST part instead, which meant the top bits of
            // each spanned word were consumed twice - once here and again by the following draw - while
            // the low bits were never consumed at all. Deterministic, so not a world-consistency bug,
            // but it correlated consecutive values across every word boundary.
            final int firstBits = 64 - alignment;
            long result = (randomBits[randomBitIndex >>> 6] >>> alignment) & lowMask(firstBits);
            randomBitIndex += count;
            if (randomBitIndex >= MAX_RANDOM_BIT_INDEX) {
                moreRandomBits();
                randomBitIndex -= MAX_RANDOM_BIT_INDEX;
            }
            final int remaining = randomBitIndex & 63; // == count - firstBits
            result |= (randomBits[randomBitIndex >>> 6] & lowMask(remaining)) << firstBits;

            return result;
        }
    }

    /**
     * Rolia - vanilla {@code fork()} is {@code new LegacyRandomSource(this.nextLong())}: it CONSUMES from
     * the parent, so parent and child are decorrelated and two successive forks differ. Copying our state
     * verbatim made the child replay the parent's exact sequence, and made {@code fork(); fork();} hand
     * back two identical children. Everywhere vanilla forks for independence - noise octave chains,
     * feature sub-placement, jigsaw sub-placers - those sub-streams were perfectly correlated, which is a
     * visible worldgen-quality regression rather than a mere statistical nit.
     *
     * <p>Now two longs are drawn from the parent (advancing it) and folded into {@code message[4..5]},
     * which are otherwise always zero but DO feed the compression input in {@link #moreRandomBits()},
     * so the child gets a genuinely independent stream keyed on the same secret.</p>
     */
    @Override
    public @NotNull RandomSource fork() {
        final long a = this.nextLong(); // advance the parent - this is what decorrelates the two
        final long b = this.nextLong();

        WorldgenCryptoRandom fork = new WorldgenCryptoRandom(0, 0, null, 0);
        System.arraycopy(this.worldSeed, 0, fork.worldSeed, 0, Globals.WORLD_SEED_LONGS);
        System.arraycopy(this.message, 0, fork.message, 0, this.message.length);
        fork.typeSalt = this.typeSalt;
        fork.message[4] = a;
        fork.message[5] = b;
        fork.message[3] = 0;
        fork.counter = 0;
        fork.randomBitIndex = MAX_RANDOM_BIT_INDEX; // force a refill on first use

        return fork;
    }

    // Rolia - do NOT let positional randoms fall back to the wrapped constant-0 delegate (that would
    // make them independent of the secret seed). Derive a secret-dependent positional factory instead.
    @Override
    public PositionalRandomFactory forkPositional() {
        // Rolia - do NOT let positional randoms fall back to the wrapped constant-0 delegate (that would
        // make them independent of the secret seed). Derive a secret-dependent factory instead.
        // Build 39: seed a Xoroshiro factory with 128 bits rather than collapsing everything into a
        // single long for LegacyRandomSource, which keeps only 48 bits of state - so every positional
        // sub-random derived from the secret used to carry <= 48 bits of derived entropy.
        final long[] hashed = getHashedWorldSeed();
        long lo = 0x9E3779B97F4A7C15L;
        long hi = 0xBF58476D1CE4E5B9L;
        for (int i = 0; i < hashed.length; i++) {
            lo ^= hashed[i];
            lo = Long.rotateLeft(lo, 17) * 0xBF58476D1CE4E5B9L;
            hi ^= Long.rotateLeft(hashed[i], 32);
            hi = Long.rotateLeft(hi, 29) * 0x94D049BB133111EBL;
        }
        lo ^= message[0] ^ Long.rotateLeft(message[1], 32) ^ (message[2] * 0x94D049BB133111EBL) ^ counter;
        hi ^= Long.rotateLeft(message[0], 41) ^ message[1] ^ (message[3] * 0xD6E8FEB86659FD93L);
        return new net.minecraft.world.level.levelgen.XoroshiroRandomSource(lo, hi).forkPositional();
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
        // Rolia - match vanilla BitRandomSource: a non-positive bound is a programming error, not a hang.
        // Mth.ceillog2(0) is 0, so getBits(0) returns 0 forever and the rejection loop below would spin
        // a worldgen/region thread indefinitely (reachable from a datapack structure-set probability > 1).
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
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

    // Rolia - WorldgenCryptoRandom draws every bit from its own stream, so an un-overridden setSeed()
    // would reseed only the inert LegacyRandomSource(0L) delegate handed to super() and do nothing at all
    // (this is why Paper's stronghold-seed config was silently ignored). Honour the reseed inside the
    // secure stream instead, keeping the current domain.
    // NOTE when updating Minecraft: every RandomSource/WorldgenRandom mutator must be overridden here,
    // or it will compile, run, and silently have no effect.
    @Override
    public void setSeed(long seed) {
        // Rolia - WorldgenRandom extends LegacyRandomSource, whose CONSTRUCTOR calls setSeed(). Because
        // this override is virtual it runs during super(), i.e. before any field of this class has been
        // initialised - worldSeed/message/typeSalt are all still null at that point. Delegate to the
        // superclass then (it is initialising its own state) and only take over once we actually exist.
        if (this.worldSeed == null) {
            super.setSeed(seed);
            return;
        }
        setSecureSeed((int) (seed >>> 32), (int) seed,
            this.typeSalt == null ? Globals.Salt.UNDEFINED : this.typeSalt, 0);
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