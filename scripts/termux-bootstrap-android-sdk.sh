#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/toolchains/android-build-lock.json"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
REPOSITORY_XML_URL="https://dl.google.com/android/repository/repository2-3.xml"
TMP_ROOT="${TMPDIR:-$PREFIX/tmp}/pocketpc-android-sdk"

echo "PocketPC Termux Android SDK bootstrap"
echo "Classification : SDK_INSTALL_ONLY_NOT_A_BUILD"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

for tool in python curl unzip sha1sum sha256sum aapt2; do
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
LOCK_PLATFORM="$(read_lock android.platformPackage)"

mkdir -p "$TMP_ROOT" "$SDK_ROOT/platforms" "$SDK_ROOT/build-tools"
INDEX="$TMP_ROOT/repository2-1.xml"

echo "Repository index"
echo "  url=$REPOSITORY_XML_URL"
curl --fail --location --silent --show-error     "$REPOSITORY_XML_URL"     --output "$INDEX"

resolve_package() {
    local kind="$1"
    local version="$2"
    python - "$INDEX" "$kind" "$version" <<'PY'
import sys
import xml.etree.ElementTree as ET

index, kind, version = sys.argv[1:]
root = ET.parse(index).getroot()

def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]

def child_text(node, wanted):
    for child in node.iter():
        if local(child.tag) == wanted and child.text:
            return child.text.strip()
    return None

def find_children(node, wanted):
    return [child for child in node.iter() if local(child.tag) == wanted]

if kind == "platform":
    wanted_paths = {
        f"platforms;android-{version}",
        f"platforms;android-{version}.0",
    }
else:
    wanted_paths = {f"build-tools;{version}"}

packages = []
for node in root.iter():
    if local(node.tag) != "remotePackage":
        continue
    path = node.attrib.get("path", "")
    if path in wanted_paths:
        packages.append(node)

if not packages:
    raise SystemExit(f"SDK_PACKAGE_NOT_FOUND:{kind}:{version}")

node = packages[0]
archives = find_children(node, "archive")
selected = None

for archive in archives:
    host_os = child_text(archive, "host-os")
    if kind == "platform":
        if host_os in (None, "", "linux"):
            selected = archive
            break
    else:
        if host_os == "linux":
            selected = archive
            break

if selected is None and archives:
    selected = archives[0]
if selected is None:
    raise SystemExit(f"SDK_ARCHIVE_NOT_FOUND:{kind}:{version}")

complete = None
for child in selected.iter():
    if local(child.tag) == "complete":
        complete = child
        break
if complete is None:
    raise SystemExit(f"SDK_COMPLETE_ARCHIVE_NOT_FOUND:{kind}:{version}")

url = child_text(complete, "url")
checksum_node = None
for child in complete.iter():
    if local(child.tag) == "checksum":
        checksum_node = child
        break

if not url or checksum_node is None or not checksum_node.text:
    raise SystemExit(f"SDK_ARCHIVE_METADATA_INCOMPLETE:{kind}:{version}")

checksum = checksum_node.text.strip().lower()
algorithm = checksum_node.attrib.get("type", "sha1").lower()

print(node.attrib.get("path", ""))
print(url)
print(algorithm)
print(checksum)
PY
}

install_package() {
    local kind="$1"
    local version="$2"
    local target="$3"

    local resolved
    if ! resolved="$(resolve_package "$kind" "$version")"; then
        echo "SDK_PACKAGE_RESOLUTION_FAILED kind=$kind version=$version" >&2
        exit 4
    fi

    local meta=()
    mapfile -t meta <<< "$resolved"

    if [ "${#meta[@]}" -lt 4 ]; then
        echo "SDK_PACKAGE_METADATA_INCOMPLETE kind=$kind version=$version" >&2
        exit 4
    fi

    local package_path="${meta[0]}"
    local archive_name="${meta[1]}"
    local algorithm="${meta[2]}"
    local expected="${meta[3]}"
    local url="https://dl.google.com/android/repository/$archive_name"
    local zip="$TMP_ROOT/$kind-$version.zip"
    local extract="$TMP_ROOT/$kind-$version-extract"

    echo
    echo "Installing $package_path"
    echo "  url=$url"
    echo "  checksum_type=$algorithm"

    rm -f "$zip"
    rm -rf "$extract"
    mkdir -p "$extract"

    curl --fail --location --show-error "$url" --output "$zip"

    local actual
    case "$algorithm" in
        sha1)
            actual="$(sha1sum "$zip" | awk '{print tolower($1)}')"
            ;;
        sha256)
            actual="$(sha256sum "$zip" | awk '{print tolower($1)}')"
            ;;
        *)
            echo "UNSUPPORTED_CHECKSUM_TYPE=$algorithm" >&2
            exit 4
            ;;
    esac

    echo "  expected_checksum=$expected"
    echo "  actual_checksum=$actual"

    if [ "$actual" != "$expected" ]; then
        rm -f "$zip"
        echo "SDK_ARCHIVE_CHECKSUM_MISMATCH=$package_path" >&2
        exit 5
    fi

    unzip -q "$zip" -d "$extract"

    local source_dir
    source_dir="$(
        python - "$extract" <<'PY'
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
matches = sorted(
    root.rglob("source.properties"),
    key=lambda path: (len(path.parts), str(path)),
)
if not matches:
    raise SystemExit(1)
