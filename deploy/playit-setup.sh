#!/bin/bash
# Set up playit.gg on this machine, so players can reach the server without
# forwarding a port.
#
# What this does for you:
#   1. installs the playit agent (APT repo where available, binary otherwise)
#   2. links it to your playit.gg account
#   3. stores the secret key with sane permissions
#   4. enables the systemd service so it comes back after a reboot
#
# Two steps cannot be automated, and the script stops and waits at each:
#   * approving the agent in a browser — it is tied to your account, not this box
#   * creating the tunnel itself, which is done on the playit.gg website
#
#   sudo ./deploy/playit-setup.sh
set -euo pipefail

SECRET_DIR="/etc/playit"
SECRET_FILE="$SECRET_DIR/playit.toml"
BIN_DIR="/usr/local/bin"
RELEASE_BASE="https://github.com/playit-cloud/playit-agent/releases/latest/download"
GAME_PORT="${GAME_PORT:-25565}"

say()  { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m    %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31mError: %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run this with sudo: sudo $0"
command -v curl >/dev/null || die "curl is not installed."

# ---------------------------------------------------------------- 1. install
find_playit() { command -v playit 2>/dev/null || command -v "$BIN_DIR/playit" 2>/dev/null || true; }

install_from_apt() {
  say "Installing playit from the official APT repository"
  # The repo is signed; the key goes in trusted.gpg.d so apt verifies packages
  # normally and future upgrades come through apt like anything else.
  #
  # Built in a temp file and moved into place only once both the download and
  # the dearmor succeeded. Redirecting straight at the destination leaves an
  # empty keyring behind when the network fails, and apt then reports a signing
  # error on every later run — a confusing way to fail at a lost connection.
  local key_tmp
  key_tmp=$(mktemp)
  # shellcheck disable=SC2064
  trap "rm -f '$key_tmp'" RETURN

  if ! curl -fsSL --retry 3 https://playit-cloud.github.io/ppa/key.gpg | gpg --dearmor > "$key_tmp"; then
    die "Could not download playit's signing key. Check this machine's internet access."
  fi
  [ -s "$key_tmp" ] || die "playit's signing key came back empty — refusing to install unverified."

  install -m 0644 "$key_tmp" /etc/apt/trusted.gpg.d/playit.gpg
  echo "deb [signed-by=/etc/apt/trusted.gpg.d/playit.gpg] https://playit-cloud.github.io/ppa/data ./" \
    > /etc/apt/sources.list.d/playit-cloud.list
  apt-get update -qq
  apt-get install -y playit
}

install_from_binary() {
  # No package for this distro. Fall back to the release binary for this CPU.
  local arch asset
  arch=$(uname -m)
  case "$arch" in
    x86_64|amd64)   asset=playit-linux-amd64 ;;
    aarch64|arm64)  asset=playit-linux-aarch64 ;;
    armv7l|armv7)   asset=playit-linux-armv7 ;;
    i686|i386)      asset=playit-linux-i686 ;;
    *) die "No playit build for CPU architecture '$arch'." ;;
  esac

  say "Installing playit binary for $arch"
  curl -fSL --retry 3 "$RELEASE_BASE/$asset" -o "$BIN_DIR/playit.tmp"
  chmod +x "$BIN_DIR/playit.tmp"
  mv "$BIN_DIR/playit.tmp" "$BIN_DIR/playit"
  echo "    $BIN_DIR/playit"
}

PLAYIT=$(find_playit)
if [ -n "$PLAYIT" ]; then
  say "playit is already installed at $PLAYIT"
elif command -v apt-get >/dev/null && command -v gpg >/dev/null; then
  install_from_apt
  PLAYIT=$(find_playit)
else
  [ -x /usr/bin/gpg ] || warn "gpg not found — using the release binary instead of the APT repo."
  install_from_binary
  PLAYIT=$(find_playit)
fi
[ -n "$PLAYIT" ] || die "playit was installed but is not on PATH."

# ------------------------------------------------------------------ 2. claim
# The secret key is what ties this machine to your playit account. If one is
# already stored, this run is an upgrade or a re-run and there is nothing to do.
if [ -s "$SECRET_FILE" ] && grep -q 'secret_key' "$SECRET_FILE"; then
  say "Already linked to a playit account ($SECRET_FILE) — keeping it"
