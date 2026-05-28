#!/usr/bin/env bash
# Compile HelperDaemon.kt → JAR → dex bundle at app/src/main/assets/helper.dex.
# Prereqs: kotlinc on PATH (or KOTLINC env var), $ANDROID_HOME pointing to
# the SDK with build-tools/34.0.0/d8 present.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/app/src/main/kotlin/com/bydmate/app/helper/HelperDaemon.kt"
OUT="$ROOT/app/src/main/assets/helper.dex"
TMP="$(mktemp -d)"
KOTLINC="${KOTLINC:-kotlinc}"
D8="${D8:-$ANDROID_HOME/build-tools/34.0.0/d8}"

mkdir -p "$ROOT/app/src/main/assets"
"$KOTLINC" -no-stdlib -no-reflect -d "$TMP/helper.jar" "$SRC" \
    -classpath "$ANDROID_HOME/platforms/android-34/android.jar"
"$D8" --output "$TMP" "$TMP/helper.jar"
mv "$TMP/classes.dex" "$OUT"
shasum -a 256 "$OUT" | awk '{print $1}' > "$OUT.sha256"
echo "built $OUT ($(wc -c < "$OUT") bytes)"
echo "sha256: $(cat "$OUT.sha256")"
rm -rf "$TMP"
