<img width="1280" height="720" alt="image" src="https://github.com/user-attachments/assets/938e7151-29c5-4efd-a05a-b6b979b87ebd" />

## Features

- Everything from Canvas: regionized multithreading, performance optimizations, and extensive configuration.
- 1024-bit secure feature seed: world generation features (structures, geodes, slime chunks, terrain randomness) are driven by a cryptographic 1024-bit seed instead of the vanilla 64-bit seed, making seed reversal practically impossible.
- Configurable secret salt (rolia-seed.properties) for additional protection against world copying.

## Running

Requires Java 25+.

```
java -Xmx4G -jar server.jar --nogui
```

- `level-seed` in server.properties works as usual (vanilla 64-bit seed).
- `feature-level-seed` in server.properties can hold the 1024-bit feature seed (decimal number). If empty, a cryptographically secure random one is generated.
- `/seed` shows both the vanilla seed and the feature seed.

## Building

```
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

The server jar is produced in `canvas-server/build/libs/`.

## Important: back up your salt!

`rolia-seed.properties` contains the secret salt. All terrain, biomes and features depend on it:
if the file is lost, newly generated chunks in an existing world WILL NOT match the old ones
(visible chunk borders). Back this file up together with your world and keep it secret —
the salt is what makes seed reversal impossible.

Note: because slime chunks are driven by the 1024-bit secure seed, the `slime-seed` option
from spigot.yml has no effect on Rolia.
