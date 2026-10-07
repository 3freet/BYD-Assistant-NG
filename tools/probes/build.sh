#!/usr/bin/env bash
# Builds the three read-only probes into one dex file and pushes it to the connected head unit.
#   ANDROID_HOME must point at an Android SDK (platform + build-tools), JAVA_HOME at a JDK 11+.
#   PUSH=0 only builds (the dex is left in $OUT).
set -euo pipefail
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"

PLATFORM_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar | sort -V | tail -1)
D8=$(ls -d "$ANDROID_HOME"/build-tools/*/d8 | sort -V | tail -1)
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$(mktemp -d)}

mkdir -p "$OUT/classes"
javac --release 8 -cp "$PLATFORM_JAR" -d "$OUT/classes" "$HERE"/*.java
"$D8" --release --min-api 29 --lib "$PLATFORM_JAR" --output "$OUT" "$OUT"/classes/*.class
echo "built $OUT/classes.dex"

if [ "${PUSH:-1}" = "1" ]; then
  adb push "$OUT/classes.dex" /data/local/tmp/probes.dex
  echo "pushed. Try:  adb shell \"CLASSPATH=/data/local/tmp/probes.dex app_process /system/bin Feat 'FRIDGE' read\""
  echo "remove with:  adb shell rm /data/local/tmp/probes.dex"
fi
