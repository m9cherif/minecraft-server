#!/usr/bin/env bash
# Shared helpers for Baarcha MC server scripts. Source this, don't execute it.

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$APP_DIR/server"
JRE_DIR="$APP_DIR/jre"
JAVA_BIN=""
PAPER_VERSION=""

log() { echo "[baarcha-mc] $(date '+%Y-%m-%dT%H:%M:%S%z') $*"; }

# Trim one log past 1MB: copy-truncate so a running writer keeps its fd valid
# (an mv would leave the JVM appending into the renamed file). Backup is
# overwritten each time, so a log costs at most ~2x the trim size.
rotate_log() {
  local f="$1"
  [ -f "$f" ] || return 0
  [ "$(stat -c%s "$f" 2>/dev/null || echo 0)" -gt "${LOG_ROTATE_BYTES:-1048576}" ] || return 0
  cp -- "$f" "$f.1" 2>/dev/null
  : > "$f"
  # Prune older plain-file generations beyond the single backup.
  find "$(dirname "$f")" -name "$(basename "$f").[0-9]*" -type f 2>/dev/null |
    sort -r | tail -n +2 | xargs -r rm -f --
}

find_java() {
  if [ -x "$JRE_DIR/bin/java" ]; then
    JAVA_BIN="$JRE_DIR/bin/java"
    return 0
  fi
  if command -v java >/dev/null 2>&1; then
    local v
    v="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    case "$v" in ''|*[!0-9]*) return 1;; esac
    if [ "$v" -ge 17 ] 2>/dev/null; then
      JAVA_BIN="$(command -v java)"
      return 0
    fi
  fi
  return 1
}

# Download a Temurin userland JRE when no usable system Java exists.
install_java() {
  if find_java; then
    log "using existing java: $JAVA_BIN"
    return 0
  fi
  mkdir -p "$JRE_DIR.tmp"
  log "no usable Java found, downloading Temurin JRE..."
  # Paper's current line needs 25+; adoptium serves the GA build for that major.
  if ! curl -fL --retry 3 -o "$JRE_DIR.tmp/jre.tar.gz" \
       "https://api.adoptium.net/v3/binary/latest/${TEMURIN_MAJOR:-25}/ga/linux/x64/jre/hotspot/normal/eclipse"; then
    rm -rf "$JRE_DIR.tmp"
    log "ERROR: Temurin download failed; install Java 17+ manually."
    return 1
  fi
  tar xzf "$JRE_DIR.tmp/jre.tar.gz" -C "$JRE_DIR.tmp"
  local inner
  inner="$(find "$JRE_DIR.tmp" -maxdepth 1 -mindepth 1 -type d -name '*jdk*' | head -1)"
  [ -n "$inner" ] || inner="$(find "$JRE_DIR.tmp" -maxdepth 1 -mindepth 1 -type d ! -path "$JRE_DIR.tmp" | head -1)"
  rm -rf "$JRE_DIR"
  mv "$inner" "$JRE_DIR"
  rm -rf "$JRE_DIR.tmp"
  find_java || { log "ERROR: installed JRE still not runnable"; return 1; }
  "$JAVA_BIN" -version >/dev/null 2>&1 || { log "ERROR: $JAVA_BIN is not executable"; return 1; }
  log "installed java at $JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1))"
  return 0
}

# Latest stable Paper build via the Fill v3 API (v2 api.papermc.io is retired / 410).
fetch_paper() {
  # Keep an intact jar across reboots; set MC_FORCE_UPDATE=1 to refresh.
  if [ -s "$SERVER_DIR/paper.jar" ] && [ ! -n "${MC_FORCE_UPDATE:-}" ]; then
    log "paper.jar already present ($(du -h "$SERVER_DIR/paper.jar" | cut -f1)) — skipping download"
    PAPER_VERSION="$(cat "$SERVER_DIR/paper.version" 2>/dev/null)"
    return 0
  fi
  local latest payload url
  latest="$(curl -fsSL --retry 3 "https://fill.papermc.io/v3/projects/paper/versions?channel=stable" 2>/dev/null | jq -r '.versions[0].version.id // empty' 2>/dev/null)"
  if [ -z "$latest" ]; then
    latest="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper" | jq -r '.versions | keys[-1] // empty')"
  fi
  [ -n "$latest" ] || { log "ERROR: could not resolve latest Paper version"; return 1; }
  PAPER_VERSION="$latest"

  payload="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$latest/builds/latest")" || return 1
  url="$(echo "$payload" | jq -r '.downloads["server:default"].url // empty')"
  [ -n "$url" ] || { log "ERROR: no download url for Paper $latest"; return 1; }

  local want_sum got_sum=ok
  want_sum="$(echo "$payload" | jq -r '.downloads["server:default"].checksums.sha256 // empty')"
  log "downloading Paper $latest -> paper.jar"
  curl -fL --retry 3 -o "$SERVER_DIR/paper.jar.part" "$url" || return 1
  if [ -n "$want_sum" ]; then
    got_sum="$(sha256sum "$SERVER_DIR/paper.jar.part" | cut -d' ' -f1)"
    [ "$got_sum" = "$want_sum" ] || { log "ERROR: checksum mismatch for paper.jar"; return 1; }
  fi
  mv "$SERVER_DIR/paper.jar.part" "$SERVER_DIR/paper.jar"
  echo "$latest" > "$SERVER_DIR/paper.version"
  log "paper.jar ready ($(du -h "$SERVER_DIR/paper.jar" | cut -f1)) sha256=$got_sum"
}

write_eula_and_props() {
  mkdir -p "$SERVER_DIR/logs"
  if [ ! -s "$SERVER_DIR/eula.txt" ]; then
    cat > "$SERVER_DIR/eula.txt" <<'EOF'
#Accepted by setup script (https://aka.ms/MinecraftEULA)
eula=true
EOF
    log "eula.txt accepted"
  fi

  # Tune properties before first boot; preserve any user edits except these keys.
  local props="$SERVER_DIR/server.properties" tmp="$SERVER_DIR/.properties.tmp"
  touch "$props"
  cp "$props" "$tmp"
  set_prop() {
    local k="$1" v="$2"
    if grep -qE "^$k=" "$tmp"; then sed -i "s|^$k=.*|$k=$v|" "$tmp"; else printf '%s=%s\n' "$k" "$v" >> "$tmp"; fi
  }
  set_prop online-mode false
  set_prop motd "Baarcha MC"
  set_prop max-players 1000
  set_prop gamemode survival
  set_prop difficulty normal
  set_prop view-distance 8
  set_prop server-port 25565
  sort -o "$tmp" "$tmp"
  mv "$tmp" "$props"
  log "server.properties tuned (online-mode=false, max-players=1000)"
}
