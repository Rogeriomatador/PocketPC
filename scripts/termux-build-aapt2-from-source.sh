#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
SOURCE_REPO="https://github.com/termux/android-build-tools.git"
SOURCE_VERSION="16.0.0.4"
SOURCE_REVISION="c4edf8539a34a8600538e6642c1ecb170452a79e"
WORK_ROOT="$HOME/.local/pocketpc/aapt2-source/$SOURCE_VERSION"
SOURCE_DIR="$WORK_ROOT/source"
BUILD_DIR="$WORK_ROOT/build"
INSTALL_DIR="$HOME/.local/pocketpc/aapt2/$SOURCE_VERSION"
AAPT2_OUT="$INSTALL_DIR/aapt2"

echo "PocketPC Termux local AAPT2 source build"
echo "Classification : TERMUX_LOCAL_AAPT2_SOURCE_BUILD_ATTEMPT"
echo "source_repo=$SOURCE_REPO"
echo "source_version=$SOURCE_VERSION"
echo "source_revision=$SOURCE_REVISION"
echo

TERMUX_VARIANT="$(bash "$ROOT/scripts/termux-detect-variant.sh" --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"

bash "$ROOT/scripts/termux-repository-check.sh" --require-compatible

for tool in git cmake ninja clang python pkg-config; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$tool" >&2
        exit 2
    }
done

if ! command -v protoc >/dev/null 2>&1; then
    echo "protoc=MISSING"
fi

if command -v pkg >/dev/null 2>&1; then
    REQUIRED_PACKAGES=(
        bison
        flex
        cmake
        ninja
        git
        pkg-config
        clang
        linux-headers
        fmt
        googletest
        libexpat
        libpng
        protobuf
        libzopfli
        zlib
    )

    MISSING_PACKAGES=()
    for package in "${REQUIRED_PACKAGES[@]}"; do
        if ! dpkg-query -W -f='${Status}' "$package" 2>/dev/null | grep -q 'install ok installed'; then
            if apt-cache show "$package" >/dev/null 2>&1; then
                MISSING_PACKAGES+=("$package")
            else
                echo "TERMUX_AAPT2_BUILD_DEPENDENCY_UNAVAILABLE=$package" >&2
                exit 21
            fi
        fi
    done

    if [ "${#MISSING_PACKAGES[@]}" -gt 0 ]; then
        echo "Installing source-build dependencies from the active Termux variant repository:"
        printf '  %s\n' "${MISSING_PACKAGES[@]}"
        pkg install -y "${MISSING_PACKAGES[@]}"
    fi
fi

command -v protoc >/dev/null 2>&1 || {
    echo "MISSING_TOOL=protoc" >&2
    exit 22
}

rm -rf "$WORK_ROOT"
mkdir -p "$WORK_ROOT" "$INSTALL_DIR"

git clone \
    --recurse-submodules \
    --branch "$SOURCE_VERSION" \
    --depth 1 \
    "$SOURCE_REPO" \
    "$SOURCE_DIR"

ACTUAL_REVISION="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
echo "actual_source_revision=$ACTUAL_REVISION"
if [ "$ACTUAL_REVISION" != "$SOURCE_REVISION" ]; then
    echo "AAPT2_SOURCE_REVISION_MISMATCH expected=$SOURCE_REVISION actual=$ACTUAL_REVISION" >&2
    exit 23
fi

API_LEVEL="$(getprop ro.build.version.sdk 2>/dev/null || printf '24')"
export CFLAGS="${CFLAGS:-} -fPIC"
export CXXFLAGS="${CXXFLAGS:-} -fPIC"
export CPPFLAGS="${CPPFLAGS:-} -DNDEBUG -D__ANDROID_SDK_VERSION__=__ANDROID_API__ -D_FILE_OFFSET_BITS=64 -DPROTOBUF_USE_DLLS"

cmake \
    -S "$SOURCE_DIR" \
    -B "$BUILD_DIR" \
    -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DANDROID_BUILD_TOOLS_DEV_MODE=ON \
    -Dprotobuf_generate_PROTOC_EXE="$(command -v protoc)"

cmake --build "$BUILD_DIR" --parallel "$(nproc 2>/dev/null || printf '2')"

BUILT_AAPT2="$(
    find "$BUILD_DIR" -type f -name aapt2 -perm -u+x -print |
        head -1
)"
if [ -z "$BUILT_AAPT2" ] || [ ! -x "$BUILT_AAPT2" ]; then
    echo "AAPT2_BUILD_OUTPUT_MISSING" >&2
    exit 24
fi

cp "$BUILT_AAPT2" "$AAPT2_OUT"
chmod 700 "$AAPT2_OUT"

echo "built_aapt2=$AAPT2_OUT"
"$AAPT2_OUT" version

PLATFORM_PACKAGE="$(
    python - "$LOCK" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print(data["android"]["platformPackage"])
PY
)"
PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
ANDROID_JAR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
    echo "ANDROID_JAR_MISSING=$ANDROID_JAR" >&2
    exit 25
fi

PROBE_DIR="$WORK_ROOT/probe"
mkdir -p "$PROBE_DIR"
cat > "$PROBE_DIR/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.pocketpc.aapt2sourceprobe">
    <uses-sdk android:minSdkVersion="23" android:targetSdkVersion="37" />
    <application />
</manifest>
EOF

"$AAPT2_OUT" link \
    -o "$PROBE_DIR/probe.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROBE_DIR/AndroidManifest.xml"

mkdir -p "$HOME/.local/pocketpc/aapt2"
printf '%s\n' "$AAPT2_OUT" > "$HOME/.local/pocketpc/aapt2/current-path"

echo
echo "Classification : TERMUX_LOCAL_AAPT2_SOURCE_BUILD_PASS"
echo "aapt2=$AAPT2_OUT"
echo "source_revision=$ACTUAL_REVISION"
echo "platform_package=$PLATFORM_PACKAGE"
echo "Important: PASS proves this locally built AAPT2 linked a minimal manifest against the locked Android platform."
echo "Important: It does not prove the PocketPC unit tests or APK build pass."
