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

print("Rolia DAB source hooks applied.")
