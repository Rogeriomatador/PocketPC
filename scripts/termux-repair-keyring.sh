#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
TMP_ROOT="${TMPDIR:-$PREFIX_DIR/tmp}/pocketpc-termux-keyring"
INDEX_URL="https://packages-cf.termux.dev/apt/termux-main/pool/main/t/termux-keyring/"
BASE_URL="https://packages-cf.termux.dev/apt/termux-main/pool/main/t/termux-keyring"
EXPECTED_PACKAGE="termux-keyring"

echo "PocketPC Termux keyring recovery"
echo "Classification : TERMUX_KEYRING_RECOVERY_DIAGNOSTIC"
echo

TERMUX_VARIANT="$(bash "$ROOT/scripts/termux-detect-variant.sh" --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"

if [ "$TERMUX_VARIANT" = "googleplay" ]; then
    echo "Classification : TERMUX_KEYRING_REPAIR_BLOCKED_GOOGLE_PLAY"
    echo "Important: Google Play Termux uses a separate repository/package set."
    echo "Important: Do not install the classic packages.termux.dev keyring into this variant."
    exit 20
fi

if [ "$MODE" != "--apply" ]; then
    echo "apply_required=true"
    echo "command=bash scripts/termux-repair-keyring.sh --apply"
    echo "Important: this changes the Termux package keyring. Run only if you want to repair it."
    exit 15
fi

for tool in curl python dpkg dpkg-deb sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "MISSING_TOOL=$tool" >&2
        exit 2
    }
done

mkdir -p "$TMP_ROOT"
INDEX="$TMP_ROOT/index.html"
DEB="$TMP_ROOT/termux-keyring.deb"
APT_LOG="$TMP_ROOT/apt-update.log"

echo "Fetching official Termux keyring index..."
curl --fail --location --proto '=https' --tlsv1.2 \
    "$INDEX_URL" \
    --output "$INDEX"

DEB_NAME="$(
    python - "$INDEX" <<'PY'
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
names = sorted(
    set(re.findall(r'termux-keyring_([0-9][A-Za-z0-9.+:~-]*)_all[.]deb', text)),
    key=lambda value: tuple(
        int(part) if part.isdigit() else part
        for part in re.split(r'([0-9]+)', value)
        if part != ""
    ),
)
if not names:
    raise SystemExit("TERMUX_KEYRING_INDEX_EMPTY")
print(f"termux-keyring_{names[-1]}_all.deb")
PY
)"

echo "selected_package=$DEB_NAME"
curl --fail --location --proto '=https' --tlsv1.2 \
    "$BASE_URL/$DEB_NAME" \
    --output "$DEB"

PACKAGE_NAME="$(dpkg-deb -f "$DEB" Package)"
PACKAGE_VERSION="$(dpkg-deb -f "$DEB" Version)"
PACKAGE_ARCH="$(dpkg-deb -f "$DEB" Architecture)"
PACKAGE_SHA256="$(sha256sum "$DEB" | awk '{print $1}')"

echo "package=$PACKAGE_NAME"
echo "version=$PACKAGE_VERSION"
echo "architecture=$PACKAGE_ARCH"
echo "sha256=$PACKAGE_SHA256"

if [ "$PACKAGE_NAME" != "$EXPECTED_PACKAGE" ]; then
    echo "TERMUX_KEYRING_PACKAGE_ID_MISMATCH=$PACKAGE_NAME" >&2
    exit 16
fi
if [ "$PACKAGE_ARCH" != "all" ]; then
    echo "TERMUX_KEYRING_ARCH_MISMATCH=$PACKAGE_ARCH" >&2
    exit 17
fi
if ! dpkg-deb -c "$DEB" | grep -q 'termux-autobuilds[.]gpg'; then
    echo "TERMUX_KEYRING_AUTOBUILDS_KEY_MISSING" >&2
    exit 18
fi

echo
echo "Installing verified package metadata payload..."
dpkg -i "$DEB"

echo
echo "Validating repository signatures after keyring install..."
set +e
apt update 2>&1 | tee "$APT_LOG"
APT_STATUS=${PIPESTATUS[0]}
set -e

if [ "$APT_STATUS" -ne 0 ]; then
    echo "Classification : TERMUX_KEYRING_RECOVERY_APT_UPDATE_FAILED"
    echo "apt_exit_code=$APT_STATUS"
    exit "$APT_STATUS"
fi

if grep -Eq 'NO_PUBKEY|repository .* is not signed|signature verification failed' "$APT_LOG"; then
    echo "Classification : TERMUX_KEYRING_RECOVERY_SIGNATURE_STILL_INVALID"
    exit 19
fi

echo
echo "Classification : TERMUX_KEYRING_RECOVERY_PASS"
echo "installed_version=$PACKAGE_VERSION"
echo "installed_sha256=$PACKAGE_SHA256"
echo "Important: PASS means apt accepted repository signatures after the keyring repair."
