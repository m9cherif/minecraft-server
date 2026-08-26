FROM eclipse-temurin:25-jdk

WORKDIR /server

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl python3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY server.properties eula.txt ./
COPY start.sh ./
RUN chmod +x start.sh

# Render probes this port with the simple HTTP server started by start.sh
EXPOSE 10000
# Minecraft
EXPOSE 25565

CMD ["./start.sh"]
