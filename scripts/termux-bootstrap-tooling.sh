#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"

echo "PocketPC Termux tooling bootstrap"
echo "Classification : TOOLING_INSTALL_ONLY_NOT_A_BUILD"
echo

if [ ! -f "$LOCK" ]; then
    echo "LOCK_MISSING=$LOCK" >&2
    exit 2
fi

need_host() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_BOOTSTRAP_TOOL=$1" >&2
        exit 2
    }
}

need_host python
need_host pkg

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

GRADLE_VERSION="$(read_lock gradle.version)"
GRADLE_URL="$(read_lock gradle.distributionUrl)"
GRADLE_SHA256="$(read_lock gradle.distributionSha256)"
JDK_MAJOR="$(read_lock jdk.major)"

if [ "$JDK_MAJOR" != "17" ]; then
    echo "UNSUPPORTED_TERMUX_JDK_LOCK=$JDK_MAJOR" >&2
    exit 3
fi

echo "Installing Termux-native prerequisites..."
pkg install -y openjdk-17 cmake ninja curl unzip coreutils

if ! command -v aapt2 >/dev/null 2>&1; then
    echo "Installing Termux Android packaging tools..."
    if ! pkg install -y aapt2; then
        pkg install -y aapt
    fi
fi

if ! command -v aapt2 >/dev/null 2>&1; then
    echo "AAPT2_STILL_MISSING" >&2
    echo "The repository configured for this Termux build did not provide aapt2." >&2
    exit 4
fi

GRADLE_BASE="$HOME/.local/pocketpc/gradle"
GRADLE_HOME="$GRADLE_BASE/gradle-$GRADLE_VERSION"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"
ARCHIVE="$GRADLE_BASE/gradle-$GRADLE_VERSION-bin.zip"

mkdir -p "$GRADLE_BASE"

if [ ! -x "$GRADLE_BIN" ]; then
    echo
    echo "Downloading pinned Gradle $GRADLE_VERSION..."
    rm -f "$ARCHIVE"
    curl --fail --location --show-error "$GRADLE_URL" --output "$ARCHIVE"

    ACTUAL_SHA="$(sha256sum "$ARCHIVE" | awk '{print tolower($1)}')"
    EXPECTED_SHA="$(printf '%s' "$GRADLE_SHA256" | tr 'A-F' 'a-f')"

    echo "gradle_expected_sha256=$EXPECTED_SHA"
    echo "gradle_actual_sha256=$ACTUAL_SHA"

    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        rm -f "$ARCHIVE"
        echo "GRADLE_SHA256_MISMATCH" >&2
        exit 5
    fi

    rm -rf "$GRADLE_HOME"
    unzip -q "$ARCHIVE" -d "$GRADLE_BASE"
    rm -f "$ARCHIVE"
fi

if [ ! -x "$GRADLE_BIN" ]; then
    echo "PINNED_GRADLE_INSTALL_FAILED=$GRADLE_BIN" >&2
    exit 6
fi

PROFILE="$HOME/.profile"
BEGIN="# >>> PocketPC pinned Gradle >>>"
END="# <<< PocketPC pinned Gradle <<<"
TMP_PROFILE="$(mktemp)"

if [ -f "$PROFILE" ]; then
    awk -v begin="$BEGIN" -v end="$END" '
        $0 == begin {skip=1; next}
        $0 == end {skip=0; next}
        !skip {print}
    ' "$PROFILE" > "$TMP_PROFILE"
else
    : > "$TMP_PROFILE"
fi

{
    cat "$TMP_PROFILE"
    echo "$BEGIN"
    echo "export POCKETPC_GRADLE_HOME=\"$GRADLE_HOME\""
    echo 'export PATH="$POCKETPC_GRADLE_HOME/bin:$PATH"'
    echo "$END"
} > "$PROFILE"
rm -f "$TMP_PROFILE"

export POCKETPC_GRADLE_HOME="$GRADLE_HOME"
export PATH="$POCKETPC_GRADLE_HOME/bin:$PATH"

echo
echo "Installed tooling"
java -version 2>&1 | head -3
echo "aapt2=$(command -v aapt2)"
echo "cmake=$(command -v cmake)"
echo "ninja=$(command -v ninja)"
echo "clang=$(command -v clang)"
"$GRADLE_BIN" --version | awk '/^Gradle / {print "gradle=" $2; exit}'

echo
echo "Classification : TERMUX_BASE_TOOLING_INSTALLED"
echo "Next:"
echo "  source ~/.profile"
echo "  bash scripts/termux-on-device-preflight.sh"
echo
echo "Important:"
echo "  This bootstrap does not install Android SDK platform 37 or an NDK host toolchain."
echo "  It does not compile Kotlin, build an APK, sign, install, or update PocketPC."
