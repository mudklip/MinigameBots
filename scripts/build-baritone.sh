#!/bin/bash
# Builds the optional Baritone jar (LGPL-3.0, unchanged upstream source) for the release zip.
# Usage: scripts/build-baritone.sh [output-dir]   (needs Java 25 and network access to its Maven repos)
set -euo pipefail
COMMIT=728a755
OUT=${1:-build/baritone}
WORK=$(mktemp -d)
git clone -q --depth 50 -b 26.3 https://github.com/cabaletta/baritone "$WORK/baritone"
cd "$WORK/baritone"
git checkout -q "$COMMIT"
sed -i 's/^available_loaders=.*/available_loaders=fabric/' gradle.properties
# A separate Gradle home keeps machine-wide init scripts out of its build.
GRADLE_USER_HOME="$WORK/gradle-home" ./gradlew --no-daemon -q :fabric:build -x test
cd - >/dev/null
mkdir -p "$OUT"
cp "$WORK/baritone/dist/baritone-api-fabric-1.20.0.jar" "$OUT/"
cp "$WORK/baritone/LICENSE" "$OUT/BARITONE-LICENSE.txt"
cat > "$OUT/BARITONE-SOURCE.txt" <<TXT
Baritone 1.20.0 for Fabric / Minecraft 26.3, built unchanged from
https://github.com/cabaletta/baritone (branch 26.3, commit $COMMIT).
Licensed under the GNU LGPL 3.0 (see BARITONE-LICENSE.txt). Optional: MinigameBots uses it only
for teacher demos and works without it.
TXT
echo "Built $OUT/baritone-api-fabric-1.20.0.jar"
