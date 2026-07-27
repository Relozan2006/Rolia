#!/usr/bin/env python3
# Rolia - apply DAB (Dynamic Activation of Brain) hooks to the decompiled Minecraft source,
# run AFTER applyAllPatches and BEFORE compilation. tickSensors (brain sensors) and
# Mob.serverAiStep (goal-mob AI) are not otherwise patched, so we edit them here at build time.
# Fails loudly (exit 1) if the source shifted, so a silent miss can never ship.
import sys

def _dump_context(path, hint, label):
    """Print every line containing `hint` with its number, so a CI failure shows the real
    upstream text instead of just 'no match' - no second build round-trip needed."""
    if not hint:
        return
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
    except OSError as e:
        print("  (could not read %s: %s)" % (path, e), file=sys.stderr)
        return
    print("  --- lines in %s containing %r (%s) ---" % (path, hint, label), file=sys.stderr)
    shown = 0
    for i, line in enumerate(lines, 1):
        if hint in line:
            print("  %5d: %s" % (i, line.rstrip()), file=sys.stderr)
            shown += 1
            if shown >= 40:
                print("  ... (truncated)", file=sys.stderr)
                break
    if shown == 0:
        print("  (no line contains %r either)" % hint, file=sys.stderr)


def patch(path, old, new, what, hint=None, count=1, marker=None):
    """Replace `old` with `new` exactly `count` times. Fails loudly so a silent miss can never ship.

    `marker` makes the hook idempotent: if it is already present the hook is skipped instead of
    being applied a second time (several anchors still match after their own substitution, so a
    re-run used to double-apply them).
    `hint` is a short substring dumped with line numbers on failure, to identify upstream drift.
    """
    s = open(path, encoding="utf-8").read()
    if marker is not None and marker in s:
        print("SKIP (already applied): " + what)
        return
    n = s.count(old)
    if n != count:
        print("ERROR: expected %d match(es) for %s in %s, found %d" % (count, what, path, n), file=sys.stderr)
        _dump_context(path, hint, what)
        sys.exit(1)
    open(path, "w", encoding="utf-8").write(s.replace(old, new, count))
    print("OK: " + what + (" (x%d)" % count if count != 1 else ""))


def assert_contains(path, needle, why):
    """Guard an assumption the hook above depends on. Cheap insurance against a silently wrong stream."""
    s = open(path, encoding="utf-8").read()
    if needle not in s:
        print("ERROR: %s - expected to find %r in %s" % (why, needle, path), file=sys.stderr)
        sys.exit(1)
    print("OK (assert): " + why)

BRAIN = "canvas-server/src/minecraft/java/net/minecraft/world/entity/ai/Brain.java"
MOB = "canvas-server/src/minecraft/java/net/minecraft/world/entity/Mob.java"

# 1) throttle brain sensors for distant mobs (villagers/piglins etc.)
patch(BRAIN,
      "    private void tickSensors(final ServerLevel level, final E body) {\n",
      "    private void tickSensors(final ServerLevel level, final E body) {\n"
      "        if (!io.rolia.optimization.Dab.shouldTickBrain(body)) return; // Rolia - DAB\n",
      "Brain.tickSensors DAB gate")

# 2) throttle goal-mob AI (zombies/skeletons etc.) far from players; navigation still runs (mobs keep moving)
block = ("        this.sensing.tick();\n"
         "        int idBasedTickCount = this.tickCount + this.getId();\n"
         "        if (idBasedTickCount % 2 != 0 && this.tickCount > 1) {\n"
         "            this.targetSelector.tickRunningGoals(false);\n"
         "            this.goalSelector.tickRunningGoals(false);\n"
         "        } else {\n"
         "            this.targetSelector.tick();\n"
         "            this.goalSelector.tick();\n"
         "        }\n")
patch(MOB, block,
      "        if (io.rolia.optimization.Dab.shouldTickBrain(this)) { // Rolia - DAB (throttle goal-mob AI far from players)\n"
      + block +
      "        } // Rolia - DAB end\n",
      "Mob.serverAiStep DAB gate")

