# Rolia

**Rolia is Canvas with a secret world seed.**

That sentence is the whole design. Rolia takes [Canvas](https://github.com/CraftCanvasMC/Canvas)
26.2 unchanged — every optimization, every region-threading fix, every default — and adds one thing:
a 1024-bit secret that decides where the caves, ore, structures and loot are, while the landscape and
the biome map stay reproducible from the ordinary `level-seed`.

Nothing else is different. There are no Rolia performance options, no Rolia gameplay changes, and no
Rolia defaults that differ from Canvas. If you know Canvas, you know Rolia.

---

## Why

A Minecraft world seed is a 64-bit number, and 64 bits is nothing. Given a handful of observations —
a few chunk shapes, a village, a couple of biome borders — a seed cracker recovers it in seconds on a
laptop. Once it is recovered, every player has an X-ray of your server: every diamond vein, every
ancient city, every stronghold, every slime chunk, forever.

Rolia's answer is not to hide the seed harder. It is to split it.

| | Public — from `level-seed` | Secret — from the 1024-bit key |
|---|---|---|
| Landscape shape | ✅ | |
| Biome map | ✅ | |
| Surface rules (banding, beaches, snow) | | ✅ |
| Trees, plants, decorations | | ✅ |
| Caves and ravines | | ✅ |
| Aquifers | | ✅ |
| Ore veins | | ✅ |
| Structures and their loot | | ✅ |
| Slime chunks | | ✅ |
| End spike layout, stronghold rings | | ✅ |

So a player can still take your `level-seed` to a seed-finding site, see the same mountains and the
same jungle, and pick where to build. What the map cannot tell them is where anything **is**. The
1024-bit key is not guessable — not with a laptop, not with a datacentre, not ever.

This is Rolia's own implementation of the idea behind
[SecureSeed](https://github.com/Earthcomputer/SecureSeed) by Earthcomputer, rebuilt for Canvas 26.2
and for region threading.

### The honest limits

- **Biome borders leak more than they used to.** Because the biome map is public by design, anything
  that follows strictly from biome — which biomes a structure *could* be in, roughly where a mushroom
  island sits — is inferable. What is not inferable is the actual placement roll.
- **The world spawn point becomes predictable**, because spawn selection is biome-driven.
- **Caves break the surface.** A cave mouth or ravine that cuts the ground is visible, and it came
  from the secret. Terrain reproduced from the public seed therefore matches to about 70% of columns
  exactly, with a median difference of zero — not 100%.
- **A plugin you install can read the secret.** Any plugin runs inside the server JVM and can reach
  any field by reflection. Rolia does not pretend otherwise. Your plugins are inside your trust
  boundary, and always were.

---

## Getting started

1. Download `rolia-paperclip-26.2.jar` from the
   [Releases](https://github.com/Relozan2006/Rolia/releases) page.
2. You need **Java 25 or newer**. Check with `java -version`.
3. Start it once. Rolia writes `rolia.yml` next to the jar, with permissions `0600`, containing a
   freshly generated salt and 1024-bit feature seed.
4. **Back up `rolia.yml` together with your world.**

`start.sh` and `start.bat` ship with the recommended flags already set.

### Read this before your first world

`rolia.yml` holds the only copy of the secret. Rolia does not write backup copies of it — earlier
builds did, and all that achieved was scattering readable copies of the key around the server
directory and into every support archive. Keeping it safe is yours to do.

Lose the file and the world is not recoverable. The chunks already on disk stay as they are, but
every chunk generated afterwards will have different caves, ore and structures, with a hard seam
between old and new. There is no repair. Put it in the same backup job as your world folder.

Rolia keeps a one-way fingerprint of the secret in `level.dat`. It cannot be reversed into the key,
and it exists so the server can tell whether the world in front of it belongs to the secret it is
holding. If they disagree, the server refuses to start — see `secure-seed.on-secret-mismatch`.

---

## Configuration

`rolia.yml` has five keys. That is all of them.

```yaml
secure-seed:
  enabled: true
  salt: "<generated on first run>"
  feature-seed: "<generated on first run>"
  on-secret-mismatch: block
  seed-command: fingerprint
```

**`enabled`** — the master switch, and the only Rolia option that defaults to `true`. Set it to
`false` and worldgen is plain Vanilla: everything derives from the ordinary `level-seed`, the secret
is ignored, and the world is an ordinary Minecraft world you can move to any server. Do not flip this
on a world that already exists.

**`salt`** and **`feature-seed`** — the two halves of the key, generated with `SecureRandom` on first
run. Never share them, never paste them into a bug report.

**`on-secret-mismatch`** — what happens when the secret does not match the world on disk: `block`
(default, refuse to start), `warn` (start anyway, very loudly), `ignore`. The default is `block`
because a server that will not start is an inconvenience, while a world quietly generating against
the wrong secret cannot be repaired. On a new world nothing is stored yet, so it can only fire on a
real mismatch.

**`seed-command`** — what `/seed` shows: `fingerprint` (the public `level-seed` plus a one-way
fingerprint of the secret, the default), `hidden` (the `level-seed` only), or `vanilla`.

Everything else lives where Canvas and Paper put it: `config/canvas-server.yml`,
`config/canvas-worlds.yml`, `config/paper-global.yml`, `config/paper-world-defaults.yml`. Rolia does
not duplicate, override or shadow any of them.

---

## Recommended launch flags

For a **32 GB** server on Java 25. These are Aikar's G1 flags at their large-heap settings, plus the
one flag Canvas asks for.

```bash
java -Xms32G -Xmx32G \
  --add-modules=jdk.incubator.vector \
  -XX:+UseG1GC \
  -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -XX:G1NewSizePercent=40 \
  -XX:G1MaxNewSizePercent=50 \
  -XX:G1HeapRegionSize=16M \
  -XX:G1ReservePercent=15 \
  -XX:G1HeapWastePercent=5 \
  -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=20 \
  -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 \
  -XX:SurvivorRatio=32 \
  -XX:+PerfDisableSharedMem \
  -XX:MaxTenuringThreshold=1 \
  -Dusing.aikars.flags=https://mcflags.emc.gs \
  -Daikars.new.flags=true \
  -jar rolia-paperclip-26.2.jar --nogui
```

Notes that actually matter:

- **`-Xms` equal to `-Xmx`.** With `AlwaysPreTouch` the JVM commits the whole heap at startup. It
  makes boot a few seconds slower and removes a whole class of pause.
- **Do not give the JVM all the machine's RAM.** 32 GB of heap wants a machine with 40 GB or more;
  the JVM needs room outside the heap and the OS needs page cache for your region files.
- **`--add-modules=jdk.incubator.vector`** must come *before* `-jar`. Without it Canvas logs a
  warning at startup and its SIMD paths stay off.
- **Check `config/paper-global.yml` → `chunk-system` before tuning anything else.** Paper ships
  `worker-threads: -1` and `io-threads: -1`, and `-1` does not mean "use every core": `io-threads`
  resolves to exactly one thread on every machine, and `worker-threads` resolves to one on any box
  with 7 or fewer cores. On a 16-core host, `worker-threads: 6` and `io-threads: 3` is worth more
  than every flag above put together.
- **Generational ZGC** (`-XX:+UseZGC -XX:+ZGenerational`) is a reasonable alternative at this heap
  size if you care about tail latency more than throughput. It is not the default recommendation
  because G1 with these flags is what the Minecraft server community has actually tested at scale.

---

## What Rolia changes, in full

Rolia is a fork of Canvas. This is the complete list of differences from stock Canvas 26.2 — if
something is not on this list, Rolia does not touch it.

### 1. The secret worldgen layer

- A **1024-bit key**, stored only in `rolia.yml`, as a salt plus a feature seed. It is never written
  to `server.properties`, never written to `level.dat`, and never printed by any command.
- **Per-domain key derivation** using **BLAKE2b in RFC 7693 keyed mode**. Every secret system —
  carvers, aquifers, ore, surface, structures, decorations, loot, slime — gets its own independent
  128-bit root derived from the key and the domain name. They are not offsets of a shared root, so
  recovering one does not give you the others.
- The level seed is mixed into every derivation, so two worlds with different `level-seed` values do
  not share caves at the same coordinates even when they share a secret.
- **Terrain and biome climate stay public.** The noises `continentalness`, `erosion`, `ridge`,
  `offset`, `jagged`, `temperature` and `vegetation` are routed to the public root, keyed by the
  ordinary `level-seed`. Everything else is secret. The routing is a whitelist, so a Minecraft
  update that adds a new noise fails safe — the new noise is secret rather than silently leaked.
- **`/seed`** reports the public seed and, by default, a fingerprint of the secret rather than the
  secret.
- **A fingerprint in `level.dat`** and a startup guard that refuses to boot a world belonging to a
  different secret.
- Switching `secure-seed.enabled` to `false` returns worldgen to literal Vanilla behaviour — the
  disabled path is the Vanilla code, not a second copy of it.

### 2. Configuration

- One new file, `rolia.yml`, with the five keys above, created `0600`.
- No Canvas or Paper default is changed. A stock Rolia server and a stock Canvas server behave
  identically apart from worldgen.

### 3. Branding

- Server brand, startup banner, `/version` and the F3 screen say Rolia.
- bStats reports as Rolia.
- The jar is `rolia-paperclip-26.2.jar`.
- Canvas's own brand id is left intact, so plugins that detect Canvas still recognise the server —
  Rolia *is* Canvas underneath, and pretending otherwise only breaks plugins.
- Canvas's config files, directories and the `/canvas` command keep their names, so Canvas's
  documentation applies to Rolia unchanged.

### 4. Build and release

- Every build runs a gauntlet before it is allowed to release: the full Paper/Canvas test suite, two
  smoke boots, three generated worlds compared column by column to prove the public/secret split
  holds, a determinism run, the fingerprint guard, a fresh-secret run, a config contract check, and
  four runs with the secure seed switched off to prove that switch really does hand worldgen back to
  Vanilla.
- The BLAKE2b implementation is verified against the RFC 7693 test vectors on every build, including
  a check that it is genuinely keyed mode rather than a naive `key || message` concatenation.

That is the entire list.

---

## Compatibility

Rolia is Canvas, which is a Folia-lineage server. Plugins must support Folia's region threading.
Anything that runs on Canvas runs on Rolia.

Worlds are **not** portable between a Rolia server and a non-Rolia server while
`secure-seed.enabled` is `true` — the caves, ore and structures of a Rolia world cannot be
regenerated without the key. With the switch off, worlds are ordinary Minecraft worlds.

Worlds generated by Rolia builds 45 and earlier are **not** compatible with build 46: the Minecraft
version changed, and biome climate moved from the secret side to the public side. Start a new world.

---

## Documentation

- Russian configuration reference: [`docs/rolia.yml.ru.md`](docs/rolia.yml.ru.md)
- Launch and operations guide: [`LAUNCH.md`](LAUNCH.md)

## Licence

Rolia inherits Canvas's licence: [GNU General Public License v3](LICENSE). Canvas itself inherits
from Paper and Folia. Licences for the third-party patches Canvas bundles ship inside the jar under
`META-INF/licenses/`.
