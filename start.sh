#!/bin/bash

PAPER_VERSION=1.21.6
PAPER_BUILD=151

if [ ! -f server.jar ]; then
  echo "Downloading Paper..."
  curl -L -o server.jar https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds/${PAPER_BUILD}/downloads/paper-${PAPER_VERSION}-${PAPER_BUILD}.jar
fi

if [ ! -f eula.txt ]; then
  echo "eula=true" > eula.txt
fi

if [ ! -f server.properties ]; then
cat <<EOF > server.properties
motd=Render Minecraft Server
max-players=3
online-mode=true
difficulty=normal
spawn-protection=0
view-distance=6
simulation-distance=4
EOF
fi

java -Xms512M -Xmx1024M -jar server.jar nogui
