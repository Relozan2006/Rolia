<div align="center">


<!-- ROLIA_RULE_44 -->
> **Правило дефолтов (со сборки 43).** Голая Rolia ведёт себя ровно как голый Canvas, плюс секретный
> сид. Каждое изменение поведения — это ключ в `rolia.yml`, и **каждый такой ключ по умолчанию
> `false`**. Единственное исключение — `secure-seed.enabled`. Исправления багов ключами не являются и
> включены всегда.
>
> Полное описание всех опций, которое CI держит в синхроне с кодом:
> [`docs/rolia.yml.ru.md`](docs/rolia.yml.ru.md).

> **Default rule (since build 43).** Stock Rolia behaves exactly like stock Canvas, plus the secure
> seed. Every behaviour change is a key in `rolia.yml`, and **every one of those keys defaults to
> `false`**. The only exception is `secure-seed.enabled`. Bug fixes are not keys and are always on.
>
> Full per-option documentation, kept in step with the code by CI:
> [`docs/rolia.yml.ru.md`](docs/rolia.yml.ru.md).

# Rolia

Высокопроизводительное ядро Minecraft на базе Canvas: рельеф — публичный, всё ценное — под 1024-битным секретом
<br>
High-performance Minecraft server core built on Canvas: public terrain, everything worth finding under a 1024-bit secret

