#!/usr/bin/env bash
# Build the plugin jar and drop it in ~/.runelite/sideloaded-plugins so it loads
# when RuneLite is started in --developer-mode (../tools/runelite_dev.sh).
set -euo pipefail
cd "$(dirname "$0")"
./gradlew -q jar -x test
OUT="$HOME/.runelite/sideloaded-plugins"
mkdir -p "$OUT"
cp build/libs/flip-copilot.jar "$OUT/flip-copilot.jar"
echo "Installed: $OUT/flip-copilot.jar"
echo "Start RuneLite in developer mode (../tools/runelite_dev.sh) or use ./gradlew run"
