# Rolia — launch and first run / запуск и первая настройка

Ready-to-use scripts: **`start.sh`** (Linux/macOS) and **`start.bat`** (Windows).
Set `MEM`, `CONC_GC_THREADS` and `JAR` at the top of the script and run it from the
server folder. They ship tuned for a **32 GB** heap on Java 25; a 32 GB heap wants a
host with 40 GB or more, because the JVM needs memory outside the heap and the OS
needs what is left to cache your region files.

Готовые скрипты: **`start.sh`** (Linux/macOS) и **`start.bat`** (Windows). Задайте
`MEM`, `CONC_GC_THREADS` и `JAR` в начале скрипта и запустите его из папки сервера.

---

## First run checklist / Чек-лист первого запуска

The JVM flags are only half the job. Do these once, then restart.

Флаги JVM — это только половина. Сделайте это один раз и перезапустите сервер.

1. **Java 25+** — `java -version`.
2. **Start once** so the server writes its configs, then stop it.
   **Запустите один раз**, чтобы сервер создал конфиги, затем остановите его.
3. **`config/paper-global.yml` → `chunk-system`** — set `worker-threads` and
   `io-threads` explicitly. See the table below. **This is the single most
   important setting on the list.**
   Задайте `worker-threads` и `io-threads` явно — см. таблицу ниже. **Это самый
   важный пункт списка.**
4. **`config/paper-global.yml` → `threaded-regions.scheduler: AFFINITY`** —
   enables work stealing and pins each region's tasks to its own tick thread.
   Requires ≥2 cores. It is also the only scheduler the Canvas region profiler
   supports (otherwise the log says `Region profiling not supported`).
   Включает work-stealing и привязку задач региона к своему потоку. Нужно ≥2 ядра.
   Также это единственный планировщик, с которым работает профайлер регионов Canvas.
5. **`server.properties` → `level-seed`** — this is the *public* seed and it
   controls **only the shape of the terrain**. You may share it.
   Это *публичный* сид, он задаёт **только форму рельефа**. Его можно называть.
6. **Back up `rolia.yml`** together with the world. It holds the 64+ character
   salt and the 1024-bit feature seed, and everything except terrain shape is
   derived from them. Lose it and an existing world can never be extended
   correctly again. It is created `0600` — keep it that way.
   **Сохраните `rolia.yml`** вместе с миром. В нём соль и 1024-битный сид; от них
   зависит всё, кроме формы рельефа. Потеряете — уже существующий мир нельзя будет
   корректно достроить. Файл создаётся с правами `0600`.
7. **Leave `rolia.yml` alone unless you mean to.** Its five keys are all under
   `secure-seed`, and the defaults are the ones you want: the secret seed on, and
   `on-secret-mismatch: block` so the server refuses to start against a world that
   belongs to a different secret rather than quietly ruining it.
   Пять ключей `rolia.yml` — все в разделе `secure-seed`, и умолчания правильные:
   секретный сид включён, а `on-secret-mismatch: block` не даст серверу стартовать
   на мире с чужим секретом вместо того, чтобы тихо его испортить.

---

## Chunk-system threads / Потоки чанковой системы

Paper ships `chunk-system.worker-threads: -1` and `chunk-system.io-threads: -1`,
and `-1` does **not** mean "use all cores":

* **`io-threads`** is resolved as `Math.max(1, configured)`. `-1` therefore means
  **exactly one I/O thread on every machine**, whatever the core count. Every
  region-file read and write for every world queues behind that one thread. It
  shows up as chunk-load stalls and rubber-banding at the edge of the view
  distance, *not* as high MSPT — which is why it is so rarely found.
* **`worker-threads`** auto-resolves to **1 thread on any box with 7 or fewer
  cores**, and to 2 on 8–9 cores.

Paper по умолчанию ставит `-1` для обоих параметров, и `-1` **не** значит «все ядра»:
`io-threads` вычисляется как `max(1, значение)`, то есть `-1` — это **всегда ровно один**
поток ввода-вывода на любой машине; `worker-threads` при `-1` превращается в **1 поток**
на машинах с 7 ядрами и меньше. Проявляется это подвисанием прогрузки чанков, а не MSPT.

