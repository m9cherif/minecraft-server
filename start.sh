#!/bin/bash
# Launch the Fabric server. Run ./setup.sh once before the first start.
set -euo pipefail
cd "$(dirname "$0")"

# Heap. Aikar's flags want Xms == Xmx: the GC is tuned for a fixed heap, and
# letting it grow just means paying to re-expand what you already reserved.
#
# 2G is the default because it is what small hosts actually have. The heap is
# not the JVM's whole footprint — metaspace, GC structures, thread stacks and
# network buffers live outside it, and the OS still needs page cache to keep
# region-file reads fast — so 2G of heap wants roughly 3 GB of machine. On a
# 2 GB box set HEAP=1400M instead; on a bigger one, raise it.
HEAP="${HEAP:-2G}"
JAR="${JAR:-server.jar}"
RESTART_ON_CRASH="${RESTART_ON_CRASH:-true}"

if [ ! -f "$JAR" ]; then
  echo "$JAR not found. Run ./setup.sh first." >&2
  exit 1
fi

# Heap size in MB, so the right flag set can be picked below. Accepts the
# forms java does: 2G, 2048M, 2097152K, or a plain byte count.
heap_mb() {
  local value="${1^^}"
  case "$value" in
    *G) echo $(( ${value%G} * 1024 )) ;;
    *M) echo "${value%M}" ;;
    *K) echo $(( ${value%K} / 1024 )) ;;
    *)  echo $(( value / 1024 / 1024 )) ;;
  esac
}
HEAP_MB=$(heap_mb "$HEAP")

# Aikar's flags (https://docs.papermc.io/paper/aikar-flags). They come in two
# shapes and using the wrong one costs real performance: above 12 GB a larger
# young generation and 16M regions keep pauses short, while below it the same
# settings starve the old generation and cause constant mixed collections.
# The threshold is picked from HEAP rather than hardcoded, so changing the heap
# does not quietly leave the flags mistuned.
JVM_FLAGS=(
  -Xms"$HEAP" -Xmx"$HEAP"
  -XX:+UseG1GC
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=200
  -XX:+UnlockExperimentalVMOptions
  -XX:+DisableExplicitGC
  -XX:+AlwaysPreTouch
  -XX:G1HeapWastePercent=5
  -XX:G1MixedGCCountTarget=4
  -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5
  -XX:SurvivorRatio=32
  -XX:+PerfDisableSharedMem
  -XX:MaxTenuringThreshold=1
  -Dusing.aikars.flags=https://mcflags.emc.gs
  -Daikars.new.flags=true
)

if [ "$HEAP_MB" -ge 12288 ]; then
  JVM_FLAGS+=(
    -XX:G1NewSizePercent=40
    -XX:G1MaxNewSizePercent=50
    -XX:G1HeapRegionSize=16M
    -XX:G1ReservePercent=15
    -XX:InitiatingHeapOccupancyPercent=20
  )
else
  JVM_FLAGS+=(
    -XX:G1NewSizePercent=30
    -XX:G1MaxNewSizePercent=40
    -XX:G1HeapRegionSize=8M
    -XX:G1ReservePercent=20
    -XX:InitiatingHeapOccupancyPercent=15
  )
fi

run() {
  echo "==> Starting server with $HEAP heap (${HEAP_MB} MB)"
  java "${JVM_FLAGS[@]}" -jar "$JAR" nogui
}

if [ "$RESTART_ON_CRASH" != "true" ]; then
  exec java "${JVM_FLAGS[@]}" -jar "$JAR" nogui
fi

# Supervisor loop. A clean shutdown (/stop, or exit code 0) ends the loop;
# a crash restarts after a short pause. Ctrl-C stops the loop, not just the JVM.
trap 'echo; echo "==> Shutting down."; exit 0' INT TERM

while true; do
  set +e
  run
  code=$?
  set -e

  if [ "$code" -eq 0 ]; then
    echo "==> Server stopped cleanly."
    break
  fi

  echo "==> Server exited with code $code — restarting in 10s (Ctrl-C to stop)."
  sleep 10
done
