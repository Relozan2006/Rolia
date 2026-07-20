#!/usr/bin/env bash
# Rolia server launcher (Linux / macOS)
# Requires Java 25 or newer. Adjust -Xmx to the RAM you want to give the server.
#
# The flags below enable SIMD acceleration (jdk.incubator.vector) and the legacy
# memory-access path Folia/Canvas use; they are safe on Java 25+.

cd "$(dirname "$0")" || exit 1

exec java -Xmx4G \
  --add-modules=jdk.incubator.vector \
  --sun-misc-unsafe-memory-access=allow \
  -jar rolia-paperclip-26.1.2.jar --nogui
