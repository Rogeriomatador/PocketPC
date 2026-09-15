#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
APT_ETC="$PREFIX_DIR/etc/apt"
MODE="${1:-diagnostic}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

collect_sources() {
    python - "$APT_ETC" <<'PY'
from pathlib import Path
import sys

apt_etc = Path(sys.argv[1])
records = []

def add(uri: str, source_file: Path) -> None:
    uri = uri.strip()
    if uri:
        records.append((str(source_file), uri))

legacy = apt_etc / "sources.list"
if legacy.is_file():
    for raw in legacy.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if parts and parts[0] in {"deb", "deb-src"} and len(parts) >= 2:
            # Skip [option=value ...] if present.
            index = 1
            if parts[index].startswith("["):
                while index < len(parts) and not parts[index].endswith("]"):
                    index += 1
                index += 1
            if index < len(parts):
                add(parts[index], legacy)

sources_dir = apt_etc / "sources.list.d"
if sources_dir.is_dir():
    for path in sorted(sources_dir.iterdir()):
        if path.suffix == ".list" and path.is_file():
            for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split()
                if parts and parts[0] in {"deb", "deb-src"} and len(parts) >= 2:
                    index = 1
                    if parts[index].startswith("["):
                        while index < len(parts) and not parts[index].endswith("]"):
                            index += 1
                        index += 1
                    if index < len(parts):
                        add(parts[index], path)
        elif path.suffix == ".sources" and path.is_file():
            enabled = True
            uris = []
            for raw in path.read_text(encoding="utf-8", errors="replace").splitlines() + [""]:
                line = raw.strip()
                if not line:
                    if enabled:
                        for uri in uris:
                            add(uri, path)
                    enabled = True
                    uris = []
                    continue
                if line.startswith("#"):
                    continue
                key, sep, value = line.partition(":")
                if not sep:
                    continue
                key = key.strip().lower()
                value = value.strip()
                if key == "enabled" and value.lower() == "no":
                    enabled = False
                elif key == "uris":
                    uris.extend(value.split())

for source_file, uri in records:
    print(f"{source_file}|{uri}")
PY
}

SOURCES="$(collect_sources || true)"
echo "PocketPC Termux repository check"
echo "Classification : TERMUX_REPOSITORY_DIAGNOSTIC"

if [ -z "$SOURCES" ]; then
    echo "repository_state=UNKNOWN_NO_ACTIVE_DEB_SOURCE"
    if [ "$MODE" = "--require-compatible" ] || [ "$MODE" = "--require-modern" ]; then
        exit 14
    fi
    exit 0
fi

printf '%s\n' "$SOURCES" | while IFS='|' read -r source_file uri; do
    echo "  source_file=$source_file"
    echo "  source_uri=$uri"
done

TERMUX_VARIANT="$(bash "$SCRIPT_DIR/termux-detect-variant.sh" --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"

SOURCE_URIS="$(printf '%s\n' "$SOURCES" | cut -d'|' -f2-)"
HAS_TERMUX_NET=false
HAS_CLASSIC_MAIN=false

if printf '%s\n' "$SOURCE_URIS" | grep -Eq 'https?://([^/]*[.])?termux[.]net([/[:space:]]|$)'; then
    HAS_TERMUX_NET=true
fi
if printf '%s\n' "$SOURCE_URIS" | grep -Eq 'https?://([^/]*[.])?packages(-cf)?[.]termux[.]dev/apt/termux-main/?([[:space:]]|$)'; then
    HAS_CLASSIC_MAIN=true
fi

if [ "$TERMUX_VARIANT" = "googleplay" ]; then
    if [ "$HAS_TERMUX_NET" = true ] && [ "$HAS_CLASSIC_MAIN" = true ]; then
        echo "repository_state=MIXED_GOOGLE_PLAY_AND_CLASSIC"
        echo "action_required=REMOVE_CLASSIC_REPOSITORY_FROM_GOOGLE_PLAY_TERMUX"
        echo "note=Google Play Termux uses its own package set; do not mix packages.termux.dev with termux.net."
        echo "recovery_command=bash scripts/termux-repair-repository.sh --apply"
        echo "recovery_hint=The repair script backs up apt configuration, removes the incompatible classic entry, and keeps the Google Play source."
        echo "Classification : TERMUX_REPOSITORY_VARIANT_MIXED"
        if [ "$MODE" = "--require-compatible" ] || [ "$MODE" = "--require-modern" ]; then
            exit 15
        fi
        exit 0
    fi

    if [ "$HAS_CLASSIC_MAIN" = true ]; then
        echo "repository_state=WRONG_CLASSIC_REPOSITORY_FOR_GOOGLE_PLAY"
        echo "action_required=RESTORE_GOOGLE_PLAY_REPOSITORY"
        echo "note=Google Play Termux packages are maintained separately from the classic Termux repository."
        echo "Classification : TERMUX_REPOSITORY_VARIANT_MISMATCH"
        if [ "$MODE" = "--require-compatible" ] || [ "$MODE" = "--require-modern" ]; then
            exit 16
        fi
        exit 0
    fi

    if [ "$HAS_TERMUX_NET" = true ]; then
        echo "repository_state=GOOGLE_PLAY_PRIMARY"
        echo "Classification : TERMUX_REPOSITORY_GOOGLE_PLAY_OK"
        exit 0
    fi

    echo "repository_state=GOOGLE_PLAY_CUSTOM_OR_UNKNOWN"
    echo "Classification : TERMUX_REPOSITORY_GOOGLE_PLAY_UNKNOWN"
    if [ "$MODE" = "--require-compatible" ] || [ "$MODE" = "--require-modern" ]; then
        exit 17
    fi
    exit 0
fi

if [ "$HAS_TERMUX_NET" = true ]; then
    echo "repository_state=LEGACY_TERMUX_NET_CLASSIC"
    echo "recommended_main_repo=deb https://packages.termux.dev/apt/termux-main stable main"
    echo "action_required=CHANGE_CLASSIC_TERMUX_MAIN_REPOSITORY"
    echo "Classification : TERMUX_REPOSITORY_LEGACY"
    if [ "$MODE" = "--require-compatible" ] || [ "$MODE" = "--require-modern" ]; then
        exit 13
    fi
    exit 0
fi

if [ "$HAS_CLASSIC_MAIN" = true ]; then
    echo "repository_state=CLASSIC_PRIMARY"
    echo "Classification : TERMUX_REPOSITORY_PRIMARY_OK"
    exit 0
fi

echo "repository_state=NON_PRIMARY_MIRROR_OR_CUSTOM"
echo "note=Repository compatibility is not proven by host name alone."
echo "Classification : TERMUX_REPOSITORY_NON_PRIMARY"
exit 0
