# Rolia

Rolia is a high-performance Minecraft server core based on Canvas (a Folia fork) with full 1024-bit secure world seed support.

## Features

- Everything from Canvas: regionized multithreading, performance optimizations, and extensive configuration.
- 1024-bit secure feature seed: world generation features (structures, geodes, slime chunks, terrain randomness) are driven by a cryptographic 1024-bit seed instead of the vanilla 64-bit seed, making seed reversal practically impossible.
- Configurable secret salt (rolia-seed.properties) for additional protection against world copying.

## Running

Requires Java 25+.

```
java -Xmx4G -jar rolia-paperclip-26.1.2.jar --nogui
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
