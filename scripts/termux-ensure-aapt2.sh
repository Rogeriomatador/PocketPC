#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"

cd "$ROOT"

echo "PocketPC Termux AAPT2 compatibility gate"
echo "Classification : AAPT2_COMPATIBILITY_ATTEMPT"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

for tool in python aapt2; do
    need "$tool"
done

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
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ANDROID_JAR_MISSING=$ANDROID_JAR" >&2
    exit 3
fi

package_version() {
    if command -v dpkg-query >/dev/null 2>&1; then
        dpkg-query -W -f='${Version}' aapt2 2>/dev/null || true
    fi
}

probe_aapt2() {
    local binary="$1"
    local probe_root="$ROOT/build/termux/aapt2-platform-probe"
    rm -rf "$probe_root"
    mkdir -p "$probe_root"

    cat > "$probe_root/AndroidManifest.xml" <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pocketpc.aapt2probe">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="$COMPILE_SDK" />
    <application />
</manifest>
EOF

    set +e
    "$binary" link \
        -o "$probe_root/probe.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$probe_root/AndroidManifest.xml" \
        >"$probe_root/link.log" 2>&1
    local status=$?
    set -e

    if [ "$status" -eq 0 ]; then
        return 0
    fi

    echo "===== AAPT2 PLATFORM PROBE ====="
    cat "$probe_root/link.log"
    echo "===== END AAPT2 PLATFORM PROBE ====="
    return "$status"
}

AAPT2="$(command -v aapt2)"
BEFORE_VERSION="$(package_version)"
BEFORE_TOOL_VERSION="$("$AAPT2" version 2>&1 | head -1 || true)"

echo "Before"
echo "  aapt2=$AAPT2"
echo "  package_version=${BEFORE_VERSION:-unknown}"
echo "  tool_version=${BEFORE_TOOL_VERSION:-unknown}"
echo "  platform_package=$PLATFORM_PACKAGE"
echo "  android_jar=$ANDROID_JAR"
echo

if probe_aapt2 "$AAPT2"; then
    echo "Classification : TERMUX_AAPT2_PLATFORM_PASS"
    exit 0
fi

echo
echo "Current AAPT2 cannot link the locked Android platform."
echo "Trying the current package from the official Termux repository."
echo

if ! command -v pkg >/dev/null 2>&1; then
    echo "TERMUX_PACKAGE_MANAGER_MISSING=pkg" >&2
    echo "Classification : TERMUX_AAPT2_PLATFORM_INCOMPATIBLE"
    exit 11
fi

pkg update -y
pkg install -y aapt aapt2

hash -r
AAPT2="$(command -v aapt2)"
AFTER_VERSION="$(package_version)"
AFTER_TOOL_VERSION="$("$AAPT2" version 2>&1 | head -1 || true)"

echo
echo "After official Termux package refresh"
echo "  aapt2=$AAPT2"
echo "  package_version=${AFTER_VERSION:-unknown}"
echo "  tool_version=${AFTER_TOOL_VERSION:-unknown}"
echo

if probe_aapt2 "$AAPT2"; then
    echo
    echo "Classification : TERMUX_AAPT2_PLATFORM_PASS_AFTER_OFFICIAL_UPDATE"
    exit 0
fi

echo
echo "Classification : TERMUX_AAPT2_PLATFORM_INCOMPATIBLE_AFTER_OFFICIAL_UPDATE"
echo "aapt2=$AAPT2"
echo "package_version=${AFTER_VERSION:-unknown}"
echo "tool_version=${AFTER_TOOL_VERSION:-unknown}"
echo "platform_package=$PLATFORM_PACKAGE"
echo "android_jar=$ANDROID_JAR"
echo "Important: Kotlin compilation is independent of this failure."
echo "Important: Do not lower compileSdk or substitute the Android 36 framework jar to hide this incompatibility."
exit 11
