#!/bin/bash
# Launch the Fabric server. Run ./setup.sh once before the first start.
set -euo pipefail
cd "$(dirname "$0")"

# Heap. Aikar's flags want Xms == Xmx: the GC is tuned for a fixed heap, and
# letting it grow just means paying to re-expand what you already reserved.
# On a 16 GB machine leave a few GB for the OS, page cache and the JVM's own
# off-heap use — 12G is the safe number, raise it only on a bigger box.
HEAP="${HEAP:-12G}"
JAR="${JAR:-server.jar}"
RESTART_ON_CRASH="${RESTART_ON_CRASH:-true}"

if [ ! -f "$JAR" ]; then
  echo "$JAR not found. Run ./setup.sh first." >&2
  exit 1
fi

# Aikar's flags (https://docs.papermc.io/paper/aikar-flags), >12 GB variant:
# a larger young gen and 16M regions, so big heaps collect in short pauses
# instead of one long stop-the-world.
JVM_FLAGS=(
  -Xms"$HEAP" -Xmx"$HEAP"
  -XX:+UseG1GC
  -XX:+ParallelRefProcEnabled
  -XX:MaxGCPauseMillis=200
  -XX:+UnlockExperimentalVMOptions
  -XX:+DisableExplicitGC
  -XX:+AlwaysPreTouch
  -XX:G1NewSizePercent=40
  -XX:G1MaxNewSizePercent=50
  -XX:G1HeapRegionSize=16M
  -XX:G1ReservePercent=15
  -XX:G1HeapWastePercent=5
  -XX:G1MixedGCCountTarget=4
  -XX:InitiatingHeapOccupancyPercent=20
  -XX:G1MixedGCLiveThresholdPercent=90
  -XX:G1RSetUpdatingPauseTimePercent=5
  -XX:SurvivorRatio=32
  -XX:+PerfDisableSharedMem
  -XX:MaxTenuringThreshold=1
  -Dusing.aikars.flags=https://mcflags.emc.gs
  -Daikars.new.flags=true
)

run() {
  echo "==> Starting server with $HEAP heap"
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
