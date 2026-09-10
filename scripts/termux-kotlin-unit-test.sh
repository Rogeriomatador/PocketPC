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
PLATFORM_PACKAGE="$(read_lock android.platformPackage)"
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

PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
PLATFORM_DIR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
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
echo "  platform_package=$PLATFORM_PACKAGE"
echo "  platform_dir=$PLATFORM_DIR"
echo "  android_jar=$ANDROID_JAR"
echo "  build_tools=$BUILD_TOOLS_DIR"
echo "  aapt2=$AAPT2"
echo
LOG_DIR="$ROOT/build/termux"
mkdir -p "$LOG_DIR"
COMPILE_LOG="$LOG_DIR/compileDebugKotlin.log"
TEST_LOG="$LOG_DIR/testDebugUnitTest.log"

echo "Executing Kotlin compile gate:"
echo "  -Ppocketpc.skipNativeBuild=true"
echo "  :app:compileDebugKotlin"
echo

set +e
gradle \
    --no-daemon \
    --console=plain \
    -Pandroid.aapt2FromMavenOverride="$AAPT2" \
    -Ppocketpc.skipNativeBuild=true \
    :app:compileDebugKotlin 2>&1 | tee "$COMPILE_LOG"
COMPILE_STATUS=${PIPESTATUS[0]}
set -e

echo
if [ "$COMPILE_STATUS" -ne 0 ]; then
    echo "===== KOTLIN COMPILER ERRORS ====="
    grep -E '(^e: |Compilation error|error: )' "$COMPILE_LOG" | tail -n 120 || true
    echo "===== END KOTLIN COMPILER ERRORS ====="
    echo
    echo "Classification : TERMUX_KOTLIN_COMPILE_FAIL"
    echo "gradle_exit_code=$COMPILE_STATUS"
    echo "compile_log=$COMPILE_LOG"
    exit "$COMPILE_STATUS"
fi

echo "Classification : TERMUX_KOTLIN_COMPILE_PASS"
echo
echo "Executing unit-test gate:"
echo "  :app:testDebugUnitTest"
echo

set +e
gradle \
    --no-daemon \
    --console=plain \
    -Pandroid.aapt2FromMavenOverride="$AAPT2" \
    -Ppocketpc.skipNativeBuild=true \
    :app:testDebugUnitTest 2>&1 | tee "$TEST_LOG"
TEST_STATUS=${PIPESTATUS[0]}
set -e

echo
if [ "$TEST_STATUS" -ne 0 ]; then
    echo "===== UNIT TEST FAILURE SUMMARY ====="
    grep -E '(^e: |FAILED|FAILURE:|error: |There were failing tests)' "$TEST_LOG" | tail -n 120 || true
    echo "===== END UNIT TEST FAILURE SUMMARY ====="
    echo
    echo "Classification : TERMUX_KOTLIN_UNIT_TEST_FAIL"
    echo "gradle_exit_code=$TEST_STATUS"
    echo "test_log=$TEST_LOG"
    exit "$TEST_STATUS"
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