# 3) villager lobotomization: skip the expensive brain tick for boxed-in villagers, but still restock
VILLAGER = "canvas-server/src/minecraft/java/net/minecraft/world/entity/npc/villager/Villager.java"
patch(VILLAGER,
      "        if (!inactive) this.getBrain().tick(level, this); // Paper - EAR 2\n",
      "        if (!inactive) io.rolia.optimization.Lobotomize.tickVillagerBrain(this, level); // Rolia - villager lobotomization (restock preserved)\n",
      "Villager.customServerAiStep lobotomize gate")

# 4) (removed in build 38) SynchedEntityData.packDirty pre-sizing.
#    It sized the list to itemsById.length (~25-30 entries for a player) when packDirty returns the
#    DIRTY DELTA, which is typically 1-3 entries - and new ArrayList<>() allocates no backing array at
#    all until the first add. So it added a 25-30 slot Object[] allocation to every dirty sync of every
#    tracked entity to avoid a regrow that essentially never happened. Net GC pressure, no win.

# 5) Seed V2 - terrain under the secret: seed RandomState's root random from the secret (cascades to all terrain)
RANDOMSTATE = "canvas-server/src/minecraft/java/net/minecraft/world/level/levelgen/RandomState.java"
# seed is captured by the inner NoiseWiringHelper class, so it must stay final; use a new final local.
patch(RANDOMSTATE,
      "    private RandomState(final NoiseGeneratorSettings settings, final HolderGetter<NormalNoise.NoiseParameters> noises, final long seed) {\n        this.random = settings.getRandomSource().newInstance(seed).forkPositional();\n",
      "    private RandomState(final NoiseGeneratorSettings settings, final HolderGetter<NormalNoise.NoiseParameters> noises, final long seed) {\n        final long secureSeed = io.rolia.secureseed.Globals.secureTerrainSeed(seed); // Rolia - Seed V2: terrain under the secret\n        this.random = settings.getRandomSource().newInstance(secureSeed).forkPositional();\n",
      "RandomState terrain-under-secret (Seed V2 root)")
patch(RANDOMSTATE,
      "                return new LegacyRandomSource(seed + seedOffset);\n",
      "                return new LegacyRandomSource(secureSeed + seedOffset);\n",
      "RandomState legacy-nether noise under secret")
patch(RANDOMSTATE,
      "new DensityFunctions.EndIslandDensityFunction(seed)",
      "new DensityFunctions.EndIslandDensityFunction(secureSeed)",
      "RandomState End-island shape under secret")

# 6) Folia-safety: don't teleport a tamed pet into an UNLOADED chunk (raw getBlockState -> getBlockStateIfLoaded)
TAMABLE = "canvas-server/src/minecraft/java/net/minecraft/world/entity/TamableAnimal.java"
patch(TAMABLE,
      "        BlockState blockStateBelow = this.level().getBlockState(pos.below());\n        if (!this.canFlyToOwner() && blockStateBelow.getBlock() instanceof LeavesBlock) {\n",
      "        BlockState blockStateBelow = this.level().getBlockStateIfLoaded(pos.below()); // Rolia - Folia-safe\n        if (blockStateBelow == null) return false; // Rolia - do not teleport a pet into an unloaded chunk\n        if (!this.canFlyToOwner() && blockStateBelow.getBlock() instanceof LeavesBlock) {\n",
      "TamableAnimal.canTeleportTo unloaded-chunk guard")


# 8) SECRET SEED: cave/ravine carvers + worldgen mob spawning.
#    NoiseBasedChunkGenerator was never patched, so applyCarvers() and spawnOriginalMobs() built a
#    plain WorldgenRandom. Because the object was not a WorldgenCryptoRandom, the setLargeFeatureSeed /
#    setDecorationSeed overrides never ran, and both stayed 100% vanilla on the PUBLIC 64-bit level
#    seed - caves, ravines and the worldgen animal spread were all computable with off-the-shelf seed
#    crackers, which is exactly the information this fork exists to hide (ravines expose ore layers).
#    Swapping the constructed object is enough: the very next statement in each method is a
#    setLargeFeatureSeed / setDecorationSeed call, and those overrides fully (re)key the stream.
NBCG = "canvas-server/src/minecraft/java/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java"
# These two guards are the important part. If upstream ever stops re-seeding right after construction,
# the object would keep its constructor coordinates (0,0) and EVERY chunk would share one carver
# stream - identical caves worldwide. Fail the build instead of shipping that.
assert_contains(NBCG, "setLargeFeatureSeed(",
                "carvers must re-seed after construction, else all chunks share one stream")
