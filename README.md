<div align="center">

# Rolia

Высокопроизводительное ядро Minecraft на базе Canvas с криптографической защитой сида (1024 бита)
<br>
High-performance Minecraft server core built on Canvas with a 1024-bit cryptographic secure seed

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

**Rolia** — серверное ядро Minecraft **26.1.2** на базе [Canvas](https://github.com/CraftCanvasMC) (форк Folia) с криптографической защитой мирового сида. **Весь** мир — рельеф, биомы, пещеры, структуры, данжи, руды, деревни, сокровища — генерируется от секретного **1024-битного** сида и секретной соли (Seed V2). По публичному сиду вычислить расположение чего-либо практически невозможно.

### Как устроена защита сида

Rolia разделяет генерацию на два независимых источника случайности:

| Что генерируется | Источник | Поведение |
| --- | --- | --- |
| Рельеф, биомы, пещеры, овраги, аквиферы, острова Энда | Секретный сид + соль | Под защитой — по публичному сиду не вычислить (Seed V2) |
| Структуры, данжи, деревни, декорации, добываемые руды, жеоды, слизнёвые чанки | Секретный `feature-seed` (1024 бита) + соль | Расположение практически невозможно вычислить или подобрать |

Ключ защиты — секретная **соль** в `rolia.yml`. Пока она в тайне, восстановить расположение структур и руд по миру нельзя.

### Возможности

- Вся производительность Canvas и Folia: регионная многопоточность, оптимизации тиков, чанков и сущностей.
- 1024-битный секретный feature-сид на настоящем **BLAKE2b** (RFC 7693) — та же хеш-функция, что в оригинальном [SecureSeed](https://github.com/Earthcomputer/SecureSeed). BLAKE3 здесь не нужен: он быстрее, но не «сильнее», а стойкость BLAKE2b не поставлена под сомнение.
- Секретная соль хранится в `rolia.yml` с правами доступа только для владельца.
- Детерминированная генерация: один и тот же `level-seed` + `feature-seed` + соль всегда дают идентичный мир.
- Оптимизация запертых торговцев (лоботомия, **включена по умолчанию**): торговец в 1×1 не тикает мозг, но **исправно пополняет сделки** — торговые залы работают как в ванилле. Учтите: лоботомированный торговец также не видит враждебных мобов, не спит, не сплетничает, не размножается и **не участвует в спавне железных големов** — выключите опцию, если у вас железные фермы на торговцах.
- Ускоренная сериализация чанковых данных (bulk-запись длинных массивов) — байты на проводе идентичны, просто быстрее.

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
| `level-seed` | `server.properties` | Базовый сид. Вместе с секретной солью определяет мир; по одному публичному сиду мир не воспроизвести. |
| `feature-level-seed` | `server.properties` | 1024-битный feature-сид (десятичное число). Если пусто — генерируется криптостойкий случайный. |
| `secure-seed.salt` | `rolia.yml` | Секретная соль (64+ символа). Создаётся автоматически с правами `0600`. |

Команда `/seed` показывает обычный сид и **отпечаток** feature-сида (`Feature seed fp:`) — 16 hex-символов, односторонняя свёртка от «сид + соль». Сам секрет не печатается ни в чат, ни в `latest.log`: отпечаток нужен только чтобы убедиться, что сид не поменялся.

### Важно: сохраните соль

`rolia.yml` содержит секретную соль. **Начиная с Seed V2 от неё зависит весь мир, а не только структуры.** Если файл потерять, в уже существующем мире каждый новый чанк получит другой рельеф, биомы, пещеры, руды и структуры — на границе исследованной территории будет виден шов, и починить это нельзя. Держите файл в секрете и в бэкапе **вместе с миром**.

Сервер логирует абсолютный путь к используемому `rolia.yml` строкой `Rolia: using config ...` — проверьте её, если запускаете сервер не из папки с миром. Если файл существует, но не читается, сервер **не стартует**: это сделано намеренно, потому что молча сгенерировать новую соль означало бы переписать мир.

Примечание: слизнёвые чанки управляются секретным сидом, поэтому опция `slime-seed` из `spigot.yml` на Rolia не действует.

### Сборка из исходников

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

Готовый jar появится в `canvas-server/build/libs/`.

### Основано на

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · идея криптографического сида — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · модель «ванильный рельеф, секретные фичи» — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

Лицензия: [GPL-3.0](LICENSE).

---

<a id="english"></a>
## English

<div align="center">
<img width="820" alt="Rolia" src="https://github.com/user-attachments/assets/938e7151-29c5-4efd-a05a-b6b979b87ebd" />
</div>

**Rolia** is a Minecraft **26.1.2** server core built on [Canvas](https://github.com/CraftCanvasMC) (a Folia fork) with a cryptographically protected world seed. The **entire** world — terrain, biomes, caves, structures, dungeons, ores, villages, loot — is generated from a secret **1024-bit** seed and a secret salt (Seed V2). Nothing's location can be reversed from the public seed.

### How the secure seed works

Rolia splits world generation into two independent sources of randomness:

| What is generated | Source | Behaviour |
| --- | --- | --- |
| Terrain, biomes, caves, ravines, aquifers, End islands | Secret seed + salt | Protected — cannot be reversed from the public seed (Seed V2) |
| Structures, dungeons, villages, decorations, mineable ores, geodes, slime chunks | Secret `feature-seed` (1024-bit) + salt | Placement is practically impossible to reverse or brute-force |

The protection key is the secret **salt** in `rolia.yml`. As long as it stays secret, the location of structures and ores cannot be recovered from the world.

### Features

- All the performance of Canvas and Folia: regionized multithreading, tick/chunk/entity optimizations.
- 1024-bit secret feature seed backed by real **BLAKE2b** (RFC 7693).
- The secret salt is stored in `rolia.yml` with owner-only permissions.
- Deterministic generation: the same `level-seed` + `feature-seed` + salt always produce an identical world.
- Hashing is real **BLAKE2b** (RFC 7693) — the same hash the original [SecureSeed](https://github.com/Earthcomputer/SecureSeed) uses. BLAKE3 would be faster, not stronger; BLAKE2b's security is not in question.
- Stuck-villager optimization (lobotomize, **on by default**): a villager in a 1×1 skips its brain tick but **still restocks trades** — trading halls behave like vanilla. Note that a lobotomized villager also does not detect hostiles, sleep, gossip, breed, or **contribute to iron-golem spawning** — turn it off if you run villager-based iron farms.
- Faster chunk-data serialization (bulk long-array writes) — the bytes on the wire are identical, just faster.

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
| `level-seed` | `server.properties` | Base seed. Together with the secret salt it determines the world; the world cannot be reproduced from the public seed alone. |
| `feature-level-seed` | `server.properties` | 1024-bit feature seed (decimal number). If empty, a cryptographically secure random one is generated. |
| `secure-seed.salt` | `rolia.yml` | Secret salt (64+ chars). Created automatically with `0600` permissions. |

The `/seed` command shows the ordinary seed and a **fingerprint** of the feature seed (`Feature seed fp:`) — 16 hex chars, a one-way digest of seed + salt. The secret itself is never printed to chat or to `latest.log`; the fingerprint exists only so you can confirm the seed has not changed.

### Important: back up your salt

`rolia.yml` holds the secret salt. **Since Seed V2 the entire world depends on it, not just structures.** If the file is lost, every newly generated chunk in an existing world gets different terrain, biomes, caves, ores and structures — you will see a hard seam at the edge of explored territory, and it cannot be repaired. Keep the file secret and backed up **together with your world**.

The server logs the absolute path of the `rolia.yml` it actually used (`Rolia: using config ...`) — check it if you start the server from a directory other than the world folder. If the file exists but cannot be parsed, the server **refuses to start**: silently generating a new salt would rewrite the world.

Note: slime chunks are driven by the secret seed, so the `slime-seed` option from `spigot.yml` has no effect on Rolia.

### Building from source

```bash
./gradlew applyAllPatches
./gradlew createPaperclipJar
```

The server jar is produced in `canvas-server/build/libs/`.

### Built on

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · secure-seed idea — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · the "vanilla terrain, secret features" model — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

License: [GPL-3.0](LICENSE).
