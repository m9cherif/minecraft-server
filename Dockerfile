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
# User-Agent: Modrinth's API asks every client to identify itself.
ENV CURL_RETRY="--retry 6 --retry-delay 3 --retry-all-errors --connect-timeout 10 -H User-Agent:minecraft-server-render/1.0(https://github.com/m9cherif/minecraft-server)"

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
# Render's router only ever proxies HTTP(S)/WebSocket, never raw TCP. See
# render-entrypoint.sh for how the TCP side gets through.
RUN mkdir -p mods
COPY mods/veinminer-*.jar mods/
# TEMPORARY: five jq-based attempts to parse Modrinth's version list all hit
# the same "control characters" error, and the last one proved the payload
# has literally zero raw control bytes in it (tr -d '[:cntrl:]' removed
# nothing) — so the theory that Modrinth's data was malformed is disproven
# too. Something about how jq is being invoked here is the real problem,
# not the data. Decision: stop trying to fix jq's path and grep the file URL
# out as plain text instead, then hardcode it below. This step just prints
# candidates so the real one can be read from the build log and pinned.
RUN get() { curl -fSL $CURL_RETRY "$@"; } \
    && get "https://api.modrinth.com/v2/project/fabric-api/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%22${MC_VERSION}%22%5D" \
      -o /tmp/fapi_raw \
    && echo "--- candidate jar URLs (newest first) ---" \
    && grep -oE 'https://cdn\.modrinth\.com/data/P7dR8mSH/versions/[^"]+\.jar' /tmp/fapi_raw | head -5

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
