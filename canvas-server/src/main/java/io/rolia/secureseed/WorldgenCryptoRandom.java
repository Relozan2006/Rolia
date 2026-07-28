package io.rolia.secureseed;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.jetbrains.annotations.NotNull;

/**
 * Rolia - the secret-seeded worldgen RNG.
 *
 * <h2>Build 41: two-tier design</h2>
 *
 * <p>Builds 38-40 ran a full cryptographic stream: one BLAKE2b compression per 512 bits consumed.
 * That is affordable for structures and decorations, and ruinous for carvers. {@code applyCarvers}
 * walks a 17x17 chunk neighbourhood and reseeds once per (position, carver) - about 578 reseeds per
 * generated chunk - and every reseed forced a fresh compression from which {@code isStartChunk} used
 * 24 bits out of 512. Together with the actual carving that came to roughly 22 000 compressions per
 * chunk, and chunk generation fell from ~150 chunks/s to ~20.</p>
 *
 * <p>Now the crypto is spent once per RESEED rather than once per 512 bits:</p>
 * <pre>
 *   setSecureSeed():  (s0, s1) = MAC(secret, domain || x || z || salt)   // one compression
 *   every draw:       Xoroshiro128++ over (s0, s1)                      // vanilla speed
 * </pre>
 *
 * <p><b>Why this is still secure.</b> Xoroshiro is not a cryptographic generator - given enough of
 * its output its state can be recovered. That does not matter here, because every (chunk, domain)
 * pair gets its own independent 128-bit seed produced by a one-way keyed MAC. Recovering one stream's
 * state tells an attacker the contents of that one chunk - which a player standing in it can simply
 * look at - and reveals nothing about the secret or about any other chunk. Predicting a chunk you
 * have not visited still requires the 1024-bit secret. So the property that matters, "you cannot
 * compute where things are without the secret", is preserved exactly, while the per-bit cost drops
 * to vanilla's.</p>
 *
 * <p>The state is two plain long fields rather than a delegate object: with ~578 reseeds per chunk,
 * allocating anything per reseed would give back much of the win.</p>
 */
public class WorldgenCryptoRandom extends WorldgenRandom {

    /**
     * Rolia - the 512-bit key derived from the secret seed and the salt.
     *
     * <p>Computed once for the whole process. {@code Globals.worldSeed} is published exactly once and
     * never mutated afterwards, so the per-thread "has the seed changed?" cache that used to guard
     * this - a 16-long {@code Arrays.equals} plus two {@code ThreadLocal.get()} calls on EVERY
     * compression - was protecting against a change that cannot happen.</p>
     */
    private static volatile long[] hashedSeed;

    private static long[] hashedSeed() {
        long[] cached = hashedSeed;
        if (cached != null) {
            return cached;
        }
        synchronized (WorldgenCryptoRandom.class) {
            if (hashedSeed == null) {
                hashedSeed = Hashing.hashWorldSeed(Globals.publishedWorldSeed());
            }
            return hashedSeed;
        }
    }

    // Reusable MAC buffers. Allocated once per instance, never per reseed.
    private final long[] macIn = new long[16];
    private final long[] macOut = new long[8];

    // Xoroshiro128++ state, seeded from the MAC output on every reseed.
    private long s0;
    private long s1;

    private Globals.Salt typeSalt = Globals.Salt.UNDEFINED; // remembered so setSeed() stays in-domain

    /**
     * Rolia - the dimension separator, captured ONCE at construction rather than re-read from the
     * ambient ThreadLocal on every reseed. Call sites that are not on a prepared worldgen thread (the
     * Bukkit API, /summon spawn checks, stronghold rings) pass it explicitly; relying on the ambient
     * value there produced answers that depended on whichever world the thread last touched.
     */
    private final int dimensionId;

