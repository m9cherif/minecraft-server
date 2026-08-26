#!/bin/bash
set -euo pipefail

PAPER_VERSION="${PAPER_VERSION:-26.1.2}"
PAPER_BUILD="${PAPER_BUILD:-69}"
PAPER_SHA256="${PAPER_SHA256:-d30fae0c74092b10855f0412ca6b265c60301a013d34bc28a2a41bf5682dd80b}"
PAPER_URL="${PAPER_URL:-https://fill-data.papermc.io/v1/objects/${PAPER_SHA256}/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar}"

JAVA_MIN_HEAP="${JAVA_MIN_HEAP:-64M}"
JAVA_MAX_HEAP="${JAVA_MAX_HEAP:-256M}"

# خادم HTTP بسيط لـ Render — Render only keeps the service alive if a port is bound.
python3 -m http.server "${PORT:-10000}" &

if [ ! -f server.jar ]; then
  echo "Downloading Paper ${PAPER_VERSION} build ${PAPER_BUILD}..."
  curl -fSL "$PAPER_URL" -o server.jar.tmp
  mv server.jar.tmp server.jar
fi

# The image ships an eula.txt; recreate it only if it went missing (e.g. fresh volume).
[ -f eula.txt ] || echo "eula=true" > eula.txt

exec java -Xms"$JAVA_MIN_HEAP" -Xmx"$JAVA_MAX_HEAP" -jar server.jar nogui
