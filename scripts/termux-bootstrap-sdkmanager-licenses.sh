#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
TMP_ROOT="${TMPDIR:-$PREFIX/tmp}/pocketpc-sdkmanager"
INDEX="$TMP_ROOT/repository2-3.xml"
REPOSITORY_XML_URL="https://dl.google.com/android/repository/repository2-3.xml"
CMDLINE_TOOLS_VERSION="19.0"
CMDLINE_TOOLS_PATH="cmdline-tools;$CMDLINE_TOOLS_VERSION"
CMDLINE_TOOLS_DIR="$SDK_ROOT/cmdline-tools/$CMDLINE_TOOLS_VERSION"

echo "PocketPC Termux sdkmanager/license bootstrap"
echo "Classification : SDK_LICENSE_SETUP_ATTEMPT"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

for tool in python curl unzip java sha1sum sha256sum; do
    need "$tool"
done

mkdir -p "$TMP_ROOT" "$SDK_ROOT/cmdline-tools"

SDKMANAGER="$CMDLINE_TOOLS_DIR/bin/sdkmanager"

if [ ! -f "$SDKMANAGER" ]; then
    echo "sdkmanager_pinned=missing:$CMDLINE_TOOLS_PATH"
    echo "Downloading official Android command-line tools metadata..."
    curl --fail --location --silent --show-error         "$REPOSITORY_XML_URL"         --output "$INDEX"

    META="$(
        python - "$INDEX" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
wanted = "cmdline-tools;19.0"
root = ET.parse(path).getroot()

def local(tag):
    return tag.rsplit("}", 1)[-1]

def children(node, name):
    return [x for x in node.iter() if local(x.tag) == name]

def child_text(node, name):
    for x in node.iter():
        if local(x.tag) == name and x.text:
            return x.text.strip()
    return None

node = None
for candidate in root.iter():
    if local(candidate.tag) != "remotePackage":
        continue
    if candidate.attrib.get("path", "") == wanted:
        node = candidate
        break

if node is None:
    raise SystemExit("CMDLINE_TOOLS_PINNED_PACKAGE_NOT_FOUND:" + wanted)
archive = None
for a in children(node, "archive"):
    host = child_text(a, "host-os")
    if host == "linux":
        archive = a
        break
if archive is None:
    raise SystemExit("CMDLINE_TOOLS_LINUX_ARCHIVE_NOT_FOUND")

complete = None
for c in archive.iter():
    if local(c.tag) == "complete":
        complete = c
        break
if complete is None:
    raise SystemExit("CMDLINE_TOOLS_COMPLETE_ARCHIVE_NOT_FOUND")

url = child_text(complete, "url")
checksum = None
ctype = "sha1"
for c in complete.iter():
    if local(c.tag) == "checksum" and c.text:
        checksum = c.text.strip().lower()
        ctype = c.attrib.get("type", "sha1").lower()
        break

if not url or not checksum:
    raise SystemExit("CMDLINE_TOOLS_METADATA_INCOMPLETE")

print(node.attrib.get("path",""))
print(url)
print(ctype)
print(checksum)
PY
    )"

    mapfile -t lines <<< "$META"
    PACKAGE_PATH="${lines[0]}"
    ARCHIVE_NAME="${lines[1]}"
    CHECKSUM_TYPE="${lines[2]}"
    EXPECTED="${lines[3]}"
    ZIP="$TMP_ROOT/cmdline-tools.zip"
    EXTRACT="$TMP_ROOT/extract"

    echo "package=$PACKAGE_PATH"
    echo "archive=$ARCHIVE_NAME"

    rm -f "$ZIP"
    rm -rf "$EXTRACT"
    mkdir -p "$EXTRACT"

    curl --fail --location --show-error         "https://dl.google.com/android/repository/$ARCHIVE_NAME"         --output "$ZIP"

    case "$CHECKSUM_TYPE" in
        sha1)
            ACTUAL="$(sha1sum "$ZIP" | awk '{print tolower($1)}')"
            ;;
        sha256)
            ACTUAL="$(sha256sum "$ZIP" | awk '{print tolower($1)}')"
            ;;
        *)
            echo "UNSUPPORTED_CHECKSUM_TYPE=$CHECKSUM_TYPE" >&2
            exit 4
            ;;
    esac

    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "CMDLINE_TOOLS_CHECKSUM_MISMATCH" >&2
        exit 5
    fi

    unzip -q "$ZIP" -d "$EXTRACT"
    SOURCE="$EXTRACT/cmdline-tools"
    if [ ! -d "$SOURCE" ]; then
        echo "CMDLINE_TOOLS_EXTRACT_LAYOUT_INVALID" >&2
        exit 6
    fi

    rm -rf "$CMDLINE_TOOLS_DIR"
    mkdir -p "$CMDLINE_TOOLS_DIR"
    cp -a "$SOURCE/." "$CMDLINE_TOOLS_DIR/"

    SDKMANAGER="$CMDLINE_TOOLS_DIR/bin/sdkmanager"

    if command -v termux-fix-shebang >/dev/null 2>&1; then
        termux-fix-shebang "$SDKMANAGER" || true
    else
        sed -i "1s|^#!.*|#!$PREFIX/bin/bash|" "$SDKMANAGER"
    fi

    chmod +x "$SDKMANAGER"
fi

echo "sdkmanager=$SDKMANAGER"
echo "cmdline_tools_version=$CMDLINE_TOOLS_VERSION"
echo
echo "Android requires you to accept the SDK license yourself."
echo "The following command is interactive; answer y to agreements you accept."
echo

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses

PLATFORM_PACKAGE="$(
    python - "$ROOT/toolchains/android-build-lock.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], "r", encoding="utf-8"))
print(data["android"]["platformPackage"])
PY
)"

echo
echo "Revalidating locked Android platform through sdkmanager..."
echo "platform_package=$PLATFORM_PACKAGE"
"$SDKMANAGER"     --sdk_root="$SDK_ROOT"     "$PLATFORM_PACKAGE"

PLATFORM_DIR_NAME="${PLATFORM_PACKAGE#platforms;}"
PLATFORM_DIR="$SDK_ROOT/platforms/$PLATFORM_DIR_NAME"

if [ ! -f "$PLATFORM_DIR/android.jar" ]; then
    echo "SDK_PLATFORM_ANDROID_JAR_MISSING=$PLATFORM_DIR/android.jar" >&2
    exit 7
fi

if [ ! -f "$PLATFORM_DIR/package.xml" ]; then
    echo "SDK_PLATFORM_PACKAGE_XML_MISSING=$PLATFORM_DIR/package.xml" >&2
    exit 8
fi

echo
echo "Classification : SDK_LICENSE_AND_PLATFORM_REGISTERED"
echo "sdk_root=$SDK_ROOT"
echo "platform_package=$PLATFORM_PACKAGE"
echo "package_xml=$PLATFORM_DIR/package.xml"
