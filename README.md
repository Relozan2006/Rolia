<div align="center">

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

Вся производительность Canvas и Folia: региональная многопоточность, оптимизации тиков, чанков и сущностей. Начиная со сборки 40 ускорения **включены по умолчанию**:

- **DAB** (Dynamic Activation of Brain) — троттлинг ИИ дальних мобов. Моб ближе `start-distance` (по умолчанию 12 блоков) от игрока думает каждый тик; дальше интервал растёт с расстоянием до `max-tick-interval` (по умолчанию 20 тиков). Спектаторы не считаются, креативные игроки — считаются.
  DAB **не меняет** правила спавна, мобкапы, дроп, физику, редстоун и работу воронок; мобы продолжают тикать, реже выполняется только их ИИ. Но ферма, работающая на передвижении мобов вдали от игрока, будет работать медленнее — внесите нужные типы в `optimizations.dab.blacklist` или выключите DAB.
- **Планировщик регионов AFFINITY** от Canvas (`threaded-regions.scheduler: AFFINITY` в `paper-global.yml`) — work-stealing и привязка задач региона к своему потоку.
- **Сетевые фильтры пакетов** и ускоренная сериализация чанковых данных (bulk-запись длинных массивов) — байты на проводе идентичны, просто быстрее.
- **Оптимизация запертых торговцев** (лоботомия, включена): торговец в 1×1 не тикает мозг, но **исправно пополняет сделки**. Учтите: он также не видит враждебных мобов, не спит, не сплетничает, не размножается и **не участвует в спавне железных големов** — выключите опцию, если у вас железные фермы на торговцах.

Также в сборке 40 починены унаследованные от Canvas баги: полностью сломанное накопление снега, дистанция спавна мобов, честность spawn-чанков и защита от кросс-регионального доступа в Folia.

### Требования

Java **25** или новее.

### Установка и запуск

Скачайте `rolia-paperclip-26.1.2.jar` из раздела [Releases](../../releases/latest).

```bash
# рекомендуется: с SIMD-ускорением (Java 25+)
java -Xmx4G --add-modules=jdk.incubator.vector --sun-misc-unsafe-memory-access=allow -jar rolia-paperclip-26.1.2.jar --nogui

# или готовый скрипт из репозитория
bash start.sh
```

### Конфигурация

| Параметр | Файл | Назначение |
| --- | --- | --- |
| `level-seed` | `server.properties` | Публичный сид. Определяет **только форму рельефа**. |
| `secure-seed.salt` | `rolia.yml` | Секретная соль (64+ символов). Создаётся автоматически с правами `0600`. |
| `optimizations.dab` | `rolia.yml` | DAB: `enabled` (по умолчанию `true`), `start-distance`, `max-tick-interval`, `blacklist`. |
| `optimizations.villager-lobotomize` | `rolia.yml` | Лоботомия запертых торговцев. |
| `optimizations.faster-network` | `rolia.yml` | Ускоренная сериализация чанковых данных. |

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

All the performance of Canvas and Folia: regionized multithreading, tick/chunk/entity optimizations. As of build 40 the speedups are **on by default**:

- **DAB** (Dynamic Activation of Brain) — throttles the AI of distant mobs. A mob within `start-distance` (12 blocks by default) of a player thinks every tick; beyond that the interval grows with distance up to `max-tick-interval` (20 ticks by default). Spectators do not count, creative-mode players do.
  DAB does **not** change spawning rules, mob caps, drops, physics, redstone or hoppers; mobs keep ticking, only their AI runs less often. But a farm that depends on mobs moving far away from any player will run more slowly — add those types to `optimizations.dab.blacklist` or turn DAB off.
- **Canvas's AFFINITY region scheduler** (`threaded-regions.scheduler: AFFINITY` in `paper-global.yml`) — work stealing plus keeping a region's tasks on their own tick thread.
- **Network packet filters** and faster chunk-data serialization (bulk long-array writes) — the bytes on the wire are identical, just faster.
- **Stuck-villager optimization** (lobotomize, on): a villager in a 1×1 skips its brain tick but **still restocks trades**. Note that it also does not detect hostiles, sleep, gossip, breed, or **contribute to iron-golem spawning** — turn it off if you run villager-based iron farms.

Build 40 also fixes bugs inherited from Canvas: completely broken snow accumulation, the mob spawn distance gate, spawn-chunk fairness, and a Folia cross-region safety guard.

### Requirements

Java **25** or newer.

### Install and run

Download `rolia-paperclip-26.1.2.jar` from [Releases](../../releases/latest).

```bash
# recommended: with SIMD acceleration (Java 25+)
java -Xmx4G --add-modules=jdk.incubator.vector --sun-misc-unsafe-memory-access=allow -jar rolia-paperclip-26.1.2.jar --nogui

# or the ready-made script from the repository
bash start.sh
```

### Configuration

| Option | File | Purpose |
| --- | --- | --- |
| `level-seed` | `server.properties` | The public seed. Determines **the shape of the terrain only**. |
| `secure-seed.salt` | `rolia.yml` | The secret salt (64+ chars). Created automatically with `0600` permissions. |
| `optimizations.dab` | `rolia.yml` | DAB: `enabled` (`true` by default), `start-distance`, `max-tick-interval`, `blacklist`. |
| `optimizations.villager-lobotomize` | `rolia.yml` | Stuck-villager lobotomization. |
| `optimizations.faster-network` | `rolia.yml` | Faster chunk-data serialization. |

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
