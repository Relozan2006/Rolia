#!/usr/bin/env python3
# Rolia - apply DAB (Dynamic Activation of Brain) hooks to the decompiled Minecraft source,
# run AFTER applyAllPatches and BEFORE compilation. tickSensors (brain sensors) and
# Mob.serverAiStep (goal-mob AI) are not otherwise patched, so we edit them here at build time.
# Fails loudly (exit 1) if the source shifted, so a silent miss can never ship.
import sys

def patch(path, old, new, what):
    s = open(path, encoding="utf-8").read()
    n = s.count(old)
    if n != 1:
        print("ERROR: expected 1 match for %s in %s, found %d" % (what, path, n), file=sys.stderr)
        sys.exit(1)
    open(path, "w", encoding="utf-8").write(s.replace(old, new, 1))
    print("OK: " + what)

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

# 4) SynchedEntityData.packDirty: pre-size the dirty list (output-identical, avoids ArrayList regrow)
SED = "canvas-server/src/minecraft/java/net/minecraft/network/syncher/SynchedEntityData.java"
patch(SED,
      "        this.isDirty = false;\n        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>();\n",
      "        this.isDirty = false;\n        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(this.itemsById.length); // Rolia - pre-size dirty list\n",
      "SynchedEntityData.packDirty pre-size")

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


# 7) Bulk writeLongArray (from Leaf) - byte-identical output (uses source.order()), faster chunk serialization
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
    "            } else if (output.source.nioBufferCount() > 0) {\n"
    "                output.source.nioBuffer(wi, neededBytes).asLongBuffer().put(longs);\n"
    "                output.source.writerIndex(wi + neededBytes);\n"
    "            } else {\n"
    "                final java.nio.ByteBuffer t = java.nio.ByteBuffer.allocate(neededBytes).order(output.source.order());\n"
    "                t.asLongBuffer().put(longs); t.rewind();\n"
    "                output.source.writeBytes(t);\n"
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
