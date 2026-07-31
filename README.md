<div align="center">

<img width="820" alt="Rolia" src="https://github.com/user-attachments/assets/938e7151-29c5-4efd-a05a-b6b979b87ebd" />

# Rolia

High-performance Minecraft server core built on Canvas: public terrain, everything worth finding under a 1024-bit secret

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-3fb950)
![Java](https://img.shields.io/badge/Java-25%2B-f89820)
![Base](https://img.shields.io/badge/base-Canvas%20%C2%B7%20Folia-3b82f6)
![Secure seed](https://img.shields.io/badge/secure%20seed-1024--bit-8b5cf6)
![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)

</div>

<!-- ROLIA_RULE_44 -->
> **Default rule (since build 43).** Stock Rolia behaves exactly like stock Canvas, plus the secure
> seed. Every behaviour change is a key in `rolia.yml`, and **every one of those keys defaults to
> `false`**. The only exception is `secure-seed.enabled`. Bug fixes are not keys and are always on.
>
> Full per-option documentation, kept in step with the code by CI (written in Russian):
> [`docs/rolia.yml.ru.md`](docs/rolia.yml.ru.md).

---

**Rolia** is a Minecraft **26.1.2** server core built on [Canvas](https://github.com/CraftCanvasMC) (a Folia fork) with cryptographically protected world generation.

As of build 40, generation is deliberately split in two: **the shape of the terrain is public and reproducible from the ordinary `level-seed`**, while everything worth finding — biomes, caves, ores, structures, dungeons, loot, slime chunks — is derived from a secret **1024-bit** seed and a secret salt. Knowing the `level-seed` gets you the landscape and nothing else.

## What is public and what is secret

| | Source | Exactly what |
| --- | --- | --- |
| **Public** | `level-seed` in `server.properties` | The **shape of the terrain only**: the `continentalness`, `erosion`, `ridge`, `offset` and `jagged` noises plus `BlendedNoise` — i.e. the heightmap and the outline of the landmasses and oceans. Plus the shape of the End islands. |
| **Secret** | a 1024-bit seed + a 64+ char salt from `rolia.yml` | Everything else: biome climate (`temperature`, `vegetation` — i.e. **which** biome sits on a given landform), caves, ravines, aquifers, ore veins, surface rules, structures, dungeons, decorations, loot, slime chunks. |

The routing is a **whitelist**: the public noises are named explicitly and everything else falls to the secret side, so a future Minecraft version that adds a new noise fails safe — the new noise is secret — instead of silently leaking.

## What that means in practice

- You can hand out your `level-seed`. Someone who has it can reproduce the **landscape** of your world — the mountains, the plains, the coastline. It will not find them a single structure, ore, cave or dungeon.
- Online seed finders and local world generators fed the public seed will show the right terrain and the **wrong** biomes, structures and ores.
- Two worlds with the same `level-seed` and different salts are the same landscape wearing different biomes, with completely different contents.

An honest caveat: terrain is **intentionally** not secret, and it does leak something. Some structures can only stand on suitable terrain (a monument needs deep ocean, a mineshaft needs land), so knowing the terrain narrows the search area. It does not hand over coordinates. And nothing protects against a player simply walking into a structure — this protects against computing the world offline, not against exploring it in game.

## Where the secret lives

The secret lives **only in `rolia.yml`** (created with `0600` permissions):

```yaml
secure-seed:
  salt: "…64+ characters…"      # the secret salt
```

- **`feature-level-seed` in `server.properties` has been removed** — the server no longer reads it.
- The secret is **not written to `level.dat`**. Only a one-way **fingerprint** is stored there, so that booting a world with the wrong `rolia.yml` is noticed instead of silently appending chunks generated from a different secret.
- `/seed` prints the ordinary seed and a **fingerprint** of the feature seed (`Feature seed fp:`) — 16 hex chars. The secret itself never reaches chat or `latest.log`; the fingerprint exists only so you can confirm the seed has not changed.

## Cryptography

Hashing is real **BLAKE2b in RFC 7693 keyed mode** — a MAC, with the salt as the key. It replaces the homemade `H(K xor M)` construction used by earlier builds. CI checks the implementation against the published BLAKE2b test vectors on every build, and separately asserts that what the server prints is the keyed digest rather than the unkeyed one or `BLAKE2b(key ‖ message)`.

The honest security bound:

- The strength comes from the **salt (64+ chars) and the 1024-bit seed**, not from the hash length. Lose the salt and you lose everything; leak the salt and there is no protection left.
- **Terrain is not secret and is not protected.** That is a decision, not an oversight: the world stays reproducible in shape, and there is nothing left to find by seed.
- BLAKE3 would be faster, not stronger; BLAKE2b's security is not in question.

## Performance

All the performance of Canvas and Folia: regionized multithreading, tick/chunk/entity optimizations. As of build 40 every Rolia-specific speedup is **off by default** — see the default rule at the top.

- **DAB** (Dynamic Activation of Brain), off by default (`optimizations.dab.enabled`) — mobs far from every player **think less often**. N grows with distance from 1 up to `max-tick-interval` (20 by default); mobs within `start-distance` (12 blocks by default) are never throttled. Spectators do not count, creative-mode players do. Two things are throttled, and build 45 changed how: a goal-driven mob (zombie, skeleton) runs its goal and target selectors once every N ticks instead of every tick, and a brain-driven mob (villager, piglin) has its **sensor schedule stretched** to `max(the sensor's own configured rate, N)`. Before build 45 the sensors were gated at the call site instead, which multiplied the configured rate by N rather than replacing it — at both defaults of 20, a distant villager rescanned every 400 ticks rather than every 20. DAB can now stretch a sensor that runs faster than N, and can never make one slower than the rate written in `paper-world-defaults.yml`.

  This is **not** behaviour-neutral: a throttled mob **reacts late** — it notices targets, repaths, flees and re-aims on a coarser clock, so distant mobs drift and converge differently than in Vanilla. Movement, physics, damage, despawning, mob caps and spawn rules are untouched, so farm *rates* are normally unaffected, but anything relying on precise distant pathfinding can change. Exempt specific types with `optimizations.dab.blacklist`, or leave DAB off.

- **Stuck-villager optimization** (lobotomize), off by default (`optimizations.villager-lobotomize.enabled`): a villager boxed into a 1×1 cell cannot path anywhere, so its whole brain tick is skipped. Trades and **restocking are preserved**, so pure trading halls behave like Vanilla.

  This is not behaviour-neutral either: skipping the brain skips every sensor and behaviour. A lobotomized villager does **not detect hostiles** (it will not flee or scream when a zombie arrives), does **not sleep**, does **not gossip**, does **not breed**, and does **not contribute to iron-golem spawning**. Leave it off if you run villager-based iron farms or breeders.

- **Faster chunk-data serialization** (`optimizations.faster-network.enabled`), off by default — bulk long-array writes. This is the one speedup that genuinely changes nothing observable: the bytes on the wire are identical, just produced faster.

- **Canvas's AFFINITY region scheduler** (`threaded-regions.scheduler: AFFINITY` in `config/paper-global.yml`) — work stealing plus keeping a region's tasks on their own tick thread. This one is Canvas's own setting, not a Rolia key, and it is worth turning on.

Rolia also fixes genuine defects inherited from Canvas — broken snow accumulation, the mob spawn distance gate, spawn-chunk fairness, a Folia cross-region safety guard, a null dereference on the spawn path, a file-descriptor leak, ender pearls lost on world unload, a projectile reading its X coordinate as Z, a weak-collection compaction test that could never fire, and thread-guard severities that did not match their own documentation. Fixes are always on and have no switch.

## Requirements

Java **25** or newer.

## Install and run

Download `rolia-paperclip-26.1.2.jar` from [Releases](../../releases/latest) and put it next to the launch script.

```bash
bash start.sh          # Linux / macOS
start.bat              # Windows
```

The scripts already carry the flags you want: `-Xms` = `-Xmx`, G1 with a pinned `-XX:ConcGCThreads`, `-XX:+AlwaysPreTouch`, `-XX:+PerfDisableSharedMem`, the Aikar set, and the two required flags `--add-modules=jdk.incubator.vector` (Canvas SIMD) and `--sun-misc-unsafe-memory-access=allow`.

The shipped values are sized for a 4–8 GB heap. On a **32 GB** host, edit the two variables at the top of the script:

```bash
MEM="32G"              # -Xms and -Xmx are both set from this
CONC_GC_THREADS=4      # -XX:ConcGCThreads
```

and raise the Aikar G1 values to their large-heap variants (`-XX:G1NewSizePercent=40`, `-XX:G1MaxNewSizePercent=50`, `-XX:G1HeapRegionSize=16M`, `-XX:G1ReservePercent=15`, `-XX:InitiatingHeapOccupancyPercent=20`), which is what Aikar's tuning prescribes above 12 GB. Every flag is explained in [LAUNCH.md](LAUNCH.md).

## First run checklist

The JVM flags are only half the job. Do this once, then restart.

1. Java **25+**.
2. Start the server once so it writes its configs, then stop it.
3. **`config/paper-global.yml` → `chunk-system`**: set `worker-threads` and `io-threads` explicitly. Paper ships `-1` for both, and `-1` does **not** mean "use all cores": `io-threads` is resolved as `max(1, configured)`, so `-1` means **exactly one** I/O thread on every machine, and `worker-threads` auto-resolves to **one** thread on any box with 7 or fewer cores. It shows up as chunk-load stalls rather than high MSPT, which is why almost nobody finds it. Rolia warns about it at startup.
4. **`config/paper-global.yml` → `threaded-regions.scheduler: AFFINITY`** — work stealing plus keeping a region's tasks on their own tick thread (needs ≥2 cores). It is also the only scheduler the Canvas region profiler supports.
5. **`server.properties` → `level-seed`** — the public seed; it controls **only the shape of the terrain**. You may hand it out.
6. **Back up `rolia.yml` together with your world** — see the section below.
7. Decide whether you want `optimizations.dab` or `optimizations.villager-lobotomize` at all. Both are off by default and both change mob behaviour — see "Performance".

| CPU cores | `worker-threads` | `io-threads` |
| --- | --- | --- |
| 4 | 2 | 2 |
| 8 | 3 | 2 |
| 16 | 6 | 3 |
| 32 | 8 | 4 |

```yaml
# config/paper-global.yml
chunk-system:
  worker-threads: 3    # example for 8 cores
  io-threads: 2
threaded-regions:
  scheduler: AFFINITY
```

These are deliberately **below** the core count. Folia also needs cores for its region tick threads, and G1 needs `-XX:ConcGCThreads` (set in the launch script) — all three budgets share the same CPUs.

## Configuration

`rolia.yml` is generated on first run and documents every option inline. The headline keys:

| Option | File | Purpose |
| --- | --- | --- |
| `level-seed` | `server.properties` | The public seed. Determines **the shape of the terrain only**. |
| `secure-seed.enabled` | `rolia.yml` | Master switch, and the only key in the file that defaults to `true`. Set it to `false` and worldgen is plain Vanilla. |
| `secure-seed.salt` | `rolia.yml` | The secret salt (64+ chars). Created automatically with `0600` permissions. |
| `secure-seed.feature-seed` | `rolia.yml` | The 1024-bit secret, as a decimal integer. Created automatically. |
| `secure-seed.on-secret-mismatch` | `rolia.yml` | What to do when `level.dat`'s fingerprint disagrees with `rolia.yml`. `warn` by default; `block` refuses to start. |
| `optimizations.dab` | `rolia.yml` | DAB: `enabled` (`false` by default), `start-distance`, `max-tick-interval`, `blacklist`. |
| `optimizations.villager-lobotomize` | `rolia.yml` | Stuck-villager lobotomization: `enabled` (`false` by default), `wait-until-trade-locked`, `check-interval`. |
| `optimizations.faster-network.enabled` | `rolia.yml` | Faster chunk-data serialization (`false` by default). |
| `chunk-system.worker-threads` / `io-threads` | `config/paper-global.yml` | Chunk-system thread pools. **Set these explicitly** — see the checklist above. |
| `threaded-regions.scheduler` | `config/paper-global.yml` | Region scheduler. `AFFINITY` recommended. |

`/rolia status` prints the live values, and `/rolia reload` re-reads the file (keys marked as restart-only keep their startup values until the next boot). Secrets are never printed by either.

Note: slime chunks are driven by the secret seed, so the `slime-seed` option from `spigot.yml` has no effect on Rolia.

## Important: back up `rolia.yml`

`rolia.yml` holds the secret salt and the 1024-bit seed. **Everything except the shape of the terrain depends on them.** If the file is lost, every newly generated chunk in an existing world gets different biomes, caves, ores and structures — you will see a hard seam at the edge of explored territory, and it cannot be repaired. Keep the file secret and backed up **together with your world**.

The server logs the absolute path of the `rolia.yml` it actually used (`Rolia: using config …`) — check it if you start the server from a directory other than the world folder. If the file exists but cannot be parsed, the server **refuses to start**: silently generating a new salt would rewrite the world.

## Upgrading from build 39

- Remove `feature-level-seed` from `server.properties` — the property is gone and is no longer read. Your 1024-bit seed belongs in `rolia.yml`.
- **The terrain of an existing world will change.** In build 39 terrain was derived from the secret too; from build 40 on it comes from `level-seed` alone. Seams are unavoidable, so build 40 and later are meant for a fresh world.
- The salt carries over unchanged: the structures, ores and biomes tied to it stay where they are.

## Building from source

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is produced in `canvas-server/build/libs/`.

## Built on

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · secure-seed idea — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · the "public terrain, secret features" model — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

License: [GPL-3.0](LICENSE).
