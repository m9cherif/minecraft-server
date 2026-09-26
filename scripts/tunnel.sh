#!/usr/bin/env bash
# playit.gg tunnel: gives players a public TCP address that forwards to the
# local Paper port. Free-account friendly (no credit card), unlike the old
# cloudflared setups replaced 2026-09-17. Runs whenever the binary starts
#
#   - PLAYIT_SECRET_KEY set  -> agent joins the account straight away.
#   - secret file or its backup exists -> reconnect, never claim again.
#   - neither                 -> agent prints a claim URL; linking the agent
#                               once in the browser provisions the secret.
#
# The secret file is sacred once it exists: NOTHING in this script ever
# deletes, rotates or moves it, so a claimed box never shows a claim link again.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

BIN="$APP_DIR/playit"
PID_FILE="$APP_DIR/playit.pid"
ADDR_FILE="$APP_DIR/PLAYIT_ADDRESS"
CLAIM_FILE="$APP_DIR/CLAIM_URL"
LOG="$SERVER_DIR/logs/tunnel.log"
MC_PORT="${MC_PORT:-25565}"
PLAYIT_VERSION="v0.15.26"
DOWNLOAD_URL="https://github.com/playit-cloud/playit-agent/releases/download/${PLAYIT_VERSION}/playit-linux-amd64"

mkdir -p "$SERVER_DIR/logs"

if [ ! -x "$BIN" ]; then
  log "downloading playit agent (${PLAYIT_VERSION})..."
  curl -fL --retry 3 -o "$BIN.part" "$DOWNLOAD_URL" || {
    rm -f "$BIN.part"
    log "ERROR: playit download failed; retry or install it manually."
    exit 1
  }
  mv "$BIN.part" "$BIN" && chmod +x "$BIN"
fi

[ -x "$BIN" ] || { log "binary not executable"; exit 1; }

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  log "playit agent already up (pid $(cat "$PID_FILE"))"
  exit 0
fi

rm -f "$ADDR_FILE"
SECRET_FILE="$SERVER_DIR/playit.secret.toml"
SECRET_BACKUP="$APP_DIR/config/playit-secret.backup.toml"

# Secret persistence ("never claim again"): once the box is claimed the secret
# must survive agent restarts AND machine resets, so every startup keeps a
# second copy at config/playit-secret.backup.toml. If the live file was lost
# but the backup survived (partial snapshot/remix), restore it instead of
# falling back into claim mode — a new claim code would orphan the tunnel.
mkdir -p "$(dirname "$SECRET_BACKUP")"
if [ -s "$SECRET_FILE" ] && ! cmp -s "$SECRET_FILE" "$SECRET_BACKUP"; then
  cp "$SECRET_FILE" "$SECRET_BACKUP" &&
    log "refreshed secret backup at $SECRET_BACKUP"
elif [ ! -s "$SECRET_FILE" ] && [ -s "$SECRET_BACKUP" ]; then
  cp "$SECRET_BACKUP" "$SECRET_FILE" &&
    log "restored missing secret from $SECRET_BACKUP (no re-claim needed)"
fi

# Ways the agent binds to a playit account (first match wins):
#  - both files absent: claim mode. The agent prints
#    https://playit.gg/claim/<code> and waits; approving that link in a browser
#    provisions the secret, which it then saves under SECRET_FILE so later
#    boots connect on their own. This is the ONLY path that ever claims.
#  - PLAYIT_SECRET_KEY env (Keys panel) -> handed to the agent directly.
#  - SECRET_FILE / its backup (restored above) -> always reconnects.
unset RUN_ARGS CLAIMING=0
if [ -n "${PLAYIT_SECRET_KEY:-}" ]; then
  log "starting playit agent from PLAYIT_SECRET_KEY -> tcp://localhost:${MC_PORT}"
  RUN_ARGS=(--secret "$PLAYIT_SECRET_KEY")
elif [ -s "$SECRET_FILE" ]; then
  log "starting playit agent with saved secret ($SECRET_FILE) -> tcp://localhost:${MC_PORT} (no claim mode)"
