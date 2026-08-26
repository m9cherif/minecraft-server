FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server

# ---------------------------------------------------------------- Fabric
ARG MC_VERSION=26.2
RUN LOADER_VERSION=$(curl -fsSL "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}" \
      | jq -r '[.[] | select(.loader.stable == true)][0].loader.version') \
    && INSTALLER_VERSION=$(curl -fsSL "https://meta.fabricmc.net/v2/versions/installer" \
      | jq -r '[.[] | select(.stable == true)][0].version') \
    && curl -fSL --retry 3 \
      "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar" \
      -o server.jar

# ------------------------------------------------------------------ mods
# TCP-only mods. Simple Voice Chat is deliberately not here: it needs a
# second UDP port, and Render's router only ever proxies HTTP(S)/WebSocket,
# never raw UDP. See render-entrypoint.sh for how the TCP side gets through.
RUN mkdir -p mods
COPY mods/veinminer-*.jar mods/
RUN FAPI_JSON=$(curl -fsSL "https://api.modrinth.com/v2/project/fabric-api/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${MC_VERSION}%22%5D") \
    && FAPI_URL=$(echo "$FAPI_JSON" | jq -r '.[0].files[] | select(.primary == true) | .url') \
    && FAPI_NAME=$(echo "$FAPI_JSON" | jq -r '.[0].files[] | select(.primary == true) | .filename') \
    && curl -fSL --retry 3 "$FAPI_URL" -o "mods/$FAPI_NAME"

COPY server.properties eula.txt ./

# -------------------------------------------------------------- wstunnel
# Render exposes exactly one public port, and only speaks HTTP(S) on it —
# never plain TCP, never UDP. wstunnel carries Minecraft's raw TCP protocol
# inside a WebSocket connection (an HTTP upgrade), which Render *does* proxy.
# Players run a small wstunnel client that undoes this locally; see
# deploy/RENDER.md. This is the actual workaround for a platform limit, not
# a security bypass — wstunnel is a standard, widely used open-source tool
# for exactly this situation.
RUN WSTUNNEL_URL=$(curl -fsSL https://api.github.com/repos/erebe/wstunnel/releases/latest \
      | jq -r '.assets[] | select(.name | test("linux_amd64.*tar\\.gz$")) | .browser_download_url') \
    && curl -fSL --retry 3 "$WSTUNNEL_URL" -o /tmp/wstunnel.tar.gz \
    && tar -xzf /tmp/wstunnel.tar.gz -C /usr/local/bin wstunnel \
    && chmod +x /usr/local/bin/wstunnel \
    && rm /tmp/wstunnel.tar.gz

COPY render-entrypoint.sh ./
RUN chmod +x render-entrypoint.sh

CMD ["./render-entrypoint.sh"]
