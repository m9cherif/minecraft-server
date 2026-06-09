#!/bin/bash

curl -L https://api.papermc.io/v2/projects/paper -o test.txt

echo "===== CONTENT ====="
cat test.txt
echo "==================="

sleep 300
