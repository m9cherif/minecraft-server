#!/usr/bin/env bash
# Stop the Paper server gracefully. Safe to run when already stopped.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

PID_FILE="$SERVER_DIR/java.pid"
[ -f "$PID_FILE" ] || { log "no pid file — nothing to stop"; exit 0; }
pid="$(cat "$PID_FILE")"
kill -0 "$pid" 2>/dev/null || { rm -f "$PID_FILE"; log "not running"; exit 0; }

log "stopping java pid $pid"
# SIGTERM lets Paper flush worlds and save players before exiting.
kill "$pid" 2>/dev/null
for _ in $(seq 1 30); do
  sleep 1
  kill -0 "$pid" 2>/dev/null || break
done
if kill -0 "$pid" 2>/dev/null; then
  log "force killing pid $pid"
  kill -9 "$pid" 2>/dev/null
fi
rm -f "$PID_FILE"
log "stopped"
