#!/bin/bash

PAPER_VERSION=1.21.11

echo "Downloading latest Paper for $PAPER_VERSION..."

curl -L \
"https://fill.papermc.io/v1/objects/paper/${PAPER_VERSION}/latest/download" \
-o server.jar

echo "Downloaded file size:"
ls -lh server.jar

echo "eula=true" > eula.txt

cat > server.properties << EOF
motd=Render Minecraft Server
max-players=3
online-mode=true
difficulty=normal
spawn-protection=0
view-distance=6
simulation-distance=4
EOF

java -Xms512M -Xmx1024M -jar server.jar nogui
