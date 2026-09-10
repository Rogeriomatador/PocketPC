#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE_REPO="https://github.com/termux/android-build-tools.git"
SOURCE_TAG="16.0.0.4"
SOURCE_SHA="c4edf8539a34a8600538e6642c1ecb170452a79e"
WORK_ROOT="${HOME}/.cache/pocketpc/android-build-tools-${SOURCE_TAG}"
SOURCE_DIR="$WORK_ROOT/source"
BUILD_DIR="$WORK_ROOT/build"
INSTALL_ROOT="${HOME}/.local/pocketpc/android-build-tools/${SOURCE_TAG}"
INSTALL_BIN="$INSTALL_ROOT/bin/aapt2"
EVIDENCE_DIR="$ROOT/build/termux"
EVIDENCE_FILE="$EVIDENCE_DIR/aapt2-local-build.txt"
LOG_FILE="$EVIDENCE_DIR/aapt2-local-build.log"
JOBS="${POCKETPC_AAPT2_BUILD_JOBS:-2}"

cd "$ROOT"

echo "PocketPC local modern AAPT2 build"
echo "Classification : TERMUX_LOCAL_AAPT2_BUILD_ATTEMPT"
echo "source_repo=$SOURCE_REPO"
echo "source_tag=$SOURCE_TAG"
echo "source_sha=$SOURCE_SHA"
echo "install_bin=$INSTALL_BIN"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

for tool in git python cmake ninja clang pkg pkg-config sha256sum; do
    need "$tool"
done

bash scripts/termux-repository-check.sh --require-compatible

TERMUX_VARIANT="$(bash scripts/termux-detect-variant.sh --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"

case "$JOBS" in
    ''|*[!0-9]*)
        echo "INVALID_BUILD_JOBS=$JOBS" >&2
        exit 3
        ;;
esac
if [ "$JOBS" -lt 1 ] || [ "$JOBS" -gt 4 ]; then
    echo "INVALID_BUILD_JOBS=$JOBS expected=1..4" >&2
    exit 3
fi

echo
echo "Resolving build dependencies from the active compatible Termux repository..."
pkg update

REQUIRED_PACKAGES=(
    bison
    flex
    cmake
    ninja
    git
    clang
    pkg-config
    fmt
    libc++
    libexpat
    libpng
    libprotobuf
    protobuf
    libzopfli
    zlib
    googletest
    patch
)

candidate_version() {
    local package="$1"
    apt-cache policy "$package" 2>/dev/null |
        awk '/Candidate:/ {candidate=$2} END {if (candidate != "" && candidate != "(none)") print candidate}'
}

HEADER_PACKAGE=""
for candidate in linux-headers ndk-sysroot; do
    if [ -n "$(candidate_version "$candidate")" ]; then
        HEADER_PACKAGE="$candidate"
        break
    fi
done

if [ -z "$HEADER_PACKAGE" ]; then
    echo "TERMUX_AAPT2_HEADER_PACKAGE_UNAVAILABLE" >&2
    echo "checked=linux-headers,ndk-sysroot" >&2
    exit 9
fi

REQUIRED_PACKAGES+=("$HEADER_PACKAGE")
echo "header_package=$HEADER_PACKAGE"
echo "resolved_packages=${REQUIRED_PACKAGES[*]}"

for package in "${REQUIRED_PACKAGES[@]}"; do
    version="$(candidate_version "$package")"
    echo "candidate_package=$package version=${version:-none}"
done

UNAVAILABLE_PACKAGES=()
for package in "${REQUIRED_PACKAGES[@]}"; do
    if [ -z "$(candidate_version "$package")" ]; then
        UNAVAILABLE_PACKAGES+=("$package")
    fi
done

if [ "${#UNAVAILABLE_PACKAGES[@]}" -gt 0 ]; then
    echo "TERMUX_AAPT2_BUILD_DEPENDENCIES_UNAVAILABLE" >&2
    printf '  missing_package=%s\n' "${UNAVAILABLE_PACKAGES[@]}" >&2
    exit 10
fi

MISSING_PACKAGES=()
for package in "${REQUIRED_PACKAGES[@]}"; do
    if ! dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
        MISSING_PACKAGES+=("$package")
    fi
done

if [ "${#MISSING_PACKAGES[@]}" -gt 0 ]; then
    echo "Installing missing build dependencies:"
    printf '  %s\n' "${MISSING_PACKAGES[@]}"
    pkg install -y "${MISSING_PACKAGES[@]}"
else
    echo "build_dependencies=already_installed"
fi

mkdir -p "$WORK_ROOT" "$INSTALL_ROOT/bin" "$EVIDENCE_DIR"

if [ -x "$INSTALL_BIN" ]; then
    echo
    echo "Existing local AAPT2 candidate found."
    "$INSTALL_BIN" version || true
fi

if [ ! -d "$SOURCE_DIR/.git" ]; then
    rm -rf "$SOURCE_DIR"
    echo
    echo "Cloning pinned official source..."
    git clone \
        --depth 1 \
        --branch "$SOURCE_TAG" \
        --recurse-submodules \
        --shallow-submodules \
        "$SOURCE_REPO" \
        "$SOURCE_DIR"
fi

ACTUAL_SOURCE_SHA="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [ "$ACTUAL_SOURCE_SHA" != "$SOURCE_SHA" ]; then
    echo "AAPT2_SOURCE_SHA_MISMATCH expected=$SOURCE_SHA actual=$ACTUAL_SOURCE_SHA" >&2
    echo "Removing stale source checkout; run this script again." >&2
    rm -rf "$SOURCE_DIR"
    exit 4
fi

git -C "$SOURCE_DIR" submodule update --init --recursive --depth 1