else
  CLAIMING=1
  log "no secret on disk anywhere — starting playit agent in claim mode."
  log "THIS IS THE ONLY TIME A CLAIM LINK IS SHOWN; approve it once and it's kept forever (backed up each boot)."
fi

"$BIN" ${RUN_ARGS+"${RUN_ARGS[@]}"} --secret_path "$SECRET_FILE" \
  >> "$LOG" 2>&1 &
echo "$!" > "$PID_FILE"
disown "$(cat "$PID_FILE")" 2>/dev/null
log "playit pid $(cat "$PID_FILE"); see $LOG for agent/claim status"

# Watcher: skim the agent log so a claim URL lands in CLAIM_URL ONLY while
# genuinely unclaimed (agent started with no secret at all), and
# PLAYIT_ADDRESS updates the moment playit allocates an endpoint. Once a
# secret exists anywhere, claims are history: stale claim files get cleared
# and no new one is ever written, whatever the agent prints.
(
  for _ in $(seq 1 60); do
    # Claim mode background file from an earlier boot must not resurface.
    if [ "${CLAIMING:-0}" = "1" ] && [ -z "${PLAYIT_SECRET_KEY:-}" ] &&
       [ ! -s "$SECRET_FILE" ]; then
      claim="$(sed 's/\x1b\[[0-9;]*m//g' "$LOG" 2>/dev/null |
               grep -oE 'https://playit\.gg/claim/[A-Za-z0-9_-]+' | tail -1)"
      if [ -n "$claim" ]; then
        printf '%s\n' "$claim" > "$CLAIM_FILE"
        log "claim url captured: $claim"
      fi
    else
      # Claimed boot (secret file or env key): retire any lingering claim
      # link, and keep the disk-secret backup fresh the moment it lands.
      if [ -s "$SECRET_FILE" ] && ! cmp -s "$SECRET_FILE" "$SECRET_BACKUP"; then
        cp "$SECRET_FILE" "$SECRET_BACKUP" &&
          log "refreshed secret backup at $SECRET_BACKUP"
      fi
      [ -e "$CLAIM_FILE" ] && rm -f "$CLAIM_FILE" && log "secret present — claim link retired"
    fi
    # Preferred source: ask the agent itself. v0.15.26's TUNNELS log banner
    # prints the bare hostname WITHOUT the allocated port, so the old
    # host:port log grep never matched and PLAYIT_ADDRESS stayed empty.
    addr=""
    if [ -s "$SECRET_FILE" ] && [ -x "$BIN" ]; then
      j="$("$BIN" --secret_path "$SECRET_FILE" tunnels list 2>/dev/null)"
      if [ -n "$j" ]; then
        dom="$(printf '%s\n' "$j" | grep -oE '"assigned_domain": "[^"]+"' | head -1 |
              sed 's/.*"\([^"]*\)"$/\1/')"
        prt="$(printf '%s\n' "$j" | grep -oE '"port_start": [0-9]+' | head -1 |
              grep -oE '[0-9]+')"
        [ -n "$dom" ] && addr="${dom}:${prt:-$MC_PORT}"
      fi
    fi
    # Fallback: log banner (bare hostname; SRV records cover the port).
    if [ -z "$addr" ]; then
      host="$(sed 's/\x1b\[[0-9;]*m//g' "$LOG" 2>/dev/null |
              grep -oE '([a-zA-Z0-9-]+\.)+(playit\.gg|ply\.gg)' | tail -1)"
      [ -n "$host" ] && addr="$host"
    fi
    if [ -n "$addr" ] && [ "$addr" != "$(cat "$ADDR_FILE" 2>/dev/null)" ]; then
      printf '%s\n' "$addr" > "$ADDR_FILE"
      log "public address detected: $addr"
    fi
    # Log resets on every restart; stop once our agent is gone.
    kill -0 "$(cat "$PID_FILE")" 2>/dev/null || exit 0
    sleep 5
  done
) >/dev/null 2>&1 &
