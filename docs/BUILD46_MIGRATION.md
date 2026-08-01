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
- `apply_dab_hooks.py` → **`apply_seed_hooks.py`**: the optimization hooks are deleted. What remains
  is **eleven hooks over twelve checksummed occurrences** — six worldgen hooks, plus five carried
  over from Canvas's own per-file patches. Two of the original eight were dropped: the legacy Nether
  climate pair, because build 46 makes the biome map public and that has to hold in all three
  dimensions. Every anchor still exists in 26.2; seven of the then-eight checksums are unchanged; one
  moved (`f24baae1b478b3fa` → `cb26b5aec4877d1a`). Verified by running the finished script end to end
  against the real 26.2 sources.
- CI: build number 46, version strings, DAB step removed, hook step repointed, and the **biome
  assertion inverted** — biomes must now MATCH when the secret changes and differ across
  level-seeds, both directions asserted.
- README rewritten; `LAUNCH.md` and the Russian config reference match the five keys; launch
  scripts retuned from 4 GB to 32 GB.

## Done since

Items 1–5 of the original plan are complete: the `Slime` patch is verified against the real dump,
the five seed-critical edits are carried as hooks rather than patch hunks (a patch numbers its hunks
against the *pre*-patch file, and the only 26.2 sources available to write against are post-patch),
the rebrand is applied on top of Canvas 26.2's own, and the CI config-contract step expects five
keys.

CI rounds so far, and what each actually failed on — worth recording, because three of them were
spent on the wrong step:

1. Branding gate — the jar manifest still said Canvas. Fixed in `build.gradle.kts.patch`.
2. Terrain gate — land mask 96 against a floor of 97; three bounds recalibrated for 26.2.
3. and 4. Diagnosed as the config-contract step, which was wrong. The job was dying one step
   earlier, in the auto-random seed test: it asserts the fragment `; DAB off` on the startup line,
   and build 46 deleted DAB along with that fragment, so the grep could never match. The tell was
   `auto_b.log` ending mid-shutdown seven seconds before the publish step ran. The replacement
   anchors the END of the line instead of naming DAB, which is stronger: it fails if any future
   option appends itself there.

## Remaining

1. Push the review fixes and iterate `v26.2` to green.
2. Delete `.github/workflows/sources.yml` — it is scaffolding.
3. Merge `v26.2` into `main`, then a `[release]` commit for `v26.2-build.46`.

## Notes for whoever continues

- The dumped sources on the `srcdump` branch are **post-Canvas-patch**. Canvas's own patches will
  not re-apply to them, and should not be expected to. Only Rolia's patches are testable that way.
- Hook checksums must be computed **in execution order**, applying each substitution before hashing
  the next hook's window. Three of the original eight differ between the pristine file and the real
  sequence. `--print-context-hashes` does that correctly and now actually prints the result — until
  build 46 it collected the values into a list nothing ever read, which is why every hash in the file
  had to be harvested one at a time out of failure messages.
- The Linux sandbox serves stale file sizes for this repo after a Windows-side write, so a grep
  there can read a truncated file and report "clean". Verify from the Windows side.
- `.github/workflows/sources.yml` is scaffolding. Delete it once build 46 is green.
