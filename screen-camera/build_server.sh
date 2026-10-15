#!/usr/bin/env bash
# Builds server/build/screen-camera-server.jar (a dex jar runnable with app_process).
# No Gradle needed. Requirements:
#   - JDK 17+ (javac)
#   - Android SDK with platforms;android-35 (or newer) and build-tools (for d8)
#
# Env overrides:
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK location
#   ANDROID_JAR                       explicit path to android.jar (API >= 35)
#   D8                                explicit d8 command (default: $BUILD_TOOLS/d8)
#   PLATFORM=android-35               platform dir name
set -euo pipefail

cd "$(dirname "$0")"
ROOT="$(pwd)"
OUT="$ROOT/server/build"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
PLATFORM="${PLATFORM:-android-35}"

if [[ -z "${ANDROID_JAR:-}" ]]; then
    [[ -n "$SDK" ]] || { echo "Set ANDROID_HOME (or ANDROID_JAR + D8)" >&2; exit 1; }
    ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
fi
[[ -f "$ANDROID_JAR" ]] || { echo "android.jar not found: $ANDROID_JAR" >&2; exit 1; }

if [[ -z "${D8:-}" ]]; then
    [[ -n "$SDK" ]] || { echo "Set ANDROID_HOME or D8" >&2; exit 1; }
    BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n1)"
    [[ -n "$BT" ]] || { echo "No build-tools in $SDK" >&2; exit 1; }
    D8="$BT/d8"
fi

rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex"

echo "[1/3] compiling hidden-API stubs (compile-only, not packaged)"
javac --release 17 -nowarn -encoding UTF-8 \
    -cp "$ANDROID_JAR" -d "$OUT/stubs" \
    $(find server/hidden-api/src/main/java -name '*.java')

echo "[2/3] compiling server"
# Stubs come BEFORE android.jar so that the stub VirtualDeviceManager (which declares the
# @SystemApi members) shadows the public-only one in android.jar.
javac --release 17 -encoding UTF-8 -Xlint:-options \
    -cp "$OUT/stubs:$ANDROID_JAR" -d "$OUT/classes" \
    $(find server/src/main/java -name '*.java')

echo "[3/3] dexing"
CLASSES=$(cd "$OUT/classes" && find . -name '*.class')
(cd "$OUT/classes" && $D8 --release --min-api 35 --lib "$ANDROID_JAR" \
    --classpath "$OUT/stubs" --output "$OUT/dex" $CLASSES)
JAR_OUT="$OUT/screen-camera-server.jar"
if command -v jar >/dev/null 2>&1; then
    (cd "$OUT/dex" && jar cfM "$JAR_OUT" classes.dex)
elif command -v zip >/dev/null 2>&1; then
    (cd "$OUT/dex" && zip -q "$JAR_OUT" classes.dex)
else
    python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED).write(sys.argv[2],'classes.dex')" \
        "$JAR_OUT" "$OUT/dex/classes.dex"
fi

echo "OK: $OUT/screen-camera-server.jar"