else
  say "Linking this machine to your playit.gg account"

  CLAIM_CODE=$("$PLAYIT" claim generate | tr -d '[:space:]')
  [ -n "$CLAIM_CODE" ] || die "playit did not return a claim code."

  cat <<CLAIM

  ------------------------------------------------------------------
   Open this link in a browser and approve the agent:

       https://playit.gg/claim/$CLAIM_CODE

   Sign in (or create a free account), then click through the setup.
   This script waits here until you have approved it.
  ------------------------------------------------------------------

CLAIM

  # `claim exchange` blocks until the browser approval lands, then prints the
  # permanent secret key. Mirror its output to the terminal so a wait is
  # visible rather than looking like a frozen script — but only when there is
  # a terminal to mirror to. Piping unconditionally through `tee /dev/tty`
  # kills the script under `set -o pipefail` the moment it is run without one
  # (`ssh host ./playit-setup.sh`, a cron entry), and the failure gives no hint
  # that a missing tty was the cause.
  if [ -t 1 ] && [ -w /dev/tty ]; then
    EXCHANGE_OUTPUT=$("$PLAYIT" claim exchange "$CLAIM_CODE" | tee /dev/tty)
  else
    EXCHANGE_OUTPUT=$("$PLAYIT" claim exchange "$CLAIM_CODE")
  fi
  SECRET_KEY=$(grep -oE '[0-9a-f]{32,}' <<<"$EXCHANGE_OUTPUT" | head -1)

  if [ -z "$SECRET_KEY" ]; then
    warn "Could not read the secret key out of playit's output."
    warn "Copy the long hex string it printed and paste it here."
    read -r -p "    secret key: " SECRET_KEY
    SECRET_KEY=$(tr -d '[:space:]' <<<"$SECRET_KEY")
  fi
  [ -n "$SECRET_KEY" ] || die "No secret key — nothing was saved."

  # 0600 root-only: anyone who reads this file can impersonate your agent.
  mkdir -p "$SECRET_DIR"
  umask 077
  printf 'secret_key = "%s"\n' "$SECRET_KEY" > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  say "Secret saved to $SECRET_FILE (root-only)"
fi

# ---------------------------------------------------------------- 3. service
if command -v systemctl >/dev/null; then
  if systemctl list-unit-files 2>/dev/null | grep -q '^playit\.service'; then
    say "Enabling playit.service"
    systemctl daemon-reload
    systemctl enable --now playit.service
    sleep 2
    systemctl --no-pager --lines=5 status playit.service || true
  else
    # Binary install: no unit shipped, so write one that matches the units in
    # this directory — same restart policy, same hardening.
    say "Installing playit.service"
    cat > /etc/systemd/system/playit.service <<UNIT
[Unit]
Description=playit.gg tunnel agent
Documentation=https://playit.gg/
After=network-online.target
Wants=network-online.target

StartLimitIntervalSec=0

[Service]
Type=simple
ExecStart=$PLAYIT --secret_path $SECRET_FILE start
Restart=always
RestartSec=10

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/log
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
UNIT
    systemctl daemon-reload
    systemctl enable --now playit.service
    sleep 2
    systemctl --no-pager --lines=5 status playit.service || true
  fi
else
  warn "No systemd here. Run it yourself: $PLAYIT --secret_path $SECRET_FILE start"
fi

# ------------------------------------------------------------------ 4. tunnel
cat <<NEXT

$(printf '\033[1m')Almost there — one step left, and it is on the website.$(printf '\033[0m')

  1. Go to  https://playit.gg/account/tunnels
  2. "Add Tunnel"  ->  Minecraft Java  (or: TCP, port $GAME_PORT)
  3. Point it at the local address  127.0.0.1:$GAME_PORT
  4. Copy the address it gives you — something like  yourname.joinmc.link

That address is what players type into Minecraft. Check it works with:

  node web/bin/mc-ping.js yourname.joinmc.link

The tunnel is only up while playit.service is running:

  systemctl status playit
  journalctl -u playit -f

NEXT
