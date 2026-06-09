#!/bin/bash

# خادم HTTP بسيط لـ Render
python3 -m http.server ${PORT:-10000} &

if [ ! -f server.jar ]; then
  curl -L "https://fill-data.papermc.io/v1/objects/d30fae0c74092b10855f0412ca6b265c60301a013d34bc28a2a41bf5682dd80b/paper-26.1.2-69.jar" \
  -o server.jar
fi

echo "eula=true" > eula.txt

java -Xms128M -Xmx384M -jar server.jar nogui