rm -rf "$BUILD_DIR"

export CFLAGS="${CFLAGS:-} -fPIC"
export CXXFLAGS="${CXXFLAGS:-} -fPIC"
export CPPFLAGS="${CPPFLAGS:-} -DNDEBUG -D__ANDROID_SDK_VERSION__=__ANDROID_API__ -D_FILE_OFFSET_BITS=64 -DPROTOBUF_USE_DLLS"

echo
echo "Configuring AAPT2..."
set +e
PROTOC="$(command -v protoc 2>/dev/null || true)"
if [ -z "$PROTOC" ]; then
    echo "MISSING_TOOL=protoc" >&2
    exit 8
fi

PROTOBUF_HEADER="${PREFIX:-/data/data/com.termux/files/usr}/include/google/protobuf/message.h"
if [ ! -f "$PROTOBUF_HEADER" ]; then
    echo "PROTOBUF_HEADER_MISSING=$PROTOBUF_HEADER" >&2
    exit 11
fi

if ! pkg-config --exists protobuf; then
    echo "PROTOBUF_PKGCONFIG_MISSING=protobuf" >&2
    exit 12
fi

echo "protoc=$PROTOC"
echo "protobuf_header=$PROTOBUF_HEADER"
echo "protobuf_pkgconfig=$(pkg-config --modversion protobuf 2>/dev/null || printf 'unknown')"

cmake \
    -S "$SOURCE_DIR" \
    -B "$BUILD_DIR" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_PREFIX_PATH="${PREFIX:-/data/data/com.termux/files/usr}" \
    -DANDROID_BUILD_TOOLS_DEV_MODE=ON \
    -Dprotobuf_generate_PROTOC_EXE="$PROTOC" \
    2>&1 | tee "$LOG_FILE"
CONFIG_STATUS=${PIPESTATUS[0]}
set -e

if [ "$CONFIG_STATUS" -ne 0 ]; then
    echo "Classification : TERMUX_LOCAL_AAPT2_CONFIGURE_FAIL"
    echo "cmake_exit_code=$CONFIG_STATUS"
    echo "log=$LOG_FILE"
    exit "$CONFIG_STATUS"
fi

echo
echo "Building AAPT2 with jobs=$JOBS..."
set +e
cmake --build "$BUILD_DIR" --target aapt2 -- -j"$JOBS" 2>&1 | tee -a "$LOG_FILE"
BUILD_STATUS=${PIPESTATUS[0]}
set -e

if [ "$BUILD_STATUS" -ne 0 ]; then
    echo "Classification : TERMUX_LOCAL_AAPT2_BUILD_FAIL"
    echo "build_exit_code=$BUILD_STATUS"
    echo "log=$LOG_FILE"
    exit "$BUILD_STATUS"
fi

BUILT_BIN="$(
    find "$BUILD_DIR" -type f -name aapt2 -perm -u+x -print -quit 2>/dev/null
)"

if [ -z "$BUILT_BIN" ] || [ ! -x "$BUILT_BIN" ]; then
    echo "AAPT2_BUILD_OUTPUT_MISSING" >&2
    exit 5
fi

cp -f "$BUILT_BIN" "$INSTALL_BIN"
chmod 700 "$INSTALL_BIN"

AAPT2_VERSION="$("$INSTALL_BIN" version 2>&1 | awk 'NR == 1 {line=$0} END {print line}')"
AAPT2_SHA256="$(sha256sum "$INSTALL_BIN" | awk '{print $1}')"

PLATFORM_PACKAGE="$(
    python - "$ROOT/toolchains/android-build-lock.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print(data["android"]["platformPackage"])
PY
)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME/android.jar"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "ANDROID_JAR_MISSING=$ANDROID_JAR" >&2
    exit 6
fi

PROBE_DIR="$WORK_ROOT/probe"
rm -rf "$PROBE_DIR"
mkdir -p "$PROBE_DIR"
cat > "$PROBE_DIR/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pocketpc.localaapt2probe">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37" />
    <application />
</manifest>
EOF

set +e
"$INSTALL_BIN" link \
    -o "$PROBE_DIR/probe.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROBE_DIR/AndroidManifest.xml" \
    >"$PROBE_DIR/link.log" 2>&1
PROBE_STATUS=$?
set -e

{
    echo "classification=TERMUX_LOCAL_AAPT2_BUILD"
    echo "source_repo=$SOURCE_REPO"
    echo "source_tag=$SOURCE_TAG"
    echo "source_sha=$SOURCE_SHA"
    echo "termux_variant=$TERMUX_VARIANT"
    echo "binary=$INSTALL_BIN"
    echo "binary_sha256=$AAPT2_SHA256"
    echo "tool_version=$AAPT2_VERSION"
    echo "platform_package=$PLATFORM_PACKAGE"
    echo "android_jar=$ANDROID_JAR"
    echo "probe_exit_code=$PROBE_STATUS"
} > "$EVIDENCE_FILE"

echo
echo "Evidence"
cat "$EVIDENCE_FILE"

if [ "$PROBE_STATUS" -ne 0 ]; then
    echo "===== LOCAL AAPT2 PLATFORM PROBE ====="
    cat "$PROBE_DIR/link.log"
    echo "===== END LOCAL AAPT2 PLATFORM PROBE ====="
    echo "Classification : TERMUX_LOCAL_AAPT2_PLATFORM_FAIL"
    exit 7
fi

echo
echo "Classification : TERMUX_LOCAL_AAPT2_BUILD_PASS"
echo "Important: this validates only the locally built AAPT2 binary against the locked Android platform."
echo "It does not build, sign, install, or physically validate the PocketPC APK."
