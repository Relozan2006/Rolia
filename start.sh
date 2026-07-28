#!/usr/bin/env bash
# =============================================================================
#  Rolia server launcher (Linux / macOS) - Minecraft 26.1.2, Java 25+
#
#  Edit MEM / CONC_GC_THREADS / JAR below and run this from the server folder.
#
#  JVM flags are only half the job. A fresh server ALSO needs three settings in
#  config/paper-global.yml that do not default to anything sensible - see the
#  "First run" checklist in LAUNCH.md or README.md. Skipping them leaves the
#  chunk system on a single worker thread and a single I/O thread.
# =============================================================================

cd "$(dirname "$0")" || exit 1

# --- edit me -----------------------------------------------------------------
MEM="4G"                            # heap size; Xms and Xmx are deliberately equal
CONC_GC_THREADS=2                   # G1 concurrent-marking threads, see below
JAR="rolia-paperclip-26.1.2.jar"
# -----------------------------------------------------------------------------

JVM_ARGS=(
  # --- Heap ------------------------------------------------------------------
  # Xms == Xmx on purpose. A growing heap means the JVM commits memory while the
  # server is running, and every resize is a full pause. Fixing both ends removes
  # the resize path entirely. Pick roughly half to two thirds of host RAM: Rolia
  # also needs off-heap memory for chunk I/O, Netty buffers and the JVM itself.
  -Xms"${MEM}" -Xmx"${MEM}"

  # --- Required module / compatibility flags ---------------------------------
  # jdk.incubator.vector: Canvas uses SIMD in parts of the chunk system. Without
  #   this the server logs "SIMD operations are available ... but are not
  #   configured" and silently falls back to scalar code. Must precede -jar.
  # sun-misc-unsafe-memory-access: silences the sun.misc.Unsafe deprecation
  #   warning raised by JOML (a Minecraft dependency).
  # enable-native-access: silences the native-access warnings from Paper's
  #   libdeflate / OpenSSL bindings and JLine.
  --add-modules=jdk.incubator.vector
  --sun-misc-unsafe-memory-access=allow
  --enable-native-access=ALL-UNNAMED

  # --- Garbage collector and its thread budget -------------------------------
  # G1 is the right default here: predictable pause targets, and it is the
  # collector the Aikar tuning below was measured against.
  #
  # ConcGCThreads is pinned ON PURPOSE, and it is the flag people leave out.
  # Folia runs one tick thread per region plus Moonrise's chunk worker and I/O
  # pools, and it budgets those against the core count. G1's CONCURRENT marking
  # threads run at the same time as region ticks - they are not covered by any
  # stop-the-world pause. Left unset, ConcGCThreads defaults to
  # ceil(ParallelGCThreads / 4) and ParallelGCThreads scales with core count, so
  # on a large box G1 quietly takes cores away from region threads mid-tick and
  # the result looks like random MSPT spikes. Pin it, and count it as spent when
  # you size threaded-regions / chunk-system threads.
  # 2 suits a 4-8 GB heap; go to 3-4 only well above that.
  -XX:+UseG1GC
  -XX:ConcGCThreads="${CONC_GC_THREADS}"

  # --- Heap and safepoint behaviour ------------------------------------------
  # AlwaysPreTouch: fault in every heap page at startup so the OS commits it up
  #   front. Boot is slower and the process shows its full heap as RSS
  #   immediately, but no player ever pays a page-fault stall mid-tick.
  # PerfDisableSharedMem: stop the JVM mmap-ing its perf counters into
  #   /tmp/hsperfdata_*. That file is written at safepoints, so a slow or full
  #   /tmp turns into server-wide pauses. Trade-off: jps/jstat/VisualVM can no
  #   longer auto-discover this process (JMX still works if you enable it).
  # DisableExplicitGC: ignore System.gc() calls from plugins, which would
  #   otherwise force a full stop-the-world collection.
  -XX:+AlwaysPreTouch
  -XX:+PerfDisableSharedMem
  -XX:+UnlockExperimentalVMOptions
  -XX:+DisableExplicitGC

  # --- Aikar's G1 tuning, sized for a 4-8 GB heap ----------------------------
  # The widely used Minecraft G1 profile: a large, aggressively collected young
  # generation so short-lived per-tick garbage never gets promoted, a low
  # occupancy trigger so concurrent marking starts early instead of degenerating
  # into a full GC, and MaxTenuringThreshold=1 so objects that do survive move to
  # the old generation immediately rather than being copied between survivor
  # spaces on every collection.
  # Above ~12 GB of heap, retune: G1NewSizePercent=40, G1MaxNewSizePercent=50,
  # G1HeapRegionSize=16M, G1ReservePercent=15, InitiatingHeapOccupancyPercent=20.
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=200
  -XX:G1NewSizePercent=30
  -XX:G1MaxNewSizePercent=40
  -XX:G1HeapRegionSize=8M
  -XX:G1ReservePercent=20
  -XX:G1HeapWastePercent=5
  -XX:G1MixedGCCountTarget=4
  -XX:InitiatingHeapOccupancyPercent=15
  -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5
  -XX:SurvivorRatio=32
  -XX:MaxTenuringThreshold=1

  # --- Markers ---------------------------------------------------------------
  # Informational only: they tell support channels which flag set you are on.
  -Dusing.aikars.flags=https://mcflags.emc.gs
  -Daikars.new.flags=true
)

exec java "${JVM_ARGS[@]}" -jar "${JAR}" --nogui