print(matches[0].parent)
PY
    )" || {
        echo "SDK_ARCHIVE_SOURCE_PROPERTIES_MISSING=$package_path" >&2
        exit 6
    }

    rm -rf "$target"
    mkdir -p "$target"
    cp -a "$source_dir/." "$target/"

    rm -f "$zip"
    rm -rf "$extract"

    echo "  installed=$target"
}

PLATFORM_DIR_NAME="${LOCK_PLATFORM#platforms;}"
PLATFORM_DIR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME"
BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/$BUILD_TOOLS"

install_package "platform" "$COMPILE_SDK" "$PLATFORM_DIR"
install_package "build-tools" "$BUILD_TOOLS" "$BUILD_TOOLS_DIR"

if [ ! -f "$PLATFORM_DIR/android.jar" ]; then
    echo "ANDROID_JAR_INSTALL_FAILED=$PLATFORM_DIR/android.jar" >&2
    exit 7
fi

if [ ! -f "$BUILD_TOOLS_DIR/lib/d8.jar" ]; then
    echo "BUILD_TOOLS_JAVA_PAYLOAD_MISSING=$BUILD_TOOLS_DIR" >&2
    exit 8
fi

cat > "$ROOT/local.properties" <<EOF
sdk.dir=$SDK_ROOT
EOF

mkdir -p "$HOME/.gradle"
GRADLE_USER_PROPERTIES="$HOME/.gradle/gradle.properties"
BEGIN="# >>> PocketPC Termux Android build >>>"
END="# <<< PocketPC Termux Android build <<<"
TMP_PROPERTIES="$(mktemp)"

if [ -f "$GRADLE_USER_PROPERTIES" ]; then
    awk -v begin="$BEGIN" -v end="$END" '
        $0 == begin {skip=1; next}
        $0 == end {skip=0; next}
        !skip {print}
    ' "$GRADLE_USER_PROPERTIES" > "$TMP_PROPERTIES"
else
    : > "$TMP_PROPERTIES"
fi

{
    cat "$TMP_PROPERTIES"
    echo "$BEGIN"
    echo "android.aapt2FromMavenOverride=$(command -v aapt2)"
    echo "$END"
} > "$GRADLE_USER_PROPERTIES"
rm -f "$TMP_PROPERTIES"

PROFILE="$HOME/.profile"
PBEGIN="# >>> PocketPC Android SDK >>>"
PEND="# <<< PocketPC Android SDK <<<"
TMP_PROFILE="$(mktemp)"

if [ -f "$PROFILE" ]; then
    awk -v begin="$PBEGIN" -v end="$PEND" '
        $0 == begin {skip=1; next}
        $0 == end {skip=0; next}
        !skip {print}
    ' "$PROFILE" > "$TMP_PROFILE"
else
    : > "$TMP_PROFILE"
fi

{
    cat "$TMP_PROFILE"
    echo "$PBEGIN"
    echo "export ANDROID_HOME=\"$SDK_ROOT\""
    echo 'export ANDROID_SDK_ROOT="$ANDROID_HOME"'
    echo "$PEND"
} > "$PROFILE"
rm -f "$TMP_PROFILE"

echo
echo "SDK verification"
echo "  lock_platform=$LOCK_PLATFORM"
echo "  android_jar=$PLATFORM_DIR/android.jar"
echo "  build_tools=$BUILD_TOOLS_DIR"
echo "  aapt2_override=$(command -v aapt2)"
echo "  local_properties=$ROOT/local.properties"

echo
echo "Classification : TERMUX_ANDROID_SDK_BASE_INSTALLED"
echo
echo "Important:"
echo "  Google build-tools Linux archives contain desktop-host native binaries."
echo "  PocketPC does not claim those x86_64 binaries are executable on this ARM64 phone."
echo "  AAPT2 is explicitly overridden with the Termux-native binary."
echo "  This script does not compile Kotlin, build an APK, sign, install, or update PocketPC."
