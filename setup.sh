#!/bin/bash
# One-time setup: installs the Fabric server and the mods that must be downloaded.
# Safe to re-run — it re-resolves the newest compatible builds each time.
set -euo pipefail
cd "$(dirname "$0")"

MC_VERSION="${MC_VERSION:-26.2}"

need() { command -v "$1" >/dev/null || { echo "Missing required command: $1" >&2; exit 1; }; }
need curl
need jq
need java

echo "==> Minecraft $MC_VERSION (Fabric)"

# ---------------------------------------------------------------- Fabric server
# meta.fabricmc.net serves a launcher jar that pulls the loader + vanilla server.
LOADER_VERSION="${LOADER_VERSION:-$(curl -fsSL "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION" \
  | jq -r '[.[] | select(.loader.stable == true)][0].loader.version')}"
INSTALLER_VERSION="${INSTALLER_VERSION:-$(curl -fsSL "https://meta.fabricmc.net/v2/versions/installer" \
  | jq -r '[.[] | select(.stable == true)][0].version')}"

if [ -z "$LOADER_VERSION" ] || [ "$LOADER_VERSION" = "null" ]; then
  echo "No stable Fabric loader for Minecraft $MC_VERSION yet." >&2
  echo "Check https://fabricmc.net/develop/ and set LOADER_VERSION= manually." >&2
  exit 1
fi

echo "==> Fabric loader $LOADER_VERSION (installer $INSTALLER_VERSION)"
curl -fSL --retry 3 \
  "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER_VERSION/$INSTALLER_VERSION/server/jar" \
  -o server.jar.tmp
mv server.jar.tmp server.jar

# ------------------------------------------------------------------------ mods
# Mods that ship with this repo already live in mods/. These are fetched from
# Modrinth so they always match MC_VERSION.
#   fabric-api        (P7dR8mSH) — hard dependency of Veinminer
fetch_mod() {
  local slug="$1" label="$2" json url filename
  echo "==> $label"
  json=$(curl -fsSL "https://api.modrinth.com/v2/project/$slug/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22$MC_VERSION%22%5D")

  if [ "$(jq 'length' <<<"$json")" -eq 0 ]; then
    echo "    No $label build for Minecraft $MC_VERSION. Skipping." >&2
    echo "    Download it manually into mods/ before starting." >&2
    return 0
  fi

  url=$(jq -r '.[0].files[] | select(.primary == true) | .url' <<<"$json")
  filename=$(jq -r '.[0].files[] | select(.primary == true) | .filename' <<<"$json")

  curl -fSL --retry 3 "$url" -o "mods/$filename.tmp"
  mv "mods/$filename.tmp" "mods/$filename"

  # Drop the copy installed by a previous run. Filenames do not follow the slug,
  # so the name is recorded rather than guessed, otherwise mods/ ends up holding
  # two versions of one mod.
  local stamp="mods/.installed-$slug"
  if [ -f "$stamp" ]; then
    local previous
    previous=$(cat "$stamp")
    [ "$previous" != "$filename" ] && rm -f "mods/$previous"
  fi
  printf '%s\n' "$filename" > "$stamp"
  echo "    mods/$filename"
}

mkdir -p mods
fetch_mod fabric-api "Fabric API"

# ------------------------------------------------------------------------ eula
[ -f eula.txt ] || echo "eula=true" > eula.txt

echo
echo "Setup complete. Mods installed:"
ls -1 mods/*.jar 2>/dev/null | sed 's|^|  |'
echo
echo "Start the server with:  ./start.sh"
