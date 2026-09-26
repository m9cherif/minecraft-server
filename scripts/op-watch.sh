#!/usr/bin/env bash
# Waits until Paper logs 'Done', then ensures each configured operator is OP.
# Runs alongside the server; exits after the ops are confirmed once per boot.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

CMDS_FILE="$SERVER_DIR/console.cmds"
LOG="$SERVER_DIR/logs/stdio.log"
[ -f "$LOG" ] || LOG="$SERVER_DIR/logs/latest.log"
OPS_USERS="${OPS_USERS:-m9cherif3 m9cherif m9cherif13}"

for _ in $(seq 1 120); do
  grep -q 'Done (' "$LOG" 2>/dev/null && break
  sleep 2
done

grep -q 'Done (' "$LOG" 2>/dev/null || { log "op-watch: server never reached Done"; exit 1; }

sleep 1
for name in $OPS_USERS; do
  printf 'op %s\n' "$name" >> "$CMDS_FILE"
  log "op-watch: sent 'op $name'"
done
sleep 2
# Paper wording varies: fresh op => "Made X a server operator"; existing =>
# "Nothing changed. The player is already an operator". Either confirms OP.
grep -iE 'operator' "$LOG" | tail -5 && log "op-watch: operator state confirmed" || true
