#!/usr/bin/env bash
# Start the Paper server headless. Safe to re-run: exits if already running.
#
# Console input: stdin is fed by `tail -n0 -f console.cmds`, so any process
# (dashboard, operator, scripts) can run a command by appending one line to
# that plain file — no FIFO locking, no lost bytes on restart.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

PID_FILE="$SERVER_DIR/java.pid"
CMDS_FILE="$SERVER_DIR/console.cmds"
[ -f "$SERVER_DIR/paper.jar" ] || { log "paper.jar missing — run scripts/setup-server.sh first"; exit 1; }
mkdir -p "$SERVER_DIR/logs"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  log "server already running (pid $(cat "$PID_FILE"))"
  exit 0
fi

# Heap sizing: honour explicit overrides, else fit inside the container's cgroup
# limit so java + node + playit never trip the OOM killer.
MC_XMS="${MC_XMS:-}"
MC_XMX="${MC_XMX:-}"
if [ -z "$MC_XMX" ]; then
  mem_max="$(cat /sys/fs/cgroup/memory.max 2>/dev/null)"
  case "$mem_max" in ''|max) mem_max=2147483648;; esac
  heap=$(( mem_max * 70 / 100 ))
  [ "$heap" -lt 536870912 ] && heap=536870912
else
  heap="$MC_XMX"
fi
# Default both boundaries to the computed heap unless individually overridden.
[ -z "$MC_XMS" ] && MC_XMS="$heap"
[ -z "$MC_XMX" ] && MC_XMX="$heap"

find_java || { log "no java available — run scripts/setup-server.sh"; exit 1; }

# Required properties are re-enforced on EVERY startup (task constraint):
# rewrite the line if present, append when missing. Other user edits survive.
enforce_props() {
  local props="$SERVER_DIR/server.properties" tmp="$SERVER_DIR/.props.tmp"
  [ -f "$props" ] || touch "$props"
  cp "$props" "$tmp"
  enforce_one() {
    if grep -qE "^$1=" "$tmp"; then sed -i "s|^$1=.*|$1=$2|" "$tmp"; else printf '%s=%s\n' "$1" "$2" >> "$tmp"; fi
  }
  enforce_one online-mode false
  enforce_one motd "Baarcha MC"
  enforce_one max-players 1000
  mv "$tmp" "$props"
}
enforce_props
node "$APP_DIR/scripts/gen-ops.js"

touch "$CMDS_FILE"
# Rotate-trim: keep 5 rotated ~1MB chunks + current, so days-old logs can't fill disk.
for lf in "$SERVER_DIR/logs/stdio.log" "$SERVER_DIR/logs/latest.log" \
          "$SERVER_DIR/logs/tunnel.log"; do
  rotate_log "$lf"
done

log "starting Paper ($("$JAVA_BIN" -version 2>&1 | head -1)) Xms=${MC_XMS} Xmx=${MC_XMX}"
log "starting Paper ($("$JAVA_BIN" -version 2>&1 | head -1)) Xms=${MC_XMS} Xmx=${MC_XMX}"
cd "$SERVER_DIR"
# Background pipeline: $! is the PID of the LAST element, i.e. the JVM itself,
# so restarts and dashboards act on java, not on a wrapper shell.
tail -n0 -f "$CMDS_FILE" | "$JAVA_BIN" \
  -Xms"${MC_XMS}" -Xmx"${MC_XMX}" \
  -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC \
  -jar paper.jar nogui > logs/stdio.log 2>&1 &
java_pid=$!
echo "$java_pid" > "$PID_FILE"
disown "$java_pid" 2>/dev/null

# Re-assert operators once the server is receptive; harmless if already OP.
nohup bash "$APP_DIR/scripts/op-watch.sh" >/dev/null 2>&1 &
disown 2>/dev/null

log "java pid $java_pid — console: printf 'list\n' >> $CMDS_FILE ; logs: logs/stdio.log"
