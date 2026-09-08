#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

cd "$ROOT"

echo "PocketPC Termux Kotlin/unit-test smoke"
echo "Classification : SOFTWARE_TEST_ATTEMPT"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

for tool in git python java gradle aapt2; do
    need "$tool"
done

if [ ! -f "$LOCK" ]; then
    echo "LOCK_MISSING=$LOCK" >&2
    exit 2
fi

read_lock() {
    python - "$LOCK" "$1" <<'PY'
import json, sys
path, key = sys.argv[1], sys.argv[2]
data = json.load(open(path, "r", encoding="utf-8"))
cur = data
for part in key.split("."):
    cur = cur[part]
print(cur)
PY
}

COMPILE_SDK="$(read_lock android.compileSdk)"
BUILD_TOOLS="$(read_lock android.buildTools)"
GRADLE_REQUIRED="$(read_lock gradle.version)"
VERSION_NAME="$(read_lock app.versionName)"
VERSION_CODE="$(read_lock app.versionCode)"

SOURCE_REVISION="$(git rev-parse HEAD)"
if git diff --quiet --ignore-submodules -- &&
   git diff --cached --quiet --ignore-submodules --; then
    SOURCE_TREE_STATE="CLEAN"
else
    SOURCE_TREE_STATE="DIRTY"
fi

ANDROID_JAR="$SDK_ROOT/platforms/android-$COMPILE_SDK/android.jar"
BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ANDROID_JAR_MISSING=$ANDROID_JAR" >&2
    exit 3
fi

if [ ! -f "$BUILD_TOOLS_DIR/lib/d8.jar" ]; then
    echo "BUILD_TOOLS_JAVA_PAYLOAD_MISSING=$BUILD_TOOLS_DIR" >&2
    exit 3
fi

GRADLE_ACTUAL="$(
    gradle --version 2>/dev/null |
        awk '/^Gradle / {print $2; exit}'
)"
if [ "$GRADLE_ACTUAL" != "$GRADLE_REQUIRED" ]; then
    echo "GRADLE_LOCK_MISMATCH required=$GRADLE_REQUIRED actual=${GRADLE_ACTUAL:-unknown}" >&2
    exit 4
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export POCKETPC_SOURCE_REVISION="$SOURCE_REVISION"

cat > "$ROOT/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

AAPT2="$(command -v aapt2)"

echo "Evidence"
echo "  source_revision=$SOURCE_REVISION"
echo "  source_tree=$SOURCE_TREE_STATE"
echo "  version=$VERSION_NAME"
echo "  version_code=$VERSION_CODE"
echo "  java=$(java -version 2>&1 | head -1)"
echo "  gradle=$GRADLE_ACTUAL"
echo "  android_jar=$ANDROID_JAR"
echo "  build_tools=$BUILD_TOOLS_DIR"
echo "  aapt2=$AAPT2"
echo
echo "Executing:"
echo "  -Ppocketpc.skipNativeBuild=true"
echo "  :app:testDebugUnitTest"
echo

set +e
gradle     --no-daemon     --stacktrace     -Pandroid.aapt2FromMavenOverride="$AAPT2"     -Ppocketpc.skipNativeBuild=true     :app:testDebugUnitTest
STATUS=$?
set -e

echo
if [ "$STATUS" -ne 0 ]; then
    echo "Classification : TERMUX_KOTLIN_UNIT_TEST_FAIL"
    echo "gradle_exit_code=$STATUS"
    echo "Important: FAIL is evidence that the attempted software test did not pass."
    echo "It does not prove the cause is Kotlin; inspect the Gradle error above."
    exit "$STATUS"
fi

echo "Classification : TERMUX_KOTLIN_COMPILE_UNIT_TEST_PASS"
echo "tested_revision=$SOURCE_REVISION"
echo "tested_version=$VERSION_NAME"
echo "tested_version_code=$VERSION_CODE"
echo "tested_tree=$SOURCE_TREE_STATE"
echo
echo "Important:"
echo "  This is a real Gradle/Kotlin unit-test software test on the phone."
echo "  Native CMake configuration is explicitly disabled for this Termux-only unit-test gate."
echo "  It does not run Android Lint, assemble an APK, execute the native CMake host,"
echo "  sign/install PocketPC, or validate physical behavior."