assert_contains(NBCG, "setDecorationSeed(",
                "worldgen mob spawning must re-seed after construction, else all chunks share one stream")
patch(NBCG,
      "new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()))",
      "new io.rolia.secureseed.WorldgenCryptoRandom(0, 0, io.rolia.secureseed.Globals.Salt.CARVER, 0) /* Rolia - carvers + worldgen mob spawn under the secret seed; re-keyed by the setLargeFeatureSeed/setDecorationSeed call that follows */",
      "NoiseBasedChunkGenerator carvers + mob spawn under the secret seed",
      hint="WorldgenRandom", count=2, marker="io.rolia.secureseed.WorldgenCryptoRandom")

# 7) Bulk writeLongArray (from Leaf) - byte-identical output (uses source.order()), faster chunk serialization.
#    Build 38: the nio branch is now `nioBufferCount() == 1 && !isReadOnly()`, not `> 0`.
#    Netty's contract for nioBuffer(int,int) is "share OR CONTAIN A COPY OF" the buffer's content.
#    For a CompositeByteBuf whose components straddle the requested range, nioBufferCount() returns
#    >= 2 (so the old `> 0` guard passed) but nioBuffer() hands back a throwaway heap ByteBuffer:
#    the longs are written into that copy and discarded, and writerIndex is then advanced over
#    uninitialised buffer memory - i.e. hundreds of longs of garbage in a chunk packet. Composites
#    arrive via ViaVersion and any future packet batching. The unreachable heap-staging branch was
#    also dropped in favour of the plain loop, which is correct for every ByteBuf implementation.
FBB = "canvas-server/src/minecraft/java/net/minecraft/network/FriendlyByteBuf.java"
_BULK = (
    "    // Rolia start - bulk writeLongArray (byte-identical; config: optimizations.faster-network)\n"
    "    private static void writeLongArrayBulk(final FriendlyByteBuf output, final long[] longs) {\n"
    "        VarInt.write(output, longs.length);\n"
    "        writeFixedSizeLongArrayBulk(output, longs);\n"
    "    }\n"
    "    private static void writeFixedSizeLongArrayBulk(final FriendlyByteBuf output, final long[] longs) {\n"
    "        if (longs.length == 0) return;\n"
    "        if (!io.rolia.RoliaConfig.fasterNetwork()) { for (long l : longs) output.source.writeLong(l); return; }\n"
    "        final int neededBytes = longs.length * Long.BYTES;\n"
    "        if (output.source.maxWritableBytes() >= neededBytes) {\n"
    "            output.source.ensureWritable(neededBytes);\n"
    "            final int wi = output.source.writerIndex();\n"
    "            if (output.source.hasArray()) {\n"
    "                java.nio.ByteBuffer.wrap(output.source.array(), output.source.arrayOffset() + wi, neededBytes).order(output.source.order()).asLongBuffer().put(longs);\n"
    "                output.source.writerIndex(wi + neededBytes);\n"
    "            } else if (output.source.nioBufferCount() == 1 && !output.source.isReadOnly()) {\n"
    "                output.source.nioBuffer(wi, neededBytes).asLongBuffer().put(longs);\n"
    "                output.source.writerIndex(wi + neededBytes);\n"
    "            } else {\n"
    "                for (long l : longs) output.source.writeLong(l);\n"
    "            }\n"
    "        } else {\n"
    "            for (long l : longs) output.source.writeLong(l);\n"
    "        }\n"
    "    }\n"
    "    // Rolia end\n"
)
patch(FBB,
      "    public FriendlyByteBuf writeLongArray(final long[] longs) {\n        writeLongArray(this, longs);\n",
      _BULK + "    public FriendlyByteBuf writeLongArray(final long[] longs) {\n        writeLongArrayBulk(this, longs);\n",
      "FriendlyByteBuf bulk writeLongArray + methods")
patch(FBB,
      "        writeFixedSizeLongArray(this, longs);\n",
      "        writeFixedSizeLongArrayBulk(this, longs);\n",
      "FriendlyByteBuf bulk writeFixedSizeLongArray call")


print("Rolia DAB source hooks applied.")
