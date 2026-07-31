#!/usr/bin/env python3
"""Rolia - source hooks applied AFTER applyAllPatches and BEFORE compilation.

WHY A SCRIPT AND NOT PATCHES
    Several methods Rolia needs to touch are not patched by Canvas at all (Brain.tickSensors,
    Mob.serverAiStep, NoiseBasedChunkGenerator.applyCarvers, RandomState's constructor). Adding
    paperweight patch files for them would work, but every one of those files is DECOMPILED output,
    so a patch against it is no more stable than a string match - and a rejected patch and a missed
    substitution fail at the same point in the build for the same reason.

WHAT THE SCRIPT GIVES UP, AND WHAT BUILD 43 GIVES BACK
    What paperweight has that a plain substitution does not is CONTEXT: git checks the lines around
    a change, so a hunk that still matches its anchor but sits in a method upstream has rewritten is
    rejected rather than applied. A bare `s.replace(old, new)` has no such check. Two things can go
    wrong silently:

      - the anchor still matches, but somewhere else, because upstream moved the code;
      - the anchor matches in the right place, but the surrounding logic changed underneath it, so
        the hook is now inserted into a method that no longer does what it did.

    Both are now caught. Every hook records a checksum over a fixed window of source lines around
    its anchor, and a change anywhere in that window fails the build with the window printed. That
    is the same guarantee git's context lines provide, made explicit.

    Run with --print-context-hashes after a deliberate upstream update to get the new values.

    Fails loudly (exit 1) on any mismatch, so a silent miss can never ship.
"""
import hashlib
import sys

PRINT_HASHES = "--print-context-hashes" in sys.argv
_HASH_REPORT = []

# Lines of context captured on each side of an anchor. Wide enough to cover the method a hook sits
# in, narrow enough that unrelated edits elsewhere in a 3000-line file do not trip it.
CONTEXT_LINES = 12


def _context_hash(text, anchor, search_from=0, context_lines=None):
    """Checksum the source around ONE occurrence of `anchor`.

    Returns (hash, window_text, first_line_number, index_of_this_occurrence).

    Whitespace at end of line is stripped before hashing so a stray trailing space in the decompiler
    output cannot fail a build on its own.

    Build 44: `search_from` exists because a hook may substitute at SEVERAL sites (the carver /
    worldgen-mob-spawn hook does two). Until now only the FIRST occurrence was ever checksummed, so
    the second site was a bare str.replace with no guard at all - exactly the failure mode the module
    docstring claims is impossible. Each occurrence now carries its own hash.

    `context_lines` overrides the default window. The carver hook needs it: its re-seed call sits 27
    lines and three nested loops below the anchor, i.e. outside a 12-line window, so an upstream edit
    that moved or deleted that re-seed would have changed nothing the guard looks at - and every chunk
    in the world would then share one carver stream (identical caves everywhere).
    """
    span = CONTEXT_LINES if context_lines is None else context_lines
    idx = text.find(anchor, search_from)
    if idx < 0:
        return None, None, 0, -1
    start_line = text.count("\n", 0, idx)
    end_line = start_line + anchor.count("\n")
    lines = text.split("\n")
    lo = max(0, start_line - span)
    hi = min(len(lines), end_line + span + 1)
    window = "\n".join(l.rstrip() for l in lines[lo:hi])
    return hashlib.sha256(window.encode("utf-8")).hexdigest()[:16], window, lo + 1, idx

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


