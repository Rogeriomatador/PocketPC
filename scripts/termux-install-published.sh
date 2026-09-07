#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

FEED_URL="https://raw.githubusercontent.com/Rogeriomatador/PocketPC/main/updates/stable.json"
DOWNLOAD_DIR="${POCKETPC_DOWNLOAD_DIR:-/sdcard/Download}"
TMP_DIR="${TMPDIR:-/data/data/com.termux/files/usr/tmp}"
FEED_FILE="$TMP_DIR/pocketpc-stable-feed.json"

echo "PocketPC on-device published APK helper"
echo "Classification : DOWNLOAD_INSTALL_HELPER"
echo

need() {
    command -v "$1" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$1" >&2
        exit 2
    }
}

need curl
need python
need sha256sum

mkdir -p "$DOWNLOAD_DIR"
mkdir -p "$TMP_DIR"

curl --fail --location --silent --show-error     "$FEED_URL"     --output "$FEED_FILE"

readarray -t META < <(
    python - "$FEED_FILE" <<'PY'
import json
import re
import sys

data = json.load(open(sys.argv[1], "r", encoding="utf-8"))

published = data.get("published") is True
version_name = str(data.get("versionName", ""))
version_code = int(data.get("versionCode", 0))
package_name = str(data.get("packageName", ""))
apk_url = str(data.get("apkUrl", ""))
apk_sha256 = str(data.get("apkSha256", "")).lower()
source_revision = str(data.get("sourceRevision", "")).lower()

if package_name != "dev.pocketpc.core":
    raise SystemExit("feed packageName does not match PocketPC")
if not version_name:
    raise SystemExit("feed versionName is empty")
if version_code <= 0:
    raise SystemExit("feed versionCode is invalid")

print("true" if published else "false")
print(version_name)
print(version_code)
print(apk_url)
print(apk_sha256)
print(source_revision)

if published:
    if not apk_url.startswith("https://"):
        raise SystemExit("published APK URL must use HTTPS")
    if not re.fullmatch(r"[0-9a-f]{64}", apk_sha256):
        raise SystemExit("published APK SHA-256 is invalid")
    if not re.fullmatch(r"[0-9a-f]{40}", source_revision):
        raise SystemExit("published source revision is invalid")
PY
)

PUBLISHED="${META[0]}"
VERSION_NAME="${META[1]}"
VERSION_CODE="${META[2]}"
APK_URL="${META[3]}"
EXPECTED_SHA="${META[4]}"
SOURCE_REVISION="${META[5]}"

echo "Feed"
echo "  version=$VERSION_NAME"
echo "  version_code=$VERSION_CODE"
echo "  published=$PUBLISHED"
echo "  source_revision=${SOURCE_REVISION:-UNPUBLISHED}"
echo

if [ "$PUBLISHED" != "true" ]; then
    echo "No signed/published PocketPC APK is available in the stable feed."
    echo "Classification : POCKETPC_ON_DEVICE_NO_PUBLISHED_APK"
    exit 3
fi

APK_PATH="$DOWNLOAD_DIR/PocketPC-$VERSION_NAME.apk"

echo "Downloading"
echo "  target=$APK_PATH"

curl --fail --location --show-error     "$APK_URL"     --output "$APK_PATH"

ACTUAL_SHA="$(sha256sum "$APK_PATH" | awk '{print tolower($1)}')"

echo
echo "Verification"
echo "  expected_sha256=$EXPECTED_SHA"
echo "  actual_sha256=$ACTUAL_SHA"

if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
    rm -f "$APK_PATH"
    echo "SHA256_MISMATCH" >&2
    echo "Classification : POCKETPC_APK_DOWNLOAD_REJECTED"
    exit 4
fi

echo "  sha256=PASS"
echo
echo "APK downloaded and hash-verified."

if command -v termux-open >/dev/null 2>&1; then
    echo "Requesting Android package installer through termux-open..."
    termux-open --view "$APK_PATH" || {
        echo "Installer request failed. APK remains at:"
        echo "  $APK_PATH"
        echo "Classification : POCKETPC_APK_VERIFIED_INSTALLER_NOT_OPENED"
        exit 5
    }
    echo
    echo "Classification : POCKETPC_APK_VERIFIED_INSTALLER_REQUESTED"
else
    echo "termux-open is unavailable."
    echo "Open this APK manually from Android Files:"
    echo "  $APK_PATH"
    echo "Classification : POCKETPC_APK_DOWNLOADED_VERIFIED"
fi

echo
echo "Important:"
echo "  Android may require 'Install unknown apps' permission for Termux."
echo "  Signature compatibility is still enforced by Android."
echo "  This helper does not bypass the package installer or signature checks."
