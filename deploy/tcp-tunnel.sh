#!/bin/bash
# Publish this server's TCP port on a machine that has a public IP.
#
# The problem this solves: the server runs somewhere players cannot reach — behind
# CGNAT, a router you do not control, a firewall, or a host that only proxies
# HTTP. The fix is an SSH reverse tunnel to any box with a public address (a $4
# VPS, a friend's server, a free-tier cloud instance). SSH opens the listening
# port on that box and carries the traffic back here.
#
# What makes this different from the WebSocket tunnel in web/: this is REAL TCP
# on the relay's public port. Players type the relay's address into Minecraft and
# join. Nothing is installed on their machines, and vanilla clients work.
#
#   RELAY=user@1.2.3.4 ./deploy/tcp-tunnel.sh
#
# Requirements on the relay, once:
#   1. Your SSH key in ~/.ssh/authorized_keys (this script never types a password).
#   2. `GatewayPorts clientspecified` in /etc/ssh/sshd_config, then restart sshd.
#      Without it SSH binds the forwarded port to the relay's loopback only and
#      nobody outside can reach it — this is the step people miss.
#   3. Port 25565/TCP open in the relay's firewall and cloud security group.
set -euo pipefail
cd "$(dirname "$0")/.."

RELAY="${RELAY:-}"                       # user@host of the public machine
RELAY_PORT="${RELAY_PORT:-25565}"        # port players connect to on the relay
RELAY_BIND="${RELAY_BIND:-0.0.0.0}"      # 0.0.0.0 = reachable from the internet
LOCAL_HOST="${LOCAL_HOST:-127.0.0.1}"    # where the Minecraft server is
LOCAL_PORT="${LOCAL_PORT:-25565}"
SSH_PORT="${SSH_PORT:-22}"
SSH_KEY="${SSH_KEY:-}"                   # optional explicit identity file
RETRY_SECONDS="${RETRY_SECONDS:-10}"

if [ -z "$RELAY" ]; then
  cat >&2 <<'USAGE'
RELAY is not set.

  RELAY=user@your-vps.example.com ./deploy/tcp-tunnel.sh

Optional:
  RELAY_PORT=25565     port players connect to on the relay
  RELAY_BIND=0.0.0.0   bind address on the relay (127.0.0.1 to keep it private)
  LOCAL_PORT=25565     the Minecraft port on this machine
  SSH_PORT=22          SSH port of the relay
  SSH_KEY=~/.ssh/id_ed25519
USAGE
  exit 1
fi

command -v ssh >/dev/null || { echo "ssh is not installed." >&2; exit 1; }

SSH_OPTS=(
  -N                                  # forward only; do not run a shell
  -p "$SSH_PORT"
  -o ExitOnForwardFailure=yes         # fail loudly if the port cannot be opened
  -o ServerAliveInterval=30           # notice a dead link in ~90s...
  -o ServerAliveCountMax=3            # ...instead of hanging until TCP gives up
  -o BatchMode=yes                    # never prompt for a password
  -o StrictHostKeyChecking=accept-new
  -o TCPKeepAlive=yes
)
[ -n "$SSH_KEY" ] && SSH_OPTS+=(-i "$SSH_KEY")

FORWARD="${RELAY_BIND}:${RELAY_PORT}:${LOCAL_HOST}:${LOCAL_PORT}"

echo "==> Tunnel: ${RELAY} port ${RELAY_PORT} -> ${LOCAL_HOST}:${LOCAL_PORT}"
echo "==> Players connect to: ${RELAY#*@}$([ "$RELAY_PORT" = 25565 ] || echo ":${RELAY_PORT}")"
echo

trap 'echo; echo "==> Tunnel stopped."; exit 0' INT TERM

while true; do
  set +e
  ssh "${SSH_OPTS[@]}" -R "$FORWARD" "$RELAY"
  code=$?
  set -e

  # 255 is SSH's own "something went wrong" code. The common causes are worth
  # naming, because the failure is otherwise silent and looks like a bad key.
  if [ "$code" -eq 255 ]; then
    echo "==> SSH failed. Check, in this order:" >&2
    echo "    - key auth works:   ssh -p $SSH_PORT $RELAY true" >&2
    echo "    - GatewayPorts clientspecified is set in the relay's sshd_config" >&2
    echo "    - port $RELAY_PORT is free on the relay and open in its firewall" >&2
  fi

  echo "==> Disconnected (exit $code) — reconnecting in ${RETRY_SECONDS}s (Ctrl-C to stop)."
  sleep "$RETRY_SECONDS"
done