    /**
     * Rolia - {@code secure-seed.enabled: false}. Every override below then delegates straight to
     * {@link WorldgenRandom}, so all fifteen worldgen call sites become vanilla without any of them
     * being touched.
     *
     * <p>This works because of a property of those call sites: every one of them re-seeds through
     * {@code setDecorationSeed}, {@code setFeatureSeed}, {@code setLargeFeatureSeed},
     * {@code setLargeFeatureWithSalt} or {@code setSeed} immediately after construction, exactly as
     * vanilla does, so delegating those five methods reproduces vanilla's stream bit for bit. The two
     * sites that do NOT re-seed - stronghold ring generation and slime chunks - are handled explicitly:
     * the former is constructed from the level seed below, and the latter has its own vanilla path in
     * {@link #seedSlimeChunk}.</p>
     */
    private final boolean disabled;

    /** Worldgen paths: the dimension comes from the thread that {@code setupGlobals} prepared. */
    public WorldgenCryptoRandom(int x, int z, Globals.Salt typeSalt, long salt) {
        this(Globals.dimension.get(), x, z, typeSalt, salt);
    }

    /** Rolia - explicit dimension, for every call site that is NOT on a prepared worldgen thread. */
    public WorldgenCryptoRandom(int dimensionId, int x, int z, Globals.Salt typeSalt, long salt) {
        // Rolia - when the secure seed is off, start from the public level seed, which is what vanilla's
        // `new WorldgenRandom(new LegacyRandomSource(level.getSeed()))` does at the sites that use the
        // instance without re-seeding it.
        super(new LegacyRandomSource(Globals.isSecureSeedEnabled() ? 0L : Globals.levelSeed()));
        this.dimensionId = dimensionId;
        this.disabled = !Globals.isSecureSeedEnabled();

        if (this.disabled) {
            // Nothing keyed to set up: this instance is now an ordinary WorldgenRandom.
            return;
        }

        if (typeSalt == null) {
            // fork() fills the state itself; leave a non-degenerate value so an unseeded instance can
            // never sit on the all-zero Xoroshiro state (which would emit zeros forever).
            this.s0 = 0x9E3779B97F4A7C15L;
            this.s1 = 0xBF58476D1CE4E5B9L;
            return;
        }

        this.setSecureSeed(x, z, typeSalt, salt);
    }

