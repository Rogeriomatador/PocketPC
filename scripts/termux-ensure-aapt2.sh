#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
LOCAL_AAPT2="$HOME/.local/pocketpc/android-build-tools/16.0.0.4/bin/aapt2"

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

need python

TERMUX_VARIANT="$(bash scripts/termux-detect-variant.sh --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"
echo

refresh_official_aapt2() {
    if ! bash scripts/termux-repository-check.sh --require-compatible; then
        echo "TERMUX_AAPT2_REPOSITORY_BLOCKED" >&2
        return 1
    fi

    if ! command -v pkg >/dev/null 2>&1; then
        echo "TERMUX_PACKAGE_MANAGER_MISSING=pkg" >&2
        return 1
    fi

    echo "Refreshing official Termux package metadata..."
    local update_log
    update_log="$(mktemp)"
    set +e
    pkg update -y 2>&1 | tee "$update_log"
    local update_status=${PIPESTATUS[0]}
    set -e
    if [ "$update_status" -ne 0 ]; then
        if grep -Eq 'NO_PUBKEY|repository .* is not signed|signature verification failed' "$update_log"; then
            echo "TERMUX_KEYRING_OUTDATED_OR_INVALID" >&2
            echo "recovery_command=bash scripts/termux-repair-keyring.sh --apply" >&2
            echo "Classification : TERMUX_AAPT2_BLOCKED_KEYRING" >&2
        else
            echo "TERMUX_PACKAGE_METADATA_REFRESH_FAILED" >&2
        fi
        rm -f "$update_log"
        return 1
    fi
    rm -f "$update_log"

    echo "Installing/updating official Termux aapt package (provides aapt2)..."
    if ! pkg install -y aapt; then
        echo "TERMUX_AAPT2_OFFICIAL_INSTALL_FAILED" >&2
        return 1
    fi

    hash -r
    return 0
}

if ! command -v aapt2 >/dev/null 2>&1 && [ ! -x "$LOCAL_AAPT2" ]; then
    echo "aapt2=missing"
    if ! refresh_official_aapt2; then
        echo "Classification : TERMUX_AAPT2_OFFICIAL_UPDATE_FAILED"
        exit 12
    fi
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
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ANDROID_JAR_MISSING=$ANDROID_JAR" >&2
    exit 3
fi

package_version() {
    if command -v dpkg-query >/dev/null 2>&1; then
        dpkg-query -W -f='${Version}' aapt 2>/dev/null || true
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

SYSTEM_AAPT2="$(command -v aapt2 2>/dev/null || true)"
BEFORE_VERSION="$(package_version)"

if [ -x "$LOCAL_AAPT2" ]; then
    LOCAL_TOOL_VERSION="$("$LOCAL_AAPT2" version 2>&1 | head -1 || true)"
    echo "Local candidate"
    echo "  aapt2=$LOCAL_AAPT2"
    echo "  tool_version=${LOCAL_TOOL_VERSION:-unknown}"
    echo "  platform_package=$PLATFORM_PACKAGE"
    echo "  android_jar=$ANDROID_JAR"
    echo
    if probe_aapt2 "$LOCAL_AAPT2"; then
        echo "aapt2_selected=$LOCAL_AAPT2"
        echo "Classification : TERMUX_LOCAL_AAPT2_PLATFORM_PASS"
        exit 0
    fi
    echo
    echo "Local PocketPC AAPT2 candidate is present but incompatible; falling back to system diagnosis."
    echo
fi

if [ -z "$SYSTEM_AAPT2" ]; then
    echo "Classification : TERMUX_AAPT2_MISSING_AFTER_RECOVERY"
    exit 12
fi

AAPT2="$SYSTEM_AAPT2"
BEFORE_TOOL_VERSION="$("$AAPT2" version 2>&1 | head -1 || true)"

echo "System candidate"
echo "  aapt2=$AAPT2"
echo "  package_version=${BEFORE_VERSION:-unknown}"
echo "  tool_version=${BEFORE_TOOL_VERSION:-unknown}"
echo "  platform_package=$PLATFORM_PACKAGE"
echo "  android_jar=$ANDROID_JAR"
echo

if probe_aapt2 "$AAPT2"; then
    echo "aapt2_selected=$AAPT2"
    echo "Classification : TERMUX_AAPT2_PLATFORM_PASS"
    exit 0
fi

echo
echo "Current AAPT2 cannot link the locked Android platform."
echo

if [ "$TERMUX_VARIANT" = "googleplay" ]; then
    CANDIDATE_VERSION=""
    if command -v apt-cache >/dev/null 2>&1; then
        CANDIDATE_VERSION="$(
            apt-cache policy aapt 2>/dev/null |
                awk '/Candidate:/ {print $2; exit}'
        )"
    fi
    echo "Google Play system AAPT2 is incompatible with the locked platform."
    echo "aapt_candidate_version=${CANDIDATE_VERSION:-unknown}"
    echo "Trying the pinned local AAPT2 source build..."
    echo

    set +e
    bash scripts/termux-build-modern-aapt2.sh
    LOCAL_BUILD_STATUS=$?
    set -e

    if [ "$LOCAL_BUILD_STATUS" -ne 0 ]; then
        echo
        echo "Classification : TERMUX_GOOGLE_PLAY_LOCAL_AAPT2_BUILD_FAIL"
        echo "local_builder_exit_code=$LOCAL_BUILD_STATUS"
        echo "platform_package=$PLATFORM_PACKAGE"
        echo "android_jar=$ANDROID_JAR"
        echo "Important: system AAPT2 remains incompatible and the pinned local build did not pass."
        exit 11
    fi

    if [ ! -x "$LOCAL_AAPT2" ]; then
        echo "LOCAL_AAPT2_MISSING_AFTER_SUCCESSFUL_BUILD=$LOCAL_AAPT2" >&2
        echo "Classification : TERMUX_GOOGLE_PLAY_LOCAL_AAPT2_BUILD_OUTPUT_MISSING"
        exit 11
    fi

    echo
    echo "Revalidating the freshly built local AAPT2..."
    if probe_aapt2 "$LOCAL_AAPT2"; then
        echo "aapt2_selected=$LOCAL_AAPT2"
        echo "Classification : TERMUX_LOCAL_AAPT2_PLATFORM_PASS_AFTER_BUILD"
        exit 0
    fi

    echo "Classification : TERMUX_GOOGLE_PLAY_LOCAL_AAPT2_REPROBE_FAIL"
    exit 11
fi

echo "Trying the current package from the classic Termux repository."
echo

if ! refresh_official_aapt2; then
    echo "Classification : TERMUX_AAPT2_OFFICIAL_UPDATE_FAILED"
    exit 12
fi

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