![Minecraft](https://img.shields.io/badge/Minecraft-26.1.2-3fb950)
![Java](https://img.shields.io/badge/Java-25%2B-f89820)
![Base](https://img.shields.io/badge/base-Canvas%20%C2%B7%20Folia-3b82f6)
![Secure seed](https://img.shields.io/badge/secure%20seed-1024--bit-8b5cf6)
![License](https://img.shields.io/badge/license-GPL--3.0-lightgrey)

**[Русский](#русский)  ·  [English](#english)**

</div>

---

<a id="русский"></a>
## Русский

<div align="center">
<img width="820" alt="Rolia" src="https://github.com/user-attachments/assets/938e7151-29c5-4efd-a05a-b6b979b87ebd" />
</div>

**Rolia** — серверное ядро Minecraft **26.1.2** на базе [Canvas](https://github.com/CraftCanvasMC) (форк Folia) с криптографической защитой генерации мира.

Начиная со сборки 40 генерация намеренно разделена надвое: **форма рельефа публична и воспроизводима по обычному `level-seed`**, а всё, что имеет игровую ценность — биомы, пещеры, руды, структуры, данжи, лут, слизнёвые чанки — рассчитывается от секретного **1024-битного** сида и секретной соли. Знание `level-seed` даёт ландшафт и ничего больше.

### Что публично, а что секретно

| | Источник | Что именно |
| --- | --- | --- |
| **Публично** | `level-seed` из `server.properties` | Только **форма рельефа**: шумы `continentalness`, `erosion`, `ridge`, `offset`, `jagged` и `BlendedNoise`, то есть карта высот и очертания материков и океанов. Плюс форма островов Энда. |
| **Секретно** | 1024-битный сид + соль (64+ символов) из `rolia.yml` | Всё остальное: климат биомов (`temperature`, `vegetation` — то есть **какой** биом лежит на данном рельефе), пещеры, овраги, аквиферы, рудные жилы, surface rules, структуры, данжи, декорации, лут, слизнёвые чанки. |

### Что это значит на практике

- Вы можете спокойно назвать свой `level-seed`. Человек с ним воспроизведёт **ландшафт** вашего мира — горы, равнины, береговую линию. Он не найдёт по нему ни одной структуры, ни одной руды, ни одной пещеры, ни одного данжа.
- Онлайн-«искалки сидов» и локальные генераторы мира по публичному сиду покажут правильный рельеф и **неправильные** биомы, структуры и руды.
- Два мира с одинаковым `level-seed` и разными солями — это один и тот же ландшафт с разными биомами на нём и полностью разным содержимым.

Честная оговорка: рельеф не секретен **осознанно**, и он кое-что подсказывает. Некоторые структуры могут стоять только на подходящем рельефе (монумент — в глубоком океане, шахта — под сушей), поэтому знание рельефа сужает область поиска. Оно не даёт координат. И, разумеется, ничто не защищает от того, что игрок просто найдёт структуру своими ногами: защита — от вычисления мира вне игры, а не от исследования внутри неё.

### Где живёт секрет

Секрет хранится **только в `rolia.yml`** (файл создаётся с правами `0600`):

```yaml
secure-seed:
  salt: "…64+ символов…"       # секретная соль
```

- Параметр **`feature-level-seed` в `server.properties` удалён** — сервер его больше не читает.
- В `level.dat` секрет **не пишется**. Там хранится только односторонний **отпечаток**: если запустить мир не с тем `rolia.yml`, сервер это заметит и не станет молча дописывать к миру чанки, сгенерированные другим секретом.
- Команда `/seed` показывает обычный сид и **отпечаток** feature-сида (`Feature seed fp:`) — 16 hex-символов. Сам секрет не печатается ни в чат, ни в `latest.log`; отпечаток нужен только чтобы убедиться, что сид не поменялся.

### Криптография

Хеширование — настоящий **BLAKE2b в keyed-режиме RFC 7693**, то есть MAC, где соль является ключом. Это заменило самодельную конструкцию `H(K xor M)` из прошлых сборок. CI сверяет реализацию с опубликованными тестовыми векторами BLAKE2b на каждой сборке и отдельно проверяет, что печатается именно keyed-дайджест, а не безключевой и не `BLAKE2b(ключ ‖ сообщение)`.

Честная граница стойкости:

- Стойкость дают **соль (64+ символов) и 1024-битный сид**, а не длина хеша. Потеряете соль — потеряете всё; утечёт соль — защиты нет.
- **Рельеф не является секретом** и не защищён. Это не недоработка, а решение: мир остаётся воспроизводимым по форме, а «искать по сиду» становится нечего.
- BLAKE3 быстрее, но не «сильнее»: стойкость BLAKE2b под вопрос не ставится.

### Производительность

Вся производительность Canvas и Folia: региональная многопоточность, оптимизации тиков, чанков и сущностей. Начиная со сборки 40 ускорения **выключены по умолчанию**:

- **DAB** (Dynamic Activation of Brain), **выключен по умолчанию** (`optimizations.dab.enabled`) — мобы вдали от всех игроков **думают реже**. ИИ моба (сенсоры и поведения мозга либо goal selector) выполняется раз в N тиков вместо каждого тика; N растёт с расстоянием от 1 до `max-tick-interval` (по умолчанию 20). Мобы ближе `start-distance` (по умолчанию 12 блоков) не троттлятся никогда. Спектаторы не считаются, креативные игроки — считаются.
  Это **не** поведенчески-нейтрально: троттлящийся моб **реагирует с задержкой** — позже замечает цель, реже перестраивает путь, убегает и доворачивается по более грубым часам, поэтому дальние мобы расходятся и сходятся не так, как в ванилле. Передвижение, физика, урон, деспавн, мобкапы и правила спавна не затронуты, так что *скорость* ферм обычно не меняется, но всё, что завязано на точный пасфайндинг вдали от игрока, поменяться может. Исключения — `optimizations.dab.blacklist`; либо выключите DAB целиком.
- **Планировщик регионов AFFINITY** от Canvas (`threaded-regions.scheduler: AFFINITY` в `paper-global.yml`) — work-stealing и привязка задач региона к своему потоку.
- **Сетевые фильтры пакетов** и ускоренная сериализация чанковых данных (bulk-запись длинных массивов) — единственная из трёх оптимизаций, которая действительно ничего не меняет: байты на проводе идентичны, просто быстрее.
- **Оптимизация запертых торговцев** (лоботомия), **включена по умолчанию**: торговец, зажатый в клетку 1×1, никуда не может пройти, поэтому его тик мозга пропускается целиком. Сделки и **пополнение товаров сохраняются**, так что чистые торговые залы ведут себя как в ванилле.
  Это тоже **не** поведенчески-нейтрально: вместе с мозгом пропускаются все сенсоры и поведения. Лоботомированный торговец **не видит враждебных мобов** (не убегает и не кричит, когда приходит зомби), **не спит**, **не сплетничает**, **не размножается** и **не участвует в спавне железных големов**. Выключите опцию, если у вас железные фермы или разводилки на торговцах.

Также в сборке 40 починены унаследованные от Canvas баги: полностью сломанное накопление снега, дистанция спавна мобов, честность spawn-чанков и защита от кросс-регионального доступа в Folia.

### Требования

Java **25** или новее.

### Установка и запуск

Скачайте `rolia-paperclip-26.1.2.jar` из раздела [Releases](../../releases/latest) и положите рядом со скриптом запуска.

```bash
bash start.sh          # Linux / macOS
start.bat              # Windows
```

Скрипты уже содержат нужные флаги JVM: `-Xms` = `-Xmx`, G1 с закреплённым `-XX:ConcGCThreads`, `-XX:+AlwaysPreTouch`, `-XX:+PerfDisableSharedMem`, набор Aikar под кучу 4–8 ГБ и обязательные `--add-modules=jdk.incubator.vector` (SIMD в Canvas) и `--sun-misc-unsafe-memory-access=allow`. Размер кучи меняется переменной `MEM` в начале скрипта. Подробный разбор каждого флага — в [LAUNCH.md](LAUNCH.md).

### Первый запуск: чек-лист

Флаги JVM — только половина дела. Сделайте это один раз и перезапустите сервер.

1. Java **25+**.
2. Запустите сервер один раз, чтобы он создал конфиги, и остановите.
3. **`config/paper-global.yml` → `chunk-system`**: задайте `worker-threads` и `io-threads` явно. Paper ставит `-1`, и `-1` **не** значит «все ядра»: `io-threads` вычисляется как `max(1, значение)`, то есть `-1` — это **ровно один** поток ввода-вывода на любой машине, а `worker-threads` при `-1` превращается в **один** поток на машинах с 7 ядрами и меньше. Проявляется это подвисанием прогрузки чанков, а не высоким MSPT, поэтому почти никто это не находит. Rolia пишет об этом предупреждение при старте.
4. **`config/paper-global.yml` → `threaded-regions.scheduler: AFFINITY`** — work-stealing и привязка задач региона к своему тик-потоку (нужно ≥2 ядра). Это же единственный планировщик, с которым работает профайлер регионов Canvas.
5. **`server.properties` → `level-seed`** — публичный сид, задаёт **только форму рельефа**. Его можно называть кому угодно.
6. **Сохраните `rolia.yml` в бэкап вместе с миром** — см. раздел ниже.
7. Решите, оставлять ли `optimizations.dab` и `optimizations.villager-lobotomize` в `rolia.yml`. Обе **выключены по умолчанию** и обе меняют поведение мобов — см. «Производительность».

| Ядер CPU | `worker-threads` | `io-threads` |
| --- | --- | --- |
| 4 | 2 | 2 |
| 8 | 3 | 2 |
| 16 | 6 | 3 |
| 32 | 8 | 4 |

```yaml
# config/paper-global.yml
chunk-system:
  worker-threads: 3    # пример для 8 ядер
  io-threads: 2
threaded-regions:
  scheduler: AFFINITY
```

Значения намеренно **меньше** числа ядер: ядра нужны ещё и тик-потокам регионов Folia, и сборщику мусора (`-XX:ConcGCThreads` в скрипте запуска). Все три бюджета делят одни и те же ядра.

### Конфигурация

| Параметр | Файл | Назначение |
| --- | --- | --- |
| `level-seed` | `server.properties` | Публичный сид. Определяет **только форму рельефа**. |
| `secure-seed.salt` | `rolia.yml` | Секретная соль (64+ символов). Создаётся автоматически с правами `0600`. |
| `optimizations.dab` | `rolia.yml` | DAB: `enabled` (по умолчанию `true`), `start-distance`, `max-tick-interval`, `blacklist`. |
| `optimizations.villager-lobotomize` | `rolia.yml` | Лоботомия запертых торговцев: `enabled` (по умолчанию `true`), `wait-until-trade-locked`, `check-interval`. |
| `optimizations.faster-network` | `rolia.yml` | Ускоренная сериализация чанковых данных (по умолчанию `true`). |
| `chunk-system.worker-threads` / `io-threads` | `config/paper-global.yml` | Потоки чанковой системы. **Задайте явно** — см. чек-лист выше. |
| `threaded-regions.scheduler` | `config/paper-global.yml` | Планировщик регионов. Рекомендуется `AFFINITY`. |

Примечание: слизнёвые чанки управляются секретным сидом, поэтому опция `slime-seed` из `spigot.yml` на Rolia не действует.

### Важно: сохраните `rolia.yml`

`rolia.yml` содержит секретную соль и 1024-битный сид. **От них зависит всё, кроме формы рельефа.** Если файл потерять, в уже существующем мире каждый новый чанк получит другие биомы, пещеры, руды и структуры — на границе исследованной территории будет виден шов, и починить это нельзя. Держите файл в секрете и в бэкапе **вместе с миром**.

Сервер логирует абсолютный путь к используемому `rolia.yml` строкой `Rolia: using config …` — проверьте её, если запускаете сервер не из папки с миром. Если файл существует, но не читается, сервер **не стартует**: молча сгенерировать новую соль означало бы переписать мир.

### Переход со сборки 39

- Уберите `feature-level-seed` из `server.properties` — параметр удалён и больше не читается. Ваш 1024-битный сид должен лежать в `rolia.yml`.
- **Рельеф существующего мира изменится.** В 39-й сборке он тоже зависел от секрета, в 40-й — только от `level-seed`. Швов не избежать, поэтому сборка 40 рассчитана на новый мир.
- Соль переносится как есть: структуры, руды и биомы, привязанные к ней, останутся на своих местах.

### Сборка из исходников

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

Готовый jar появится в `canvas-server/build/libs/`.

### Основано на

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · идея криптографического сида — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · модель «публичный рельеф, секретные фичи» — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

Лицензия: [GPL-3.0](LICENSE).

---

<a id="english"></a>
## English

<div align="center">
<img width="820" alt="Rolia" src="https://github.com/user-attachments/assets/938e7151-29c5-4efd-a05a-b6b979b87ebd" />
</div>

**Rolia** is a Minecraft **26.1.2** server core built on [Canvas](https://github.com/CraftCanvasMC) (a Folia fork) with cryptographically protected world generation.

As of build 40, generation is deliberately split in two: **the shape of the terrain is public and reproducible from the ordinary `level-seed`**, while everything worth finding — biomes, caves, ores, structures, dungeons, loot, slime chunks — is derived from a secret **1024-bit** seed and a secret salt. Knowing the `level-seed` gets you the landscape and nothing else.

### What is public and what is secret

| | Source | Exactly what |
| --- | --- | --- |
| **Public** | `level-seed` in `server.properties` | The **shape of the terrain only**: the `continentalness`, `erosion`, `ridge`, `offset` and `jagged` noises plus `BlendedNoise` — i.e. the heightmap and the outline of the landmasses and oceans. Plus the shape of the End islands. |
| **Secret** | a 1024-bit seed + a 64+ char salt from `rolia.yml` | Everything else: biome climate (`temperature`, `vegetation` — i.e. **which** biome sits on a given landform), caves, ravines, aquifers, ore veins, surface rules, structures, dungeons, decorations, loot, slime chunks. |

### What that means in practice

- You can hand out your `level-seed`. Someone who has it can reproduce the **landscape** of your world — the mountains, the plains, the coastline. It will not find them a single structure, ore, cave or dungeon.
- Online seed finders and local world generators fed the public seed will show the right terrain and the **wrong** biomes, structures and ores.
- Two worlds with the same `level-seed` and different salts are the same landscape wearing different biomes, with completely different contents.

An honest caveat: terrain is **intentionally** not secret, and it does leak something. Some structures can only stand on suitable terrain (a monument needs deep ocean, a mineshaft needs land), so knowing the terrain narrows the search area. It does not hand over coordinates. And nothing protects against a player simply walking into a structure — this protects against computing the world offline, not against exploring it in game.

### Where the secret lives

The secret lives **only in `rolia.yml`** (created with `0600` permissions):

```yaml
secure-seed:
  salt: "…64+ characters…"      # the secret salt
```

- **`feature-level-seed` in `server.properties` has been removed** — the server no longer reads it.
- The secret is **not written to `level.dat`**. Only a one-way **fingerprint** is stored there, so that booting a world with the wrong `rolia.yml` is noticed instead of silently appending chunks generated from a different secret.
- `/seed` prints the ordinary seed and a **fingerprint** of the feature seed (`Feature seed fp:`) — 16 hex chars. The secret itself never reaches chat or `latest.log`; the fingerprint exists only so you can confirm the seed has not changed.

### Cryptography

Hashing is real **BLAKE2b in RFC 7693 keyed mode** — a MAC, with the salt as the key. It replaces the homemade `H(K xor M)` construction used by earlier builds. CI checks the implementation against the published BLAKE2b test vectors on every build, and separately asserts that what the server prints is the keyed digest rather than the unkeyed one or `BLAKE2b(key ‖ message)`.

The honest security bound:

- The strength comes from the **salt (64+ chars) and the 1024-bit seed**, not from the hash length. Lose the salt and you lose everything; leak the salt and there is no protection left.
- **Terrain is not secret and is not protected.** That is a decision, not an oversight: the world stays reproducible in shape, and there is nothing left to find by seed.
- BLAKE3 would be faster, not stronger; BLAKE2b's security is not in question.

### Performance

All the performance of Canvas and Folia: regionized multithreading, tick/chunk/entity optimizations. As of build 40 the speedups are **off by default** (`optimizations.dab.enabled`):

- **DAB** (Dynamic Activation of Brain), **off by default** (`optimizations.dab.enabled`) — mobs far from every player **think less often**. A mob's AI (its brain sensors and behaviours, or the goal selector) runs once every N ticks instead of every tick, where N grows with distance from 1 up to `max-tick-interval` (20 by default). Mobs within `start-distance` (12 blocks by default) are never throttled. Spectators do not count, creative-mode players do.
  This is **not** behaviour-neutral: a throttled mob **reacts late** — it notices targets, repaths, flees and re-aims on a coarser clock, so distant mobs drift and converge differently than in Vanilla. Movement, physics, damage, despawning, mob caps and spawn rules are untouched, so farm *rates* are normally unaffected, but anything relying on precise distant pathfinding can change. Exempt specific types with `optimizations.dab.blacklist`, or turn DAB off entirely.
- **Canvas's AFFINITY region scheduler** (`threaded-regions.scheduler: AFFINITY` in `paper-global.yml`) — work stealing plus keeping a region's tasks on their own tick thread.
- **Network packet filters** and faster chunk-data serialization (bulk long-array writes) — the only one of the three that genuinely changes nothing: the bytes on the wire are identical, just faster.
- **Stuck-villager optimization** (lobotomize), **off by default** (`optimizations.dab.enabled`): a villager boxed into a 1×1 cell cannot path anywhere, so its whole brain tick is skipped. Trades and **restocking are preserved**, so pure trading halls behave like Vanilla.
  This is not behaviour-neutral either: skipping the brain skips every sensor and behaviour. A lobotomized villager does **not detect hostiles** (it will not flee or scream when a zombie arrives), does **not sleep**, does **not gossip**, does **not breed**, and does **not contribute to iron-golem spawning**. Turn it off if you run villager-based iron farms or breeders.

Build 40 also fixes bugs inherited from Canvas: completely broken snow accumulation, the mob spawn distance gate, spawn-chunk fairness, and a Folia cross-region safety guard.

### Requirements

Java **25** or newer.

### Install and run

Download `rolia-paperclip-26.1.2.jar` from [Releases](../../releases/latest) and put it next to the launch script.

```bash
bash start.sh          # Linux / macOS
start.bat              # Windows
```

The scripts already carry the flags you want: `-Xms` = `-Xmx`, G1 with a pinned `-XX:ConcGCThreads`, `-XX:+AlwaysPreTouch`, `-XX:+PerfDisableSharedMem`, the Aikar set sized for a 4–8 GB heap, and the two required flags `--add-modules=jdk.incubator.vector` (Canvas SIMD) and `--sun-misc-unsafe-memory-access=allow`. Change the heap with the `MEM` variable at the top of the script. Every flag is explained in [LAUNCH.md](LAUNCH.md).

### First run checklist

The JVM flags are only half the job. Do this once, then restart.

1. Java **25+**.
2. Start the server once so it writes its configs, then stop it.
3. **`config/paper-global.yml` → `chunk-system`**: set `worker-threads` and `io-threads` explicitly. Paper ships `-1` for both, and `-1` does **not** mean "use all cores": `io-threads` is resolved as `max(1, configured)`, so `-1` means **exactly one** I/O thread on every machine, and `worker-threads` auto-resolves to **one** thread on any box with 7 or fewer cores. It shows up as chunk-load stalls rather than high MSPT, which is why almost nobody finds it. Rolia warns about it at startup.
4. **`config/paper-global.yml` → `threaded-regions.scheduler: AFFINITY`** — work stealing plus keeping a region's tasks on their own tick thread (needs ≥2 cores). It is also the only scheduler the Canvas region profiler supports.
5. **`server.properties` → `level-seed`** — the public seed; it controls **only the shape of the terrain**. You may hand it out.
6. **Back up `rolia.yml` together with your world** — see the section below.
7. Decide whether to keep `optimizations.dab` and `optimizations.villager-lobotomize` in `rolia.yml`. Both are **off by default** (`optimizations.dab.enabled`) and both change mob behaviour — see "Performance".

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

### Configuration

| Option | File | Purpose |
| --- | --- | --- |
| `level-seed` | `server.properties` | The public seed. Determines **the shape of the terrain only**. |
| `secure-seed.salt` | `rolia.yml` | The secret salt (64+ chars). Created automatically with `0600` permissions. |
| `optimizations.dab` | `rolia.yml` | DAB: `enabled` (`false` by default), `start-distance`, `max-tick-interval`, `blacklist`. |
| `optimizations.villager-lobotomize` | `rolia.yml` | Stuck-villager lobotomization: `enabled` (`false` by default), `wait-until-trade-locked`, `check-interval`. |
| `optimizations.faster-network` | `rolia.yml` | Faster chunk-data serialization (`false` by default). |
| `chunk-system.worker-threads` / `io-threads` | `config/paper-global.yml` | Chunk-system thread pools. **Set these explicitly** — see the checklist above. |
| `threaded-regions.scheduler` | `config/paper-global.yml` | Region scheduler. `AFFINITY` recommended. |

Note: slime chunks are driven by the secret seed, so the `slime-seed` option from `spigot.yml` has no effect on Rolia.

### Important: back up `rolia.yml`

`rolia.yml` holds the secret salt and the 1024-bit seed. **Everything except the shape of the terrain depends on them.** If the file is lost, every newly generated chunk in an existing world gets different biomes, caves, ores and structures — you will see a hard seam at the edge of explored territory, and it cannot be repaired. Keep the file secret and backed up **together with your world**.

The server logs the absolute path of the `rolia.yml` it actually used (`Rolia: using config …`) — check it if you start the server from a directory other than the world folder. If the file exists but cannot be parsed, the server **refuses to start**: silently generating a new salt would rewrite the world.

### Upgrading from build 39

- Remove `feature-level-seed` from `server.properties` — the property is gone and is no longer read. Your 1024-bit seed belongs in `rolia.yml`.
- **The terrain of an existing world will change.** In build 39 terrain was derived from the secret too; in build 40 it comes from `level-seed` alone. Seams are unavoidable, so build 40 is meant for a fresh world.
- The salt carries over unchanged: the structures, ores and biomes tied to it stay where they are.

### Building from source

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is produced in `canvas-server/build/libs/`.

### Built on

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · secure-seed idea — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · the "public terrain, secret features" model — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

License: [GPL-3.0](LICENSE).