    /**
     * Rolia - slime chunks. The dimension MUST be passed explicitly: this is reachable from the Bukkit
     * API and from spawn checks driven by /summon, neither of which runs on a prepared worldgen thread.
     *
     * <p>This is one of only two call sites that USES the instance without re-seeding it, so when the
     * secure seed is switched off it needs vanilla's own construction rather than the generic fallback -
     * hence the level seed and Spigot's slime seed being passed in. Getting this wrong would move every
     * slime chunk on a server that had the secure seed disabled, which is precisely the kind of silent
     * difference this fork exists to avoid.</p>
     *
     * @param levelSeed the public level seed, as {@code WorldgenRandom.seedSlimeChunk} expects
     * @param slimeSeed {@code spigotConfig.slimeSeed} (vanilla's 987234911L unless configured)
     */
    public static RandomSource seedSlimeChunk(int dimensionId, int chunkX, int chunkZ, long levelSeed, long slimeSeed) {
        if (!Globals.isSecureSeedEnabled()) {
            return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, levelSeed, slimeSeed);
        }
        return new WorldgenCryptoRandom(dimensionId, chunkX, chunkZ, Globals.Salt.SLIME_CHUNK, 0);
    }

    /**
     * Rolia - derive this instance's stream from the secret. One keyed BLAKE2b compression, then the
     * whole stream runs from Xoroshiro. This is the only place crypto is spent.
     */
    public void setSecureSeed(int x, int z, Globals.Salt typeSalt, long salt) {
        this.typeSalt = typeSalt;

        final long[] key = hashedSeed();
        final long[] m = this.macIn;
        // message layout is unchanged from the streaming design, so the domain separation is the same:
        // [0] position, [1] dimension + caller salt, [2] domain id, [3..15] reserved/zero.
        // NOTE the parentheses: in Java '^' binds TIGHTER than '|', so `a | b ^ k` would mean
        // `a | (b ^ k)` and the position would only be half-keyed. Keep them explicit.
        m[0] = ((((long) x) << 32) | ((long) z & 0xffffffffL)) ^ key[0];
        m[1] = ((((long) this.dimensionId) << 32) | ((long) salt & 0xffffffffL)) ^ key[1];
        m[2] = ((long) typeSalt.id) ^ key[2];
        m[3] = key[3];
        m[4] = key[4];
        m[5] = key[5];
        m[6] = key[6];
        m[7] = key[7];
        // m[8..15] stay zero for the lifetime of the instance.

        Hashing.hash(m, this.macOut);

        long a = this.macOut[0];
        long b = this.macOut[1];
        if ((a | b) == 0L) {
            // Xoroshiro's all-zero state is absorbing. Probability ~2^-128, but a fixed world would be
            // catastrophic and the check is one branch per reseed.
            b = 0x9E3779B97F4A7C15L;
        }
        this.s0 = a;
        this.s1 = b;
    }

    /** Rolia - Xoroshiro128++, the same generator vanilla uses for modern worldgen. */
    private long nextBits64() {
        final long x = this.s0;
        long y = this.s1;
        final long result = Long.rotateLeft(x + y, 17) + x;
        y ^= x;
        this.s0 = Long.rotateLeft(x, 49) ^ y ^ (y << 21);
        this.s1 = Long.rotateLeft(y, 28);
        return result;
    }

    @Override
    public int next(int bits) {
        if (this.disabled) {
            return super.next(bits);
        }
        // Take the high bits: Xoroshiro128++'s low bits are the weakest, and vanilla's own
        // XoroshiroRandomSource does exactly this.
        return (int) (nextBits64() >>> (64 - bits));
    }

    @Override
    public long nextLong() {
        if (this.disabled) {
            // Vanilla BitRandomSource#nextLong, written out rather than delegated. nextLong(),
            // nextInt(int) and consumeCount() are DEFAULT METHODS on an interface the superclass
            // implements, and `super.x()` through that chain is the kind of thing that compiles on one
            // Minecraft version and not the next. super.next(int) is safe because LegacyRandomSource
            // declares it outright.
            final int hi = super.next(32);
            final int lo = super.next(32);
            return ((long) hi << 32) + (long) lo;
        }
        return nextBits64();
    }

    @Override
    public int nextInt(int bound) {
        if (this.disabled) {
            if (bound <= 0) {
                throw new IllegalArgumentException("Bound must be positive");
            }
            // Vanilla BitRandomSource#nextInt, same reasoning as nextLong above: identical draw count
            // and identical rejection loop, so the stream matches vanilla exactly.
            if ((bound & -bound) == bound) {
                return (int) (((long) bound * (long) super.next(31)) >> 31);
            }
            int i;
            int j;
            do {
                i = super.next(31);
                j = i % bound;
            } while (i - j + (bound - 1) < 0);
            return j;
        }
        // Match vanilla: a non-positive bound is a programming error, not undefined behaviour.
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }
        // Vanilla BitRandomSource#nextInt, reproduced rather than delegated: it is a default method on
        // an interface the superclass implements, and `super.nextInt(...)` through that chain is the
        // kind of thing that compiles on one Minecraft version and not the next. Same draw count and
        // same rejection loop as vanilla, so consumption is identical.
        if ((bound & -bound) == bound) {
            return (int) (((long) bound * (long) next(31)) >> 31);
        }
        int i;
        int j;
        do {
            i = next(31);
            j = i % bound;
        } while (i - j + (bound - 1) < 0);
        return j;
    }

    @Override
    public void consumeCount(int count) {
        if (this.disabled) {
            // Vanilla RandomSource#consumeCount is `for (i < count) this.nextInt();`, and nextInt() is
            // next(32). Written out for the same reason as nextLong/nextInt above.
            for (int i = 0; i < count; i++) {
                super.next(32);
            }
            return;
        }
        // RandomSource#consumeCount skips `count` rounds of nextInt(), i.e. count draws - not count
        // bits, which is what builds 39-40 did (a 32x mismatch that broke PerlinNoise.skipOctave's
        // octave alignment). A non-positive count is a no-op in vanilla.
        for (int i = 0; i < count; i++) {
            nextBits64();
        }
    }

    /**
     * Rolia - vanilla {@code fork()} consumes from the parent, so parent and child decorrelate and two
     * successive forks differ. Same here: two draws advance the parent and become the child's state.
     */
    @Override
    public @NotNull RandomSource fork() {
        if (this.disabled) {
            return super.fork();
        }
        final long a = nextBits64();
        long b = nextBits64();
        if ((a | b) == 0L) {
            b = 0x9E3779B97F4A7C15L;
        }
        final WorldgenCryptoRandom fork = new WorldgenCryptoRandom(this.dimensionId, 0, 0, null, 0);
        fork.typeSalt = this.typeSalt;
        fork.s0 = a;
        fork.s1 = b;
        return fork;
    }

    /**
     * Rolia - also consumes from the parent, for the same reason. Before build 39 this was a pure
     * function of the current state, so two calls returned identical factories and NormalNoise - which
     * builds two PerlinNoise layers from one source - collapsed both layers onto the same noise.
     */
    @Override
    public PositionalRandomFactory forkPositional() {
        if (this.disabled) {
            return super.forkPositional();
        }
        final long lo = nextBits64();
        final long hi = nextBits64();
        return new XoroshiroRandomSource(lo, hi).forkPositional();
    }

    /**
     * Rolia - WorldgenRandom extends LegacyRandomSource, whose CONSTRUCTOR calls setSeed(). Because
     * this override is virtual it runs during super(), before any field of this class exists - hence
     * the null check on a field that is initialised by the field initialiser. Honour a genuine reseed
     * inside the secret stream (this is what makes Paper's stronghold-seed config work).
     */
    @Override
    public void setSeed(long seed) {
        // Rolia - macIn == null means we are still inside LegacyRandomSource's constructor (see above);
        // `disabled` is not assigned yet at that point either, so this check has to come first.
        if (this.macIn == null || this.disabled) {
            super.setSeed(seed);
            return;
        }
        setSecureSeed((int) (seed >>> 32), (int) seed,
            this.typeSalt == null ? Globals.Salt.UNDEFINED : this.typeSalt, 0);
    }

    @Override
    public long setDecorationSeed(long worldSeed, int blockX, int blockZ) {
        if (this.disabled) {
            return super.setDecorationSeed(worldSeed, blockX, blockZ);
        }
        setSecureSeed(blockX, blockZ, Globals.Salt.POPULATION, 0);
        return ((long) blockX << 32) | ((long) blockZ & 0xffffffffL);
    }

    @Override
    public void setFeatureSeed(long populationSeed, int index, int step) {
        if (this.disabled) {
            super.setFeatureSeed(populationSeed, index, step);
            return;
        }
        setSecureSeed((int) (populationSeed >> 32), (int) populationSeed, Globals.Salt.DECORATION, index + 10000L * step);
    }

    @Override
    public void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
        if (this.disabled) {
            super.setLargeFeatureSeed(worldSeed, chunkX, chunkZ);
            return;
        }
        // Keep an explicitly chosen domain instead of forcing GENERATE_FEATURE. Carvers are constructed
        // with Salt.CARVER and then reseeded through here; forcing the structure domain made carver
        // index 0 produce a stream identical to Structure.makeRandom for the same chunk, and a player
        // can SEE cave shape - which would have leaked the leading output of the structure stream.
        final Globals.Salt domain = this.typeSalt == Globals.Salt.UNDEFINED ? Globals.Salt.GENERATE_FEATURE : this.typeSalt;
        setSecureSeed(chunkX, chunkZ, domain, (int) (worldSeed ^ (worldSeed >>> 32)));
    }

    @Override
    public void setLargeFeatureWithSalt(long worldSeed, int regionX, int regionZ, int salt) {
        if (this.disabled) {
            super.setLargeFeatureWithSalt(worldSeed, regionX, regionZ, salt);
            return;
        }
        setSecureSeed(regionX, regionZ, Globals.Salt.POTENTIONAL_FEATURE, salt);
    }
}
