#!/bin/bash

echo "Downloading Paper 26.1.2..."

curl -L \
"https://fill-data.papermc.io/v1/objects/d30fae0c74092b10855f0412ca6b265c60301a013d34bc28a2a41bf5682dd80b/paper-26.1.2-69.jar" \
-o server.jar

echo "Checking file..."
ls -lh server.jar

echo "eula=true" > eula.txt

cat > server.properties <<EOF
motd=Paper 26.1.2 Server
max-players=3
online-mode=true
difficulty=normal
view-distance=6
simulation-distance=4
EOF

java -Xms512M -Xmx1024M -jar server.jar nogui
