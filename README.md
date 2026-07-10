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

**Rolia** — серверное ядро Minecraft **26.1.2** на базе [Canvas](https://github.com/CraftCanvasMC) (форк Folia) с криптографической защитой мирового сида. Территория остаётся воспроизводимой из обычного сида, а всё, что даёт игровое преимущество — структуры, данжи, руды, деревни, сокровища — генерируется от секретного **1024-битного** сида, вычислить который по миру практически невозможно.

### Как устроена защита сида

Rolia разделяет генерацию на два независимых источника случайности:

| Что генерируется | Источник | Поведение |
| --- | --- | --- |
| Рельеф, биомы, пещеры, аквиферы, острова Энда | Обычный `level-seed` (64 бита) | Как в ванилле — мир воспроизводим из сида |
| Структуры, данжи, деревни, декорации, добываемые руды, жеоды, слизнёвые чанки | Секретный `feature-seed` (1024 бита) + соль | Расположение практически невозможно вычислить или подобрать |

Ключ защиты — секретная **соль** в `rolia-seed.properties`. Пока она в тайне, восстановить расположение структур и руд по миру нельзя.

### Возможности

- Вся производительность Canvas и Folia: регионная многопоточность, оптимизации тиков, чанков и сущностей.
- 1024-битный секретный feature-сид на настоящем **BLAKE2b** (RFC 7693).
- Секретная соль хранится в `rolia-seed.properties` с правами доступа только для владельца.
- Детерминированная генерация: один и тот же `level-seed` + `feature-seed` + соль всегда дают идентичный мир.

### Требования

Java **25** или новее.

### Установка и запуск

Скачайте `rolia-paperclip-26.1.2.jar` из раздела [Releases](../../releases/latest).

```bash
# рекомендуется: с SIMD-ускорением (Java 25+)
java -Xmx4G --add-modules=jdk.incubator.vector -jar rolia-paperclip-26.1.2.jar --nogui

# или готовый скрипт из репозитория
bash start.sh
```

### Конфигурация

| Параметр | Файл | Назначение |
| --- | --- | --- |
| `level-seed` | `server.properties` | Обычный сид. Управляет рельефом и биомами. Мир воспроизводим из него. |
| `feature-level-seed` | `server.properties` | 1024-битный feature-сид (десятичное число). Если пусто — генерируется криптостойкий случайный. |
| `secure-seed.salt` | `rolia-seed.properties` | Секретная соль (64+ символа). Создаётся автоматически с правами `0600`. |

Команда `/seed` показывает и обычный сид, и feature-сид.

### Важно: сохраните соль

`rolia-seed.properties` содержит секретную соль. От неё зависит расположение всех структур и руд. Если файл потерять, в уже существующем мире новые структуры и руды перестанут совпадать со старыми (рельеф совпадёт — он привязан к `level-seed`). Держите файл в секрете и в бэкапе вместе с миром — именно соль делает вычисление сида невозможным.

Примечание: слизнёвые чанки управляются секретным сидом, поэтому опция `slime-seed` из `spigot.yml` на Rolia не действует.

### Сборка из исходников

```bash
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
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

**Rolia** is a Minecraft **26.1.2** server core built on [Canvas](https://github.com/CraftCanvasMC) (a Folia fork) with a cryptographically protected world seed. The terrain stays reproducible from the ordinary seed, while everything that grants a gameplay advantage — structures, dungeons, ores, villages, loot — is generated from a secret **1024-bit** seed that is practically impossible to reverse from the world.

### How the secure seed works

Rolia splits world generation into two independent sources of randomness:

| What is generated | Source | Behaviour |
| --- | --- | --- |
| Terrain, biomes, caves, aquifers, End islands | Ordinary `level-seed` (64-bit) | Vanilla-like — the world is reproducible from the seed |
| Structures, dungeons, villages, decorations, mineable ores, geodes, slime chunks | Secret `feature-seed` (1024-bit) + salt | Placement is practically impossible to reverse or brute-force |

The protection key is the secret **salt** in `rolia-seed.properties`. As long as it stays secret, the location of structures and ores cannot be recovered from the world.

### Features

- All the performance of Canvas and Folia: regionized multithreading, tick/chunk/entity optimizations.
- 1024-bit secret feature seed backed by real **BLAKE2b** (RFC 7693).
- The secret salt is stored in `rolia-seed.properties` with owner-only permissions.
- Deterministic generation: the same `level-seed` + `feature-seed` + salt always produce an identical world.

### Requirements

Java **25** or newer.

### Install and run

Download `rolia-paperclip-26.1.2.jar` from [Releases](../../releases/latest).

```bash
# recommended: with SIMD acceleration (Java 25+)
java -Xmx4G --add-modules=jdk.incubator.vector -jar rolia-paperclip-26.1.2.jar --nogui

# or the ready-made script from the repository
bash start.sh
```

### Configuration

| Option | File | Purpose |
| --- | --- | --- |
| `level-seed` | `server.properties` | Ordinary seed. Drives terrain and biomes. The world is reproducible from it. |
| `feature-level-seed` | `server.properties` | 1024-bit feature seed (decimal number). If empty, a cryptographically secure random one is generated. |
| `secure-seed.salt` | `rolia-seed.properties` | Secret salt (64+ chars). Created automatically with `0600` permissions. |

The `/seed` command shows both the ordinary seed and the feature seed.

### Important: back up your salt

`rolia-seed.properties` holds the secret salt. The location of every structure and ore depends on it. If the file is lost, newly generated structures and ores in an existing world will no longer match the old ones (terrain will still match — it is tied to `level-seed`). Keep the file secret and backed up together with your world — the salt is what makes seed reversal impossible.

Note: slime chunks are driven by the secret seed, so the `slime-seed` option from `spigot.yml` has no effect on Rolia.

### Building from source

```bash
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

The server jar is produced in `canvas-server/build/libs/`.

### Built on

[Canvas](https://github.com/CraftCanvasMC) · [Folia / Paper](https://papermc.io) · secure-seed idea — [SecureSeed](https://github.com/Earthcomputer/SecureSeed) (Earthcomputer) · the "vanilla terrain, secret features" model — [Matter](https://github.com/plasmoapp/matter) (plasmoapp).

License: [GPL-3.0](LICENSE).
