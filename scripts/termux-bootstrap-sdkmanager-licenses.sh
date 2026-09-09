#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
TMP_ROOT="${TMPDIR:-$PREFIX/tmp}/pocketpc-sdkmanager"
INDEX="$TMP_ROOT/repository2-3.xml"
REPOSITORY_XML_URL="https://dl.google.com/android/repository/repository2-3.xml"

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

SDKMANAGER=""
for candidate in     "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"     "$SDK_ROOT/cmdline-tools/bin/sdkmanager"
do
    if [ -f "$candidate" ]; then
        SDKMANAGER="$candidate"
        break
    fi
done

if [ -z "$SDKMANAGER" ]; then
    echo "sdkmanager=missing"
    echo "Downloading official Android command-line tools metadata..."
    curl --fail --location --silent --show-error         "$REPOSITORY_XML_URL"         --output "$INDEX"

    META="$(
        python - "$INDEX" <<'PY'
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
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

packages = []
for node in root.iter():
    if local(node.tag) != "remotePackage":
        continue
    p = node.attrib.get("path", "")
    if p.startswith("cmdline-tools;"):
        rev = child_text(node, "revision")
        packages.append(node)

def version_tuple(node):
    r = None
    for c in node:
        if local(c.tag) == "revision":
            r = c
            break
    if r is None:
        return (0,0,0,0)
    vals = []
    for n in ("major","minor","micro","preview"):
        t = child_text(r,n)
        try: vals.append(int(t or 0))
        except: vals.append(0)
    return tuple(vals)

if not packages:
    raise SystemExit("CMDLINE_TOOLS_PACKAGE_NOT_FOUND")

node = max(packages, key=version_tuple)
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

    rm -rf "$SDK_ROOT/cmdline-tools/latest"
    mkdir -p "$SDK_ROOT/cmdline-tools/latest"
    cp -a "$SOURCE/." "$SDK_ROOT/cmdline-tools/latest/"

    SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

    if command -v termux-fix-shebang >/dev/null 2>&1; then
        termux-fix-shebang "$SDKMANAGER" || true
    else
        sed -i "1s|^#!.*|#!$PREFIX/bin/bash|" "$SDKMANAGER"
    fi

    chmod +x "$SDKMANAGER"
fi

echo "sdkmanager=$SDKMANAGER"
echo
echo "Android requires you to accept the SDK license yourself."
echo "The following command is interactive; answer y to agreements you accept."
echo

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses

echo
echo "Classification : SDK_LICENSE_SETUP_COMPLETED"
echo "sdk_root=$SDK_ROOT"
