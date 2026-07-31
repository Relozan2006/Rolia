#!/usr/bin/env python3
"""Rolia - build-time source hooks for the secret worldgen layer.

Some of what Rolia needs to change lives in methods Canvas does not patch at all, so there is no
per-file patch to extend. These hooks edit the decompiled sources directly, after paperweight has
applied every Canvas and Rolia patch and before the compiler runs.

String substitution into generated code is a sharp tool, so each hook carries a checksum of the
source AROUND its anchor. If upstream moves the code, changes a neighbouring line, or renames
something the hook depends on, the build fails and says which hook and which occurrence - rather
than substituting into text that no longer means what it meant.

Build 46 note. This file used to be apply_dab_hooks.py and carried sixteen hooks: eight for the
secret seed and eight for optimizations - DAB, villager lobotomization, faster network writes,
line-of-sight caching, collision-shape caching. Those optimizations are gone, so their hooks are
gone with them, and the name no longer fits. What remains is the eight that make worldgen secret.

Porting 26.1.2 -> 26.2 cost almost nothing here, which was worth measuring rather than assuming:
every anchor still exists with exactly the expected number of occurrences, and seven of the eight
context checksums are unchanged, because RandomState.java is identical in every region these hooks
touch. Exactly one moved - the first of the two NoiseBasedChunkGenerator sites,
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

print("Rolia: worldgen source hooks applied")
