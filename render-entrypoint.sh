#!/bin/bash
# Entrypoint for the Render deployment. Minecraft speaks raw TCP; Render's
# router only proxies HTTP(S)/WebSocket. So this starts Minecraft privately
# on localhost, then puts a WebSocket tunnel (wstunnel) in front of it on
# Render's public $PORT. Players connect through a matching client-side
# tunnel — see deploy/RENDER.md.
set -euo pipefail
cd "$(dirname "$0")"

PORT="${PORT:-10000}"           # set by Render for every web service
JAVA_HEAP="${JAVA_HEAP:-384M}"  # keep well under the instance's RAM (see RENDER.md)
WSTUNNEL_SECRET="${WSTUNNEL_SECRET:?Set WSTUNNEL_SECRET in the Render dashboard before deploying}"

echo "eula=true" > eula.txt

echo "==> Starting Minecraft (heap ${JAVA_HEAP}) on 127.0.0.1:25565"
java -Xms"$JAVA_HEAP" -Xmx"$JAVA_HEAP" -jar server.jar nogui &
MC_PID=$!

echo "==> Waiting for Minecraft to accept connections..."
for _ in $(seq 1 150); do
  (exec 3<>/dev/tcp/127.0.0.1/25565) 2>/dev/null && exec 3>&- && break
  sleep 2
done

echo "==> Starting wstunnel on 0.0.0.0:${PORT}, forwarding only to 127.0.0.1:25565"
# --restrict-to locks the tunnel to this one destination, so the public
# endpoint can't be abused as a general-purpose proxy to arbitrary hosts.
# The secret path segment keeps casual scanners of the public URL from
# reaching even that: without it, requests get a plain 404.
wstunnel server \
  --restrict-to "127.0.0.1:25565" \
  --restrict-http-upgrade-path-prefix "$WSTUNNEL_SECRET" \
  "ws://0.0.0.0:${PORT}" &
WS_PID=$!

wait -n "$MC_PID" "$WS_PID"
EXIT_CODE=$?
echo "==> A process exited (code $EXIT_CODE) — stopping the other and exiting."
kill "$MC_PID" "$WS_PID" 2>/dev/null || true
exit "$EXIT_CODE"