Nothing warns you about this — Paper does not, and Rolia no longer does either
(the startup advisory went with the rest of Rolia's non-seed code in build 46).
Check it yourself. Recommended values:

Об этом никто не предупреждает: ни Paper, ни, начиная со сборки 46, Rolia —
стартовый совет удалён вместе с остальным кодом Rolia, не относящимся к сиду.
Проверьте сами. Рекомендуемые значения:

| CPU cores / ядра | `worker-threads` | `io-threads` |
| --- | --- | --- |
| 4 | 2 | 2 |
| 8 | 3 | 2 |
| 16 | 6 | 3 |
| 32 | 8 | 4 |

```yaml
# config/paper-global.yml
chunk-system:
  worker-threads: 3    # example for 8 cores / пример для 8 ядер
  io-threads: 2
threaded-regions:
  scheduler: AFFINITY
```

These are deliberately **below** the core count. Folia also needs cores for its
region tick threads, and the GC needs `ConcGCThreads` (see below) — those three
budgets share the same CPUs. Over-provisioning the chunk pool just moves the
contention somewhere less visible.

Значения намеренно **меньше** числа ядер: ядра нужны ещё и потокам регионов Folia, и
сборщику мусора (`ConcGCThreads`). Все три бюджета делят одни и те же ядра.

---

## What the JVM flags do / Что делают флаги JVM

**`-Xms` = `-Xmx`** — a growing heap means the JVM commits memory while the
server runs, and every resize is a full pause. Fixing both ends removes the
resize path. Use roughly half to two thirds of host RAM: Rolia also needs
off-heap memory for chunk I/O, Netty buffers and the JVM itself.

**`--add-modules=jdk.incubator.vector`** — the important one. Canvas runs parts
of the chunk system on SIMD instructions, but only if this incubator module is
on. Without it the log says `SIMD operations are available ... but are not
configured` and the server falls back to scalar code. It is a launch flag only:
it changes speed, never world generation or the secure seed. Must appear
**before** `-jar`.

**`--sun-misc-unsafe-memory-access=allow`** — silences the `sun.misc.Unsafe`
deprecation warning raised by JOML (a Minecraft dependency). Harmless either way.
On Java 26 `sun.misc.Unsafe` is removed and the flag stops applying.

**`--enable-native-access=ALL-UNNAMED`** — silences the native-access warnings
from Paper's libdeflate/OpenSSL bindings and JLine.

**`-XX:+UseG1GC -XX:ConcGCThreads=4`** — G1 gives predictable pause targets and
is the collector the Aikar tuning was measured against. `ConcGCThreads` is pinned
**on purpose**: G1's concurrent marking threads run *at the same time* as Folia's
region tick threads, so they are not covered by any stop-the-world pause. Left
unset it defaults to `ceil(ParallelGCThreads / 4)` and `ParallelGCThreads` scales
with core count — so on a large box G1 quietly takes cores away from region ticks
and the symptom is unexplained MSPT spikes. `2` suits a 4–8 GB heap; the shipped
scripts use `4`, which is right for the 32 GB they are tuned for.

**`-XX:+AlwaysPreTouch`** — fault in every heap page at startup so the OS commits
it up front. Boot is slower and the process reports its whole heap as RSS
immediately, but nobody pays a page-fault stall mid-tick.

**`-XX:+PerfDisableSharedMem`** — stop the JVM writing perf counters to
`/tmp/hsperfdata_*`. That file is written at safepoints, so slow or full `/tmp`
becomes server-wide pauses. Trade-off: `jps`/`jstat`/VisualVM can no longer
auto-discover the process (JMX still works if you enable it).

**`-XX:+DisableExplicitGC`** — ignore `System.gc()` from plugins.

**Aikar's G1 block** — a large, aggressively collected young generation so
short-lived per-tick garbage is never promoted; a low occupancy trigger so
concurrent marking starts early instead of degenerating into a full GC; and
`MaxTenuringThreshold=1` so survivors are promoted immediately rather than copied
between survivor spaces every collection. The shipped scripts carry the >12 GB
settings — `G1NewSizePercent=40`, `G1MaxNewSizePercent=50`, `G1HeapRegionSize=16M`,
`G1ReservePercent=15`, `InitiatingHeapOccupancyPercent=20` — because they are tuned
for a 32 GB heap. Below about 12 GB, go back to 30 / 40 / 8M / 20 / 15.

---

## What Rolia adds / Что Rolia добавляет

Exactly one thing: the secret world seed. `rolia.yml` has five keys, all under
`secure-seed`, and only `enabled` defaults to `true`. There are no Rolia
performance options and no Rolia gameplay changes — a stock Rolia server behaves
like a stock Canvas server apart from worldgen.

Ровно одно: секретный сид мира. В `rolia.yml` пять ключей, все в разделе
`secure-seed`, и по умолчанию включён только `enabled`. Никаких своих
оптимизаций и никаких изменений геймплея у Rolia нет — голая Rolia ведёт себя
как голый Canvas, кроме генерации мира.

Performance settings live where they always did: `config/canvas-server.yml`,
`config/canvas-worlds.yml`, `config/paper-global.yml` and
`config/paper-world-defaults.yml`. Rolia does not duplicate or override them.

Настройки производительности — там же, где и были: `canvas-server.yml`,
`canvas-worlds.yml`, `paper-global.yml`, `paper-world-defaults.yml`. Rolia их не
дублирует и не переопределяет.

**Public from the ordinary `level-seed`:** the landscape and the biome map.
**Secret, from the 1024-bit key:** surface rules, decorations, caves and ravines,
aquifers, ore, structures and their loot, slime chunks, End spike layout and
stronghold rings.

**Публично по обычному `level-seed`:** рельеф и карта биомов.
**Секретно, из 1024-битного ключа:** правила поверхности, декорации, пещеры и
овраги, водоносные слои, руда, структуры и лут в них, слайм-чанки, расположение
столбов Края и колец крепостей.

> `rolia.yml` holds the only copy of the secret. Rolia does **not** write backup
> copies of it. Back it up together with the world folder — lose it and the world
> cannot be recovered.
>
> `rolia.yml` — единственная копия секрета. Rolia **не** делает его резервных
> копий. Бэкапьте его вместе с миром: потеряете — мир не восстановить.

---


## Verifying / Проверка

* SIMD is on when the `SIMD operations ... are not configured` warning is **gone**.
* Thread pools are sized when Rolia's `CHUNK SYSTEM THREADS` warning block is **gone**.
* `Rolia: using config <path>` shows which `rolia.yml` was actually loaded —
  check it if you launch the server from a different working directory.
* `/seed` prints the public seed plus a 16-hex-char `Feature seed fp:`
  fingerprint. The secret itself never reaches chat or `latest.log`.

---

## Bigger heaps and other collectors / Большие кучи и другие сборщики

For heaps well above 12 GB with many cores on Java 25 you can try Generational
ZGC instead of the whole G1 block:

```
-XX:+UseZGC -X