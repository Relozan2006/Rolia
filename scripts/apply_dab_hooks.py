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


def _context_hash(text, anchor):
    """Checksum the source around `anchor`. Returns (hash, window_text, first_line_number).

    Whitespace at end of line is stripped before hashing so a stray trailing space in the decompiler
    output cannot fail a build on its own.
    """
    idx = text.find(anchor)
    if idx < 0:
        return None, None, 0
    start_line = text.count("\n", 0, idx)
    end_line = start_line + anchor.count("\n")
    lines = text.split("\n")
    lo = max(0, start_line - CONTEXT_LINES)
    hi = min(len(lines), end_line + CONTEXT_LINES + 1)
    window = "\n".join(l.rstrip() for l in lines[lo:hi])
    return hashlib.sha256(window.encode("utf-8")).hexdigest()[:16], window, lo + 1

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


def patch(path, old, new, what, hint=None, count=1, marker=None, context=None):
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

    actual, window, firstline = _context_hash(s, old)
    if PRINT_HASHES:
        _HASH_REPORT.append((what, actual))
    elif context is not None and actual != context:
        print("ERROR: %s - the source AROUND the anchor changed." % what, file=sys.stderr)
        print("       expected context %s, found %s" % (context, actual), file=sys.stderr)
        print("       The anchor still matches, so the substitution would have been applied silently", file=sys.stderr)
        print("       into a method upstream has rewritten. Read the window below, decide whether the", file=sys.stderr)
        print("       hook is still correct, then update the context hash in scripts/apply_dab_hooks.py", file=sys.stderr)
        print("       (run it with --print-context-hashes to get the new values).", file=sys.stderr)
        print("       --- %s lines %d.. ---" % (path, firstline), file=sys.stderr)
        for i, line in enumerate(window.split("\n")):
            print("       %5d: %s" % (firstline + i, line), file=sys.stderr)
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

# 1) throttle brain sensors for distant mobs (villagers/piglins etc.)
patch(BRAIN,
      "    private void tickSensors(final ServerLevel level, final E body) {\n",
      "    private void tickSensors(final ServerLevel level, final E body) {\n"
      "        if (!io.rolia.optimization.Dab.shouldTickBrain(body)) return; // Rolia - DAB\n",
      "Brain.tickSensors DAB gate", context="6b3d0d2669203e48")

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
      "        this.roliaSecretRandom = io.rolia.secureseed.Globals.isSecureSeedEnabled() ? io.rolia.secureseed.Globals.secretPositionalFactory(\"worldgen-root\") : this.random; // Rolia - secure-seed.enabled=false routes every SECRET system back through the public root, i.e. vanilla\n"
      "        this.noises = noises;\n"
      "        this.aquiferRandom = this.roliaSecretRandom.fromHashOf(Identifier.withDefaultNamespace(\"aquifer\")).forkPositional(); // Rolia - SECRET\n"
      "        this.oreRandom = this.roliaSecretRandom.fromHashOf(Identifier.withDefaultNamespace(\"ore\")).forkPositional(); // Rolia - SECRET\n",
      "RandomState aquifer+ore under the secret", context="aa03c4885be73c83",
      hint="aquiferRandom")
patch(RANDOMSTATE,
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), this.random);\n",
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), this.roliaSecretRandom); // Rolia - SECRET: surface rules\n",
      "RandomState surface rules under the secret", context="f069d615b323915e",
      hint="surfaceSystem")
patch(RANDOMSTATE,
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises, this.random, noise));\n",
      "        // Rolia - route each noise to the public or the secret root. Whitelist: unknown noises are SECRET.\n"
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises,\n"
      "            io.rolia.secureseed.Globals.isPublicTerrainNoise(noise) ? this.random : this.roliaSecretRandom, noise));\n",
      "RandomState per-noise public/secret routing", context="374470dcfbee24e3",
      hint="noiseIntances")
patch(RANDOMSTATE,
      "        return this.positionalRandoms.computeIfAbsent(name, key -> this.random.fromHashOf(name).forkPositional());\n",
      "        // Rolia - same split for named factories; only BlendedNoise's \"terrain\" factory stays public.\n"
      "        return this.positionalRandoms.computeIfAbsent(name, key ->\n"
      "            (io.rolia.secureseed.Globals.isPublicTerrainFactory(name) ? this.random : this.roliaSecretRandom).fromHashOf(name).forkPositional());\n",
      "RandomState named-factory public/secret routing", context="7336d7bb05bd5c53",
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
      "NoiseBasedChunkGenerator carvers + mob spawn under the secret seed", context="e89063c52ddc2cec",
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
      "FriendlyByteBuf bulk writeLongArray + methods", context="358493d6b9e6563b")
patch(FBB,
      "        writeFixedSizeLongArray(this, longs);\n",
      "        writeFixedSizeLongArrayBulk(this, longs);\n",
      "FriendlyByteBuf bulk writeFixedSizeLongArray call", context="74f77d49a71f7fc7")


if PRINT_HASHES:
    print("")
    print("=== context hashes (paste into the context= arguments above) ===")
    for what, h in _HASH_REPORT:
        print("  %-60s %s" % (what, h))
    sys.exit(0)

print("Rolia source hooks applied.")