def patch(path, old, new, what, hint=None, count=1, marker=None, context=None, context_lines=None):
    """Replace `old` with `new` exactly `count` times. Fails loudly so a silent miss can never ship.

    `marker` makes the hook idempotent: if it is already present the hook is skipped instead of
    being applied a second time (several anchors still match after their own substitution, so a
    re-run used to double-apply them).
    `hint` is a short substring dumped with line numbers on failure, to identify upstream drift.
    `context` is the expected checksum of the surrounding source (see the module docstring). This is
    what replaces git's context lines: the anchor matching is not enough on its own, because upstream
    can rewrite the method around it and leave the anchor intact.
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

    # Build 44: one hash PER OCCURRENCE. `context` is a string when count == 1 and a list/tuple of
    # `count` hashes otherwise.
    actuals = []
    windows = []
    firstlines = []
    pos = 0
    for _ in range(count):
        a, w, fl, idx = _context_hash(s, old, pos, context_lines)
        if idx < 0:
            break
        actuals.append(a)
        windows.append(w)
        firstlines.append(fl)
        pos = idx + 1
    if PRINT_HASHES:
        _HASH_REPORT.append((what, actuals[0] if count == 1 else list(actuals)))
    elif context is not None:
        expected = [context] if isinstance(context, str) else list(context)
        if len(expected) != count:
            print("ERROR: %s - declares count=%d but %d context hash(es); they must match."
                  % (what, count, len(expected)), file=sys.stderr)
            sys.exit(1)
        for i, (exp, act) in enumerate(zip(expected, actuals)):
            if exp == act:
                continue
            print("ERROR: %s - the source AROUND occurrence %d of %d changed."
                  % (what, i + 1, count), file=sys.stderr)
            print("       expected context %s, found %s" % (exp, act), file=sys.stderr)
            print("       The anchor still matches, so the substitution would have been applied silently", file=sys.stderr)
            print("       into a method upstream has rewritten. Read the window below, decide whether the", file=sys.stderr)
            print("       hook is still correct, then update the context hash in scripts/apply_dab_hooks.py", file=sys.stderr)
            print("       (run it with --print-context-hashes to get the new values).", file=sys.stderr)
            print("       --- %s lines %d.. ---" % (path, firstlines[i]), file=sys.stderr)
            for k, line in enumerate(windows[i].split("\n")):
                print("       %5d: %s" % (firstlines[i] + k, line), file=sys.stderr)
            sys.exit(1)

    open(path, "w", encoding="utf-8").write(s.replace(old, new, count))
    print("OK: " + what + (" (x%d)" % count if count != 1 else "") + ("" if context is None else " [ctx %s]" % context))


def assert_contains(path, needle, why):
    """Guard an assumption the hook above depends on. Cheap insurance against a silently wrong stream."""
    s = open(path, encoding="utf-8").read()
    if needle not in s:
        print("ERROR: %s - expected to find %r in %s" % (why, needle, path), file=sys.stderr)
        sys.exit(1)
    print("OK (assert): " + why)

BRAIN = "canvas-server/src/minecraft/java/net/minecraft/world/entity/ai/Brain.java"
MOB = "canvas-server/src/minecraft/java/net/minecraft/world/entity/Mob.java"

# 1) REMOVED in build 45 - the gate multiplied a documented setting instead of throttling it.
#
# It read:
#     private void tickSensors(final ServerLevel level, final E body) {
#         if (!Dab.shouldTickBrain(body)) return;    <- here
#
# Brain.tickSensors is nothing but a loop over sensor.tick(level, body), and Sensor.tick is:
#     public final void tick(level, body) {
#         if (--this.timeToTick <= 0L) {
#             this.timeToTick = <Paper's configured sensor rate>;
#             this.updateTargetingConditionRanges(body);
#             this.doTick(level, body);
#         }
#     }
# The counter only moves when the method is CALLED. Gating the caller therefore does not make the
# sensor run on the DAB interval - it multiplies the operator's configured scanRate BY it. At
# Paper's default rate of 20 and DAB's default interval of 20, a distant villager rescanned its
# surroundings once every 400 ticks: twenty seconds, from a setting whose file says one.
#
# A throttle belongs in the schedule, so the hook moves to Sensor.tick itself - see hook 1b below.

# 1b) throttle brain sensors by lengthening their SCHEDULE, which is the thing that decides how often
# they run. Dab.sensorPeriod returns max(configuredRate, dabInterval): DAB may stretch a sensor that
# scans faster than its interval and can never make one slower than the rate the operator configured.
# At the defaults - Paper's rate 20, DAB's interval 20 - the two are equal and nothing changes, which
# is exactly what the old gate failed to do when it turned 20 into 400.
SENSOR = "canvas-server/src/minecraft/java/net/minecraft/world/entity/ai/sensing/Sensor.java"
patch(SENSOR,
      "            this.timeToTick = java.util.Objects.requireNonNullElse(level.paperConfig().tickRates.sensor.get(body.getType(), this.configKey), this.scanRate); // Paper - configurable sensor tick rate and timings\n",
      "            this.timeToTick = io.rolia.optimization.Dab.sensorPeriod(body, java.util.Objects.requireNonNullElse(level.paperConfig().tickRates.sensor.get(body.getType(), this.configKey), this.scanRate)); // Paper - configurable sensor tick rate and timings // Rolia - DAB throttles the schedule, not the call\n",
      "Sensor.tick DAB schedule", context="5b94c96e96141a1c")

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
# Rolia - build 45: this.sensing.tick() moves OUT of the gate.
#
# EntitySenses#tick clears the line-of-sight cache; the cache is what makes a mob's idea of what it
# can see up to `optimizations.ai.line-of-sight-interval` ticks stale, and that option documents
# "up to 3 ticks". Inside the gate it was cleared only on ticks DAB allowed, so the real staleness
# was DAB's interval multiplied by that option - 60 ticks at the defaults, three seconds of a mob
# shooting at a wall a player had already stepped out from behind. Clearing a cache is a couple of
# field writes; there is nothing to save by skipping it, and it was never the point of the gate.
gated = block[block.index("        int idBasedTickCount"):]
patch(MOB, block,
      "        this.sensing.tick(); // Rolia - build 45: outside the DAB gate; gating it multiplied line-of-sight-interval by the DAB interval\n"
      "        if (io.rolia.optimization.Dab.shouldTickBrain(this)) { // Rolia - DAB (throttle goal-mob AI far from players)\n"
      + gated +
      "        } // Rolia - DAB end\n",
      "Mob.serverAiStep DAB gate", context="777ce9b1f1cea226")

# 3) villager lobotomization: skip the expensive brain tick for boxed-in villagers, but still restock
VILLAGER = "canvas-server/src/minecraft/java/net/minecraft/world/entity/npc/villager/Villager.java"
patch(VILLAGER,
      "        if (!inactive) this.getBrain().tick(level, this); // Paper - EAR 2\n",
      "        if (!inactive) io.rolia.optimization.Lobotomize.tickVillagerBrain(this, level); // Rolia - villager lobotomization (restock preserved)\n",
      "Villager.customServerAiStep lobotomize gate", context="6716fb424c613783")

# 4) (removed in build 38) SynchedEntityData.packDirty pre-sizing.
#    It sized the list to itemsById.length (~25-30 entries for a player) when packDirty returns the
#    DIRTY DELTA, which is typically 1-3 entries - and new ArrayList<>() allocates no backing array at
#    all until the first add. So it added a 25-30 slot Object[] allocation to every dirty sync of every
#    tracked entity to avoid a regrow that essentially never happened. Net GC pressure, no win.

# 5) BUILD 40 SEED MODEL: terrain is PUBLIC, everything else is SECRET.
#
#    Build 36-39 put the WHOLE of RandomState under the secret ("Seed V2"). That was worse than it
#    sounded: the secret was funnelled into a single 64-bit long, and two vanilla paths that consume it
#    - LegacyRandomSource for the legacy Nether biome noises, and EndIslandDensityFunction - keep only
#    48 bits of state. 48 bits is a routine seed-cracking workload, so the entire terrain layer was
#    recoverable in GPU-hours from observable landscape.
#
#    Build 40 splits it instead, which is both stronger and what the project actually wants:
#      PUBLIC (from the ordinary level-seed): terrain SHAPE - continentalness, erosion, ridge, offset,
#        jagged, BlendedNoise, End-island shape. Reproducible by anyone who knows the level seed.
#      SECRET (from the 1024-bit seed + salt, via a 128-bit Xoroshiro positional factory): everything
#        else - biome climate (temperature/vegetation, i.e. WHICH biome sits on a landform), caves, ore
#        veins, aquifers, surface rules.
#
#    The routing is a WHITELIST in Globals.isPublicTerrainNoise: anything not explicitly named public -
#    including any noise a future Minecraft version adds - falls to the secret side. Failing safe beats
#    failing convenient. Note the constructor's `seed` parameter is deliberately left alone, so the
#    public root and the End-island shape stay exactly vanilla.
RANDOMSTATE = "canvas-server/src/minecraft/java/net/minecraft/world/level/levelgen/RandomState.java"
patch(RANDOMSTATE,
      "    private final PositionalRandomFactory random;\n",
      "    private final PositionalRandomFactory random;\n"
      "    private final PositionalRandomFactory roliaSecretRandom; // Rolia - root of every SECRET worldgen system\n",
      "RandomState secret-root field", context="8b276daa6ade7608",
      hint="PositionalRandomFactory", marker="roliaSecretRandom")
patch(RANDOMSTATE,
      "        this.random = settings.getRandomSource().newInstance(seed).forkPositional();\n"
      "        this.noises = noises;\n"
      "        this.aquiferRandom = this.random.fromHashOf(Identifier.withDefaultNamespace(\"aquifer\")).forkPositional();\n"
      "        this.oreRandom = this.random.fromHashOf(Identifier.withDefaultNamespace(\"ore\")).forkPositional();\n",
      "        this.random = settings.getRandomSource().newInstance(seed).forkPositional(); // Rolia - PUBLIC: terrain shape stays on the level seed\n"
      "        // Rolia - build 44: each secret system names its OWN domain and gets an independent\n"
      "        // 128-bit root. They used to be fromHashOf() offsets of one shared \"worldgen-root\", and\n"
      "        // vanilla's positional factory is affine in its seed, so recovering the state of any one\n"
      "        // of them recovered all of them. secretOr(vanillaExpression, domain) returns the vanilla\n"
      "        // expression verbatim when secure-seed.enabled is false.\n"
      "        this.roliaSecretRandom = io.rolia.secureseed.Globals.secretOr(this.random, \"worldgen-root\"); // Rolia\n"
      "        this.noises = noises;\n"
      "        this.aquiferRandom = io.rolia.secureseed.Globals.secretOr(this.random.fromHashOf(Identifier.withDefaultNamespace(\"aquifer\")).forkPositional(), \"aquifer\"); // Rolia - SECRET, own root\n"
      "        this.oreRandom = io.rolia.secureseed.Globals.secretOr(this.random.fromHashOf(Identifier.withDefaultNamespace(\"ore\")).forkPositional(), \"ore\"); // Rolia - SECRET, own root\n",
      "RandomState aquifer+ore under the secret", context="aa03c4885be73c83",
      hint="aquiferRandom")
patch(RANDOMSTATE,
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), this.random);\n",
      "        // Rolia - SECRET, own root. This is the most exposed consumer of all: SurfaceSystem takes\n"
      "        // the factory DIRECTLY and draws the badlands terracotta banding from it - about 200 draws\n"
      "        // whose results you can read straight off the terrain. Sharing a root with every other\n"
      "        // noise made that a readable constraint on all of them.\n"
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), io.rolia.secureseed.Globals.secretOr(this.random, \"surface\")); // Rolia\n",
      "RandomState surface rules under the secret", context="20e682c2d81715c8",
      hint="surfaceSystem")
patch(RANDOMSTATE,
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises, this.random, noise));\n",
      "        // Rolia - route each noise to the public or the secret root. Whitelist: unknown noises are SECRET.\n"
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises,\n"
      "            io.rolia.secureseed.Globals.isPublicTerrainNoise(noise)\n"
      "                ? this.random\n"
      "                : io.rolia.secureseed.Globals.secretOr(this.roliaSecretRandom, \"noise:\" + noise.identifier()), noise));\n",
      "RandomState per-noise public/secret routing", context="374470dcfbee24e3",
      hint="noiseIntances")
patch(RANDOMSTATE,
      "        return this.positionalRandoms.computeIfAbsent(name, key -> this.random.fromHashOf(name).forkPositional());\n",
      "        // Rolia - same split for named factories; only BlendedNoise's \"terrain\" factory stays public.\n"
      "        return this.positionalRandoms.computeIfAbsent(name, key ->\n"
      "            io.rolia.secureseed.Globals.isPublicTerrainFactory(name)\n"
      "                ? this.random.fromHashOf(name).forkPositional()\n"
      "                : io.rolia.secureseed.Globals.secretOr(this.roliaSecretRandom.fromHashOf(name).forkPositional(), \"factory:\" + name));\n",
      "RandomState named-factory public/secret routing", context="7ecba93f08a2b3e2",
      hint="positionalRandoms")
# The two legacy Nether climate noises are SECRET, and must not go through LegacyRandomSource's 48-bit
# state. newLegacyInstance() itself is left alone because useLegacyInit also routes BlendedNoise (which
# is terrain, and public) through it.
patch(RANDOMSTATE,
      "                    NormalNoise newNoise = NormalNoise.createLegacyNetherBiome(this.newLegacyInstance(0L), noiseData.value());\n",
      "                    NormalNoise newNoise = NormalNoise.createLegacyNetherBiome(io.rolia.secureseed.Globals.isSecureSeedEnabled() ? io.rolia.secureseed.Globals.secretClimateSource(0L) : this.newLegacyInstance(0L), noiseData.value()); // Rolia - SECRET, full width (vanilla when secure-seed.enabled=false)\n",
      "RandomState nether temperature climate under the secret", context="5ea8cc55701c6fcb",
      hint="TEMPERATURE_NETHER")
patch(RANDOMSTATE,
      "                    NormalNoise newNoise = NormalNoise.createLegacyNetherBiome(this.newLegacyInstance(1L), noiseData.value());\n",
      "                    NormalNoise newNoise = NormalNoise.createLegacyNetherBiome(io.rolia.secureseed.Globals.isSecureSeedEnabled() ? io.rolia.secureseed.Globals.secretClimateSource(1L) : this.newLegacyInstance(1L), noiseData.value()); // Rolia - SECRET, full width (vanilla when secure-seed.enabled=false)\n",
      "RandomState nether vegetation climate under the secret", context="264930f2e13eb7f3",
      hint="VEGETATION_NETHER")

# 6) (removed in build 40) TamableAnimal.canTeleportTo unloaded-chunk guard.
#    It was a no-op. pos.below() is in the same chunk column as pos, and getPathTypeStatic(this, pos) -
#    one line earlier in the same method - already reads that chunk, so by the time the guarded read ran
#    the chunk was always loaded and the guard could never fire. The caller maybeTeleportTo already has
#    Folia's own getChunkIfLoaded(...) == null early-out, which is the check that actually matters.


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
      hint="WorldgenRandom", count=2, marker="io.rolia.secureseed.WorldgenCryptoRandom",
      # Build 44: 30 lines of context, not 12. applyCarvers constructs the random at its anchor but
      # re-seeds it 27 lines and three nested loops later, so a 12-line window did not contain the one
      # statement the whole hook depends on. Both occurrences are now checksummed (see _context_hash).
      context_lines=30, context=["f24baae1b478b3fa", "c0042a246a7f6d47"])

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
      "FriendlyByteBuf bulk writeLongArray + methods", context="358493d6b9e6563b")
patch(FBB,
      "        writeFixedSizeLongArray(this, longs);\n",
      "        writeFixedSizeLongArrayBulk(this, longs);\n",
      "FriendlyByteBuf bulk writeFixedSizeLongArray call", context="74f77d49a71f7fc7")


# 9) optimizations.ai.line-of-sight-interval (default 1 = Vanilla).
#    Sensing clears its seen/unseen sets EVERY tick, so every mob re-raycasts every target every
#    tick. Raycasting is the expensive part of a mob's sensing, and it is per mob per target, so on a
#    mob-dense server this is the largest single saving available. Above 1 a mob notices a target
#    appearing (or losing cover) up to N-1 ticks late, which is why the default is Vanilla.
#    Per-mob state, so this is Folia-safe by construction.
SENSING = "canvas-server/src/minecraft/java/net/minecraft/world/entity/ai/sensing/Sensing.java"
patch(SENSING,
      "    public void tick() {\n"
      "        this.seen.clear();\n"
      "        this.unseen.clear();\n"
      "    }\n",
      "    // Rolia start - optimizations.ai.line-of-sight-interval\n"
      "    private int roliaLineOfSightAge;\n"
      "\n"
      "    public void tick() {\n"
      "        final int roliaInterval = io.rolia.RoliaConfig.lineOfSightInterval();\n"
      "        if (roliaInterval > 1) {\n"
      "            if (++this.roliaLineOfSightAge < roliaInterval) {\n"
      "                return; // keep the cached results for another tick\n"
      "            }\n"
      "            this.roliaLineOfSightAge = 0;\n"
      "        }\n"
      "        this.seen.clear();\n"
      "        this.unseen.clear();\n"
      "    }\n"
      "    // Rolia end - optimizations.ai.line-of-sight-interval\n",
      "Sensing line-of-sight interval", context="65e60d08fc5e1944",
      hint="unseen", marker="roliaLineOfSightAge")

# 10) optimizations.ai.inactive-goal-selector-interval (default 3 = exactly what Paper does).
#     Paper's EAR 2 already runs the goal selector of INACTIVE mobs on a reduced schedule; this only
#     makes the divisor configurable. curRate is a per-selector field, so nothing is shared.
GOALSELECTOR = "canvas-server/src/minecraft/java/net/minecraft/world/entity/ai/goal/GoalSelector.java"
patch(GOALSELECTOR,
      "        return this.curRate % 3 == 0; // TODO newGoalRate was already unused in 1.20.4, check if this is correct\n",
      "        // Rolia - optimizations.ai.inactive-goal-selector-interval; 3 is Paper's own rate\n"
      "        final int roliaRate = io.rolia.RoliaConfig.inactiveGoalSelectorInterval();\n"
      "        return this.curRate % roliaRate == 0;\n",
      "GoalSelector inactive rate", context="dfbefef0e3fa4bce",
      hint="curRate", marker="roliaRate")

# 11) optimizations.collision.cache-shape-coords (default false).
#     getCoords allocates a fresh CubePointRange on every call, and collision queries are hot. A cube
#     shape's size is fixed at construction, so the list is the same value every time. The cache is
#     write-once and two threads racing can only compute the same value twice, so no lock is needed.
CUBESHAPE = "canvas-server/src/minecraft/java/net/minecraft/world/phys/shapes/CubeVoxelShape.java"
patch(CUBESHAPE,
      "    @Override\n"
      "    public DoubleList getCoords(final Direction.Axis axis) {\n"
      "        return new CubePointRange(this.shape.getSize(axis));\n"
      "    }\n",
      "    // Rolia start - optimizations.collision.cache-shape-coords\n"
      "    private DoubleList[] roliaCoordCache;\n"
      "\n"
      "    @Override\n"
      "    public DoubleList getCoords(final Direction.Axis axis) {\n"
      "        if (!io.rolia.RoliaConfig.cacheShapeCoords()) {\n"
      "            return new CubePointRange(this.shape.getSize(axis));\n"
      "        }\n"
      "        DoubleList[] cache = this.roliaCoordCache;\n"
      "        if (cache == null) {\n"
      "            cache = this.roliaCoordCache = new DoubleList[3]; // Direction.Axis has exactly three values\n"
      "        }\n"
      "        final int index = axis.ordinal();\n"
      "        DoubleList cached = cache[index];\n"
      "        if (cached == null) {\n"
      "            cached = cache[index] = new CubePointRange(this.shape.getSize(axis));\n"
      "        }\n"
      "        return cached;\n"
      "    }\n"
      "    // Rolia end - optimizations.collision.cache-shape-coords\n",
      "CubeVoxelShape coordinate cache", context="603eb6446725fad4",
      hint="CubePointRange", marker="roliaCoordCache")



if PRINT_HASHES:
    print("")
    print("=== context hashes (paste into the context= arguments above) ===")
    for what, h in _HASH_REPORT:
        print("  %-60s %s" % (what, h if isinstance(h, str) else ", ".join('"%s"' % x for x in h)))
    sys.exit(0)

print("Rolia source hooks applied.")
