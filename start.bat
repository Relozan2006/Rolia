@echo off
REM ==========================================================================
REM  Rolia server launcher (Windows) - Minecraft 26.1.2, Java 25+
REM
REM  Edit MEM / CONC_GC_THREADS / JAR below and run this from the server folder.
REM
REM  JVM flags are only half the job. A fresh server ALSO needs three settings
REM  in config\paper-global.yml that do not default to anything sensible - see
REM  the "First run" checklist in LAUNCH.md or README.md. Skipping them leaves
REM  the chunk system on a single worker thread and a single I/O thread.
REM ==========================================================================

cd /d "%~dp0"

REM --- edit me --------------------------------------------------------------
set "MEM=4G"
set "CONC_GC_THREADS=2"
set "JAR=rolia-paperclip-26.1.2.jar"
REM --------------------------------------------------------------------------

set "JVM_ARGS="

REM --- Heap -----------------------------------------------------------------
REM Xms == Xmx on purpose. A growing heap means the JVM commits memory while the
REM server is running, and every resize is a full pause. Fixing both ends removes
REM the resize path entirely. Pick roughly half to two thirds of host RAM: Rolia
REM also needs off-heap memory for chunk I/O, Netty buffers and the JVM itself.
set "JVM_ARGS=%JVM_ARGS% -Xms%MEM% -Xmx%MEM%"

REM --- Required module / compatibility flags --------------------------------
REM jdk.incubator.vector: Canvas uses SIMD in parts of the chunk system. Without
REM   this the server logs "SIMD operations are available ... but are not
REM   configured" and silently falls back to scalar code. Must precede -jar.
REM sun-misc-unsafe-memory-access: silences the sun.misc.Unsafe deprecation
REM   warning raised by JOML (a Minecraft dependency).
REM enable-native-access: silences the native-access warnings from Paper's
REM   libdeflate / OpenSSL bindings and JLine.
set "JVM_ARGS=%JVM_ARGS% --add-modules=jdk.incubator.vector"
set "JVM_ARGS=%JVM_ARGS% --sun-misc-unsafe-memory-access=allow"
set "JVM_ARGS=%JVM_ARGS% --enable-native-access=ALL-UNNAMED"

REM --- Garbage collector and its thread budget ------------------------------
REM G1 is the right default here: predictable pause targets, and it is the
REM collector the Aikar tuning below was measured against.
REM
REM ConcGCThreads is pinned ON PURPOSE, and it is the flag people leave out.
REM Folia runs one tick thread per region plus Moonrise's chunk worker and I/O
REM pools, and it budgets those against the core count. G1's CONCURRENT marking
REM threads run at the same time as region ticks - they are not covered by any
REM stop-the-world pause. Left unset, ConcGCThreads defaults to
REM ceil(ParallelGCThreads / 4) and ParallelGCThreads scales with core count, so
REM on a large box G1 quietly takes cores away from region threads mid-tick and
REM the result looks like random MSPT spikes. Pin it, and count it as spent when
REM you size threaded-regions / chunk-system threads.
REM 2 suits a 4-8 GB heap; go to 3-4 only well above that.
set "JVM_ARGS=%JVM_ARGS% -XX:+UseG1GC -XX:ConcGCThreads=%CONC_GC_THREADS%"

REM --- Heap and safepoint behaviour -----------------------------------------
REM AlwaysPreTouch: fault in every heap page at startup so the OS commits it up
REM   front. Boot is slower and the process shows its full heap as RSS
REM   immediately, but no player ever pays a page-fault stall mid-tick.
REM PerfDisableSharedMem: stop the JVM writing its perf counters to the shared
REM   memory file. That file is written at safepoints, so slow storage there
REM   turns into server-wide pauses. Trade-off: jps/jstat/VisualVM can no longer
REM   auto-discover this process (JMX still works if you enable it).
REM DisableExplicitGC: ignore System.gc() calls from plugins, which would
REM   otherwise force a full stop-the-world collection.
set "JVM_ARGS=%JVM_ARGS% -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem"
set "JVM_ARGS=%JVM_ARGS% -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC"

REM --- Aikar's G1 tuning, sized for a 4-8 GB heap ---------------------------
REM The widely used Minecraft G1 profile: a large, aggressively collected young
REM generation so short-lived per-tick garbage never gets promoted, a low
REM occupancy trigger so concurrent marking starts early instead of degenerating
REM into a full GC, and MaxTenuringThreshold=1 so objects that do survive move to
REM the old generation immediately rather than being copied between survivor
REM spaces on every collection.
REM Above ~12 GB of heap, retune: G1NewSizePercent=40, G1MaxNewSizePercent=50,
REM G1HeapRegionSize=16M, G1ReservePercent=15, InitiatingHeapOccupancyPercent=20.
set "JVM_ARGS=%JVM_ARGS% -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
set "JVM_ARGS=%JVM_ARGS% -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40"
set "JVM_ARGS=%JVM_ARGS% -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20"
set "JVM_ARGS=%JVM_ARGS% -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4"
set "JVM_ARGS=%JVM_ARGS% -XX:InitiatingHeapOccupancyPercent=15"
set "JVM_ARGS=%JVM_ARGS% -XX:G1MixedGCLiveThresholdPercent=90"
set "JVM_ARGS=%JVM_ARGS% -XX:G1RSetUpdatingPauseTimePercent=5"
set "JVM_ARGS=%JVM_ARGS% -XX:SurvivorRatio=32 -XX:MaxTenuringThreshold=1"

REM --- Markers --------------------------------------------------------------
REM Informational only: they tell support channels which flag set you are on.
set "JVM_ARGS=%JVM_ARGS% -Dusing.aikars.flags=https://mcflags.emc.gs"
set "JVM_ARGS=%JVM_ARGS% -Daikars.new.flags=true"

java %JVM_ARGS% -jar "%JAR%" --nogui

pause
