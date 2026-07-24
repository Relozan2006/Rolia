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

print("Rolia DAB source hooks applied.")
