#!/usr/bin/env python3
"""Rolia - build-time source hooks for the secret worldgen layer.

Some of what Rolia needs to change lives in methods Canvas does not patch at all, so there is no
per-file patch to extend. These hooks edit the decompiled sources directly, after paperweight has
applied every Canvas and Rolia patch and before the compiler runs.

String substitution into generated code is a sharp tool, so each hook carries a checksum of the
source AROUND its anchor. If upstream moves the code, changes a neighbouring line, or renames
something the hook depends on, the build fails and says which hook and which occurrence - rather
than substituting into text that no longer means what it meant.

Build 46 note. This file used to be apply_dab_hooks.py and carried the optimization hooks too - DAB,
villager lobotomization, faster network writes, line-of-sight caching, collision-shape caching. Those
options are gone from a bare core, so their hooks are gone with them and the name no longer fits.

What remains is eleven hooks over twelve checksummed occurrences (one hook matches twice): six that
route worldgen through the secret, and five that were moved here from Canvas's own per-file patches
when Rolia rebased onto 26.2 - see the build-46 section further down for why those five could not
stay as patch hunks.

Two more were removed outright in build 46: the legacy Nether climate pair. Build 46 makes the biome
map public, and that has to hold in all three dimensions or the claim is not true.

Porting 26.1.2 -> 26.2 cost almost nothing for the worldgen hooks, which was worth measuring rather
than assuming: every anchor still existed with exactly the expected number of occurrences, and seven
of the then-eight checksums were unchanged, because RandomState.java is identical in every region
they touch. Exactly one moved - the first of the two NoiseBasedChunkGenerator sites,
f24baae1b478b3fa -> cb26b5aec4877d1a.
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
        # Rolia - build 46: never skip during a hash-printing run. Regenerating the checksums against a
        # tree where the hooks are already applied is the natural thing to reach for after a failure,
        # and skipping would have printed a confident, silently incomplete list instead of failing.
        if PRINT_HASHES:
            print("ERROR: %s is already applied in %s." % (what, path), file=sys.stderr)
            print("       --print-context-hashes must run against a CLEAN tree, in execution order,",
                  file=sys.stderr)
            print("       or the hashes it prints do not describe the windows the hooks will see.",
                  file=sys.stderr)
            sys.exit(1)
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
            print("       hook is still correct, then update the context hash in scripts/apply_seed_hooks.py", file=sys.stderr)
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
      hint="aquiferRandom", marker="secretOr(this.random, \"worldgen-root\")")
patch(RANDOMSTATE,
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), this.random);\n",
      "        // Rolia - SECRET, own root. This is the most exposed consumer of all: SurfaceSystem takes\n"
      "        // the factory DIRECTLY and draws the badlands terracotta banding from it - about 200 draws\n"
      "        // whose results you can read straight off the terrain. Sharing a root with every other\n"
      "        // noise made that a readable constraint on all of them.\n"
      "        this.surfaceSystem = new SurfaceSystem(this, settings.defaultBlock(), settings.seaLevel(), io.rolia.secureseed.Globals.secretOr(this.random, \"surface\")); // Rolia\n",
      "RandomState surface rules under the secret", context="20e682c2d81715c8",
      hint="surfaceSystem", marker="secretOr(this.random, \"surface\")")
patch(RANDOMSTATE,
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises, this.random, noise));\n",
      "        // Rolia - route each noise to the public or the secret root. Whitelist: unknown noises are SECRET.\n"
      "        return this.noiseIntances.computeIfAbsent(noise, key -> Noises.instantiate(this.noises,\n"
      "            io.rolia.secureseed.Globals.isPublicTerrainNoise(noise)\n"
      "                ? this.random\n"
      "                : io.rolia.secureseed.Globals.secretOr(this.roliaSecretRandom, \"noise:\" + noise.identifier()), noise));\n",
      "RandomState per-noise public/secret routing", context="374470dcfbee24e3",
      hint="noiseIntances", marker="isPublicTerrainNoise(noise)")
patch(RANDOMSTATE,
      "        return this.positionalRandoms.computeIfAbsent(name, key -> this.random.fromHashOf(name).forkPositional());\n",
      "        // Rolia - same split for named factories; only BlendedNoise's \"terrain\" factory stays public.\n"
      "        return this.positionalRandoms.computeIfAbsent(name, key ->\n"
      "            io.rolia.secureseed.Globals.isPublicTerrainFactory(name)\n"
      "                ? this.random.fromHashOf(name).forkPositional()\n"
      "                : io.rolia.secureseed.Globals.secretOr(this.roliaSecretRandom.fromHashOf(name).forkPositional(), \"factory:\" + name));\n",
      "RandomState named-factory public/secret routing", context="7ecba93f08a2b3e2",
      hint="positionalRandoms", marker="isPublicTerrainFactory(name)")
# Rolia - build 46: the two legacy Nether climate hooks were REMOVED here, not moved.
#
# Builds 40-45 routed TEMPERATURE_NETHER and VEGETATION_NETHER through the secret, because biome
# climate was secret everywhere. Build 46 made the biome map public - and it has to be public in all
# three dimensions or the claim is not true. Leaving these two would have meant a seed-finding site
# showing the right Overworld biomes and the wrong Nether ones, with nothing in the CI biome gate to
# catch it, because that gate only samples the Overworld.
#
# Removing them is safe for the checksums of the hooks above: those all run BEFORE these did, so
# their context windows were hashed against text these substitutions had not yet touched. The hooks
# below are in other files entirely.
#
# newLegacyInstance() itself was already left alone, because useLegacyInit also routes BlendedNoise -
# which is terrain, and public - through it. So dropping these two hooks leaves both Nether climate
# noises on vanilla's own construction, which is exactly what "the biome map is public" means.

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
      context_lines=30, context=["cb26b5aec4877d1a", "c0042a246a7f6d47"])

# 7) Bulk writeLongArray (from Leaf) - byte-identical output (uses source.order()), faster chunk serialization.
#    Build 38: the nio branch is now `nioBufferCount() == 1 && !isReadOnly()`, not `> 0`.
#    Netty's contract for nioBuffer(int,int) is "share OR CONTAIN A COPY OF" the buffer's content.
#    For a CompositeByteBuf whose components straddle the requested range, nioBufferCount() returns
#    >= 2 (so the old `> 0` guard passed) but nioBuffer() hands back a throwaway heap ByteBuffer:
#    the longs are written into that copy and discarded, and writerIndex is then advanced over
#    uninitialised buffer memory - i.e. hundreds of longs of garbage in a chunk packet. Composites
#    arrive via ViaVersion and any future packet batching. The unreachable heap-staging branch was
#    also dropped in favour of the plain loop, which is correct for every ByteBuf implementation.


# =================================================================================================
# Rolia - build 46: four edits that used to live inside Canvas's own per-file patches.
#
# They are hooks now, and the reason is mechanical rather than stylistic. A per-file patch numbers
# its hunks against the ORIGINAL decompiled file, but the only 26.2 sources available to write
# against are the ones a build produces - which are post-patch. Writing these as patch hunks would
# have meant guessing the pre-patch line numbers, pushing, and reading the rejection. As hooks they
# anchor on text that exists at exactly the moment they run, and they carry the same context
# checksum every other hook here does.
#
# Only the SEED parts are carried over. The same Canvas patches also held Rolia's spawn-chunk
# fairness shuffle, the random-tick parity block and the chunk-thread startup advisory; those were
# optional behaviour, they are not part of a bare core, and they are deliberately left behind.
# =================================================================================================

SCC = "canvas-server/src/minecraft/java/net/minecraft/server/level/ServerChunkCache.java"
# The generator is where worldgen begins, so this is where the secret has to be published.
#
# setupGlobals is NOT a no-op after the first call, and this hook has to be on getGenerator() for
# exactly that reason: the publication happens once behind a volatile, but every call also sets the
# worldgen dimension ThreadLocal for the calling thread. That is what makes off-worldgen paths such
# as /locate and treasure-map lookups read the right dimension - they reach the generator through
# this accessor first.
patch(SCC,
      "    public ChunkGenerator getGenerator() {\n",
      "    public ChunkGenerator getGenerator() {\n"
      "        io.rolia.secureseed.Globals.setupGlobals(level); // Rolia - publish the secret before any generation runs\n",
      "ServerChunkCache publishes the secret", context="bb5944b3443ce376",
      hint="getGenerator", marker="io.rolia.secureseed.Globals.setupGlobals(level)")

SL = "canvas-server/src/minecraft/java/net/minecraft/server/level/ServerLevel.java"
# ...and again as the level is constructed, because a world can be generated from paths that do not
# go through getGenerator() first. Same publish-once call.
patch(SL,
      "            generator = new org.bukkit.craftbukkit.generator.CustomChunkGenerator(this, generator, gen);\n"
      "        }\n"
      "        // CraftBukkit end\n",
      "            generator = new org.bukkit.craftbukkit.generator.CustomChunkGenerator(this, generator, gen);\n"
      "        }\n"
      "        // CraftBukkit end\n"
      "        io.rolia.secureseed.Globals.setupGlobals(this); // Rolia - publish the secret at level construction\n",
      "ServerLevel publishes the secret", context="0c2cca763b6fff6e",
      hint="CustomChunkGenerator", marker="io.rolia.secureseed.Globals.setupGlobals(this)")

CG = "canvas-server/src/minecraft/java/net/minecraft/world/level/chunk/ChunkGenerator.java"
# The decoration seed drives every structure and feature placement in the chunk. Vanilla keys it
# from the public level seed; under Rolia it comes from the secret, which is the difference between
# "a seed map shows you where the villages are" and "it does not".
#
# The ternary is not decoration. WorldgenCryptoRandom's switched-off path wraps a LegacyRandomSource,
# and this is the ONE call site in the fork where vanilla wraps Xoroshiro instead. setDecorationSeed
# draws two nextLong() values from whatever is wrapped and builds the population seed out of them, so
# substituting the class unconditionally changed the population seed - and therefore every feature in
# every chunk - on a server that had switched the secret OFF. That silently falsified the promise made
# in rolia.yml and in the README, that `secure-seed.enabled: false` yields an ordinary Minecraft world
# you can move to any Paper or Folia server. Branching keeps the disabled path byte-identical to the
# line it replaced.
patch(CG,
      "            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));\n",
      "            WorldgenRandom random = io.rolia.secureseed.Globals.isSecureSeedEnabled() ? new io.rolia.secureseed.WorldgenCryptoRandom(origin.getX(), origin.getZ(), io.rolia.secureseed.Globals.Salt.UNDEFINED, 0) : new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed())); // Rolia - decoration seed under the secret, exactly vanilla when it is off\n",
      "ChunkGenerator decoration seed under the secret", context="6d7425f2032c038e",
      hint="generateUniqueSeed", marker="WorldgenCryptoRandom")
# Bukkit's BlockPopulator API gets its own salt domain. UNDEFINED collided exactly with a datapack
# structure set configured with salt: 0, which is a real configuration people write.
#
# Branched for the same reason as the hook above, plus one of its own: the switched-off path inside
# WorldgenCryptoRandom seeds from Globals.levelSeed(), which is captured once from the FIRST world the
# server opens. On a multiworld server that is the wrong seed for every other world. Here the level is
# in scope, so the disabled path can just be the line it replaced.
patch(CG,
      "                WorldgenRandom seededrandom = new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(level.getSeed()));\n",
      "                WorldgenRandom seededrandom = io.rolia.secureseed.Globals.isSecureSeedEnabled() ? new io.rolia.secureseed.WorldgenCryptoRandom(x, z, io.rolia.secureseed.Globals.Salt.BUKKIT_POPULATOR, 0) : new WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(level.getSeed())); // Rolia - own salt domain, exactly vanilla when the secret is off\n",
      "ChunkGenerator Bukkit populator under the secret", context="68f800ff45a58640",
      hint="seededrandom", marker="Salt.BUKKIT_POPULATOR")

# CraftChunk lives in the Paper tree rather than the Minecraft one, and which of the two it ends up
# in depends on the build layout, so resolve it instead of asserting a path.
import glob as _glob
_cc = ([p for p in _glob.glob("canvas-server/src/main/java/org/bukkit/craftbukkit/CraftChunk.java")]
       + [p for p in _glob.glob("paper-server/src/main/java/org/bukkit/craftbukkit/CraftChunk.java")])
if not _cc:
    print("ERROR: CraftChunk.java not found in either tree", file=sys.stderr); sys.exit(1)
# The Bukkit API must agree with where slimes actually spawn. Computed straight from the
# coordinates, never through getHandle(), which would force-load the chunk from another region.
patch(_cc[0],
      "        return this.level.paperConfig().entities.spawning.allChunksAreSlimeChunks || WorldgenRandom.seedSlimeChunk(this.getX(), this.getZ(), this.getWorld().getSeed(), level.spigotConfig.slimeSeed).nextInt(10) == 0; // Paper\n",
      "        return this.level.paperConfig().entities.spawning.allChunksAreSlimeChunks || io.rolia.secureseed.WorldgenCryptoRandom.seedSlimeChunk(io.rolia.secureseed.Globals.stableDimensionId(this.level), this.getX(), this.getZ(), this.getWorld().getSeed(), level.spigotConfig.slimeSeed).nextInt(10) == 0; // Paper // Rolia - the API must agree with where slimes really spawn\n",
      "CraftChunk isSlimeChunk under the secret", context="e9a14ff67a9a40d4",
      hint="isSlimeChunk", marker="WorldgenCryptoRandom.seedSlimeChunk")

print("Rolia: worldgen source hooks applied")

# Rolia - build 46: actually print what --print-context-hashes collected.
#
# _HASH_REPORT was appended to and never read, so the flag documented as "the way to regenerate the
# checksums" emitted nothing at all, and the OK line above still echoed the DECLARED hash rather than
# the computed one. Every hash in this file therefore had to be harvested one at a time out of failure
# messages - which is also why the ordering guarantee this file rests on was never actually exercised.
#
# Note the substitutions are still written to disk during such a run, and must be: each hook's window
# includes text an earlier hook rewrote, so hashes are only correct when computed in execution order,
# against a tree where the earlier substitutions have already happened. Run it on a throwaway checkout.
if PRINT_HASHES:
    print("")
    print("=== context hashes, in execution order ===")
    for what, value in _HASH_REPORT:
        if isinstance(value, list):
            print('    context=%r,  # %s' % (value, what))
        else:
            print('    context="%s",  # %s' % (value, what))
    print("=== %d hook(s); the tree was MODIFIED, do not build from it ===" % len(_HASH_REPORT))
