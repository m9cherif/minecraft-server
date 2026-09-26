#!/usr/bin/env bash
# First-run provisioning: Java (Temurin), Paper jar, EULA, tuned properties.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

mkdir -p "$SERVER_DIR"
install_java   || exit 1
fetch_paper    || exit 1
write_eula_and_props

log "setup complete (Paper $PAPER_VERSION)"
