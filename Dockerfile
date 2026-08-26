FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates jq \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /server

# A plain "curl | jq" build step gives a confusing jq parse error when the
# curl call itself failed transiently (empty/partial body) — Render's build
# network has occasionally dropped a single lookup mid-build. --retry-all-errors
# covers that; get() also fails loudly with the real cause instead of leaving
# it to jq to complain about invalid JSON.
ENV CURL_RETRY="--retry 6 --retry-delay 3 --retry-all-errors --connect-timeout 10"

# ---------------------------------------------------------------- Fabric
ARG MC_VERSION=26.2
RUN get() { curl -fSL $CURL_RETRY "$@"; } \
    && LOADER_VERSION=$(get "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}" \
      | jq -r '[.[] | select(.loader.stable == true)][0].loader.version') \
    && INSTALLER_VERSION=$(get "https://meta.fabricmc.net/v2/versions/installer" \
      | jq -r '[.[] | select(.stable == true)][0].version') \
    && test -n "$LOADER_VERSION" && test "$LOADER_VERSION" != "null" \
    && test -n "$INSTALLER_VERSION" && test "$INSTALLER_VERSION" != "null" \
    && get "https://meta.fabricmc.net/v2/versions/loader/${MC_VERSION}/${LOADER_VERSION}/${INSTALLER_VERSION}/server/jar" \
      -o server.jar

# ------------------------------------------------------------------ mods
# TCP-only mods. Simple Voice Chat is deliberately not here: it needs a
# second UDP port, and Render's router only ever proxies HTTP(S)/WebSocket,
# never raw UDP. See render-entrypoint.sh for how the TCP side gets through.
RUN mkdir -p mods
COPY mods/veinminer-*.jar mods/
RUN get() { curl -fSL $CURL_RETRY "$@"; } \
    && FAPI_JSON=$(get "https://api.modrinth.com/v2/project/fabric-api/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${MC_VERSION}%22%5D") \
    && FAPI_URL=$(echo "$FAPI_JSON" | jq -r '.[0].files[] | select(.primary == true) | .url') \
    && FAPI_NAME=$(echo "$FAPI_JSON" | jq -r '.[0].files[] | select(.primary == true) | .filename') \
    && test -n "$FAPI_URL" && test "$FAPI_URL" != "null" \
    && get "$FAPI_URL" -o "mods/$FAPI_NAME"

COPY server.properties eula.txt ./

# -------------------------------------------------------------- wstunnel
# Render exposes exactly one public port, and only speaks HTTP(S) on it —
# never plain TCP, never UDP. wstunnel carries Minecraft's raw TCP protocol
# inside a WebSocket connection (an HTTP upgrade), which Render *does* proxy.
# Players run a small wstunnel client that undoes this locally; see
# deploy/RENDER.md. This is the actual workaround for a platform limit, not
# a security bypass — wstunnel is a standard, widely used open-source tool
# for exactly this situation.
RUN get() { curl -fSL $CURL_RETRY "$@"; } \
    && WSTUNNEL_URL=$(get https://api.github.com/repos/erebe/wstunnel/releases/latest \
      | jq -r '.assets[] | select(.name | test("linux_amd64.*tar\\.gz$")) | .browser_download_url') \
    && test -n "$WSTUNNEL_URL" && test "$WSTUNNEL_URL" != "null" \
    && get "$WSTUNNEL_URL" -o /tmp/wstunnel.tar.gz \
    && tar -xzf /tmp/wstunnel.tar.gz -C /usr/local/bin wstunnel \
    && chmod +x /usr/local/bin/wstunnel \
    && rm /tmp/wstunnel.tar.gz

COPY render-entrypoint.sh ./
RUN chmod +x render-entrypoint.sh

CMD ["./render-entrypoint.sh"]
