# Build 46 — migration to Canvas 26.2

Working branch: `v26.2`. `main` still holds released build 45 and is untouched.

Base: `CraftCanvasMC/Canvas` **`main` @ `6390316`** — `mcVersion = 26.2`, `channel = STABLE`,
their build **#890**. There is no `ver/26.2` branch; stable 26.2 is `main`. Canvas dropped the Folia
upstream at this version and builds straight on Paper (`paperRef` replaced `foliaCommit`).

## What the rebase actually cost

Rolia carried edits to **69** Canvas files. In 26.2:

| | files |
|---|---|
| byte-identical — edit re-applies verbatim | 7 |
| Canvas changed it — re-apply by hand | 37 |
| moved to a new path — re-target | 9 |
| deleted | 16 |

The 16 deletions are the Folia region-threading layer that went with the upstream:
`AffinitySchedulerThreadPool`, `SchedulerUtil`, `WorldShutdownThread`, `EnderPearls`, `TeamData`,
`RegionThreadingWaypointManager`, `RegionThreadDumper`, `RootCommandTree`, `RegionTickCommand`,
`ReloadCommand`, `StructureTemplateOptimizer`, the `Connection` and `PacketEncoder` patches, both
`Fixup-Region-Threading` patches and `0003-Per-world-Canvas-configs`. About half of builds 44–45
lived there. None of it is carried forward: it repaired optional code that no longer exists, and
build 46 is a bare core by design.

## Done

- Tree rebased onto Canvas 26.2; `src/sources/java` is gone in 26.2 and no longer exists here.
- Stripped to a bare core: `Dab.java`, `Lobotomize.java`, every optimization hook, the `/rolia`
  command and the Luminol/Pufferfish/SparklyPaper licence files are deleted.
- `RoliaConfig` reduced from 28 options to **5**, all under `secure-seed`; 1234 → ~700 lines, no
  dangling references, braces balanced.
- **Biome climate is public.** `temperature` and `vegetation` join the public whitelist in
  `Globals.isPublicTerrainNoise`. The other four biome parameters were already public because
  terrain is built from them.
- `on-secret-mismatch` defaults to `block`.
- No automatic backups of the secret — `rolia.yml.bak.<ts>` writing removed, warning kept, atomic
  temp-file write kept.
- Nine of the ten seed patches apply to 26.2 **unchanged**. `GeodeFeature` needed rewriting
  (`GeodeConfiguration` became a record, so context lines gained parentheses, and the hunk moved
  from line 41 to 43). `Slime.java` moved to `monster/cubemob/` and its patch moved with it.
- `apply_dab_hooks.py` → **`apply_seed_hooks.py`**: eight worldgen hooks, optimization hooks
  deleted. Every anchor still exists in 26.2; seven of eight context checksums are unchanged; one
  moved (`f24baae1b478b3fa` → `cb26b5aec4877d1a`). Verified by running the finished script end to
  end against the real 26.2 sources.
- CI: build number 46, version strings, DAB step removed, hook step repointed, and the **biome
  assertion inverted** — biomes must now MATCH when the secret changes and differ across
  level-seeds, both directions asserted.
- README rewritten; `LAUNCH.md` and the Russian config reference match the five keys; launch
  scripts retuned from 4 GB to 32 GB.

## Remaining, in order

1. **Verify the `Slime` patch** against the second source dump (path
   `net/minecraft/world/entity/monster/cubemob/Slime.java`).
2. **Re-apply four seed-critical edits** to Canvas 26.2's own patch files. Only the seed parts —
   the parity and optimization parts of those same edits are deliberately dropped:
   - `ChunkGenerator.java.patch` — import `Globals`/`WorldgenCryptoRandom`; the decoration-seed
     `WorldgenCryptoRandom(origin.getX(), origin.getZ(), Salt.UNDEFINED, 0)`; the
     `Salt.BUKKIT_POPULATOR` site; the structure-set weighting sites.
   - `ServerChunkCache.java.patch` — `Globals.setupGlobals(level)` in `getGenerator()`.
     **Not** the spawn-chunk fairness shuffle.
   - `ServerLevel.java.patch` — `Globals.setupGlobals(this)`. **Not**
     `warnIfChunkSystemThreadsUnderconfigured` (deleted) and **not** the random-tick parity block.
   - `CraftChunk.java.patch` — `isSlimeChunk()` through `WorldgenCryptoRandom.seedSlimeChunk`.
3. **Rebrand**, on top of Canvas 26.2's `0001-Rebrand.patch` (they changed it by 1676 lines):
   brand name, banner, `/version`, F3, bStats, jar name. Keep `BRAND_CANVAS_ID` meaning Canvas and
   add `BRAND_ROLIA_ID`, as in build 45 — Rolia is a Canvas fork and plugins that detect Canvas
   should keep working.
4. **`GlobalConfiguration`** — the hook that loads `rolia.yml` at startup.
5. **CI details** — the reference-source dump list, and the config-contract step's expectations now
   that there are five keys rather than twenty-eight.
6. Re-enable `build.yml` on the `v26.2` branch, then iterate to green.

## Notes for whoever continues

- The dumped sources on the `srcdump` branch are **post-Canvas-patch**. Canvas's own patches will
  not re-apply to them, and should not be expected to. Only Rolia's patches are testable that way.
- Hook checksums must be computed **in execution order**, applying each substitution before hashing
  the next hook's window. Three of the eight differ between the pristine file and the real sequence.
- The Linux sandbox serves stale file sizes for this repo after a Windows-side write, so a grep
  there can read a truncated file and report "clean". Verify from the Windows side.
- `.github/workflows/sources.yml` is scaffolding. Delete it once build 46 is green.
