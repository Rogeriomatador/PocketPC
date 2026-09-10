#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
APT_ETC="$PREFIX_DIR/etc/apt"
MODE="${1:-diagnostic}"

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
    [ "$MODE" = "--require-modern" ] && exit 14
    exit 0
fi

printf '%s\n' "$SOURCES" | while IFS='|' read -r source_file uri; do
    echo "  source_file=$source_file"
    echo "  source_uri=$uri"
done

if printf '%s\n' "$SOURCES" | cut -d'|' -f2- | grep -Eq 'https?://([^/]*[.])?termux[.]net([/[:space:]]|$)'; then
    echo "repository_state=LEGACY_TERMUX_NET"
    echo "recommended_main_repo=deb https://packages.termux.dev/apt/termux-main stable main"
    echo "action_required=CHANGE_TERMUX_MAIN_REPOSITORY"
    echo "note=pkg_update_does_not_change_repository"
    if command -v termux-change-repo >/dev/null 2>&1; then
        echo "recovery_command=termux-change-repo"
        echo "recovery_hint=Select Main repository, then choose the packages.termux.dev primary mirror."
    else
        echo "recovery_command=manual_sources_list_edit"
        echo "recovery_file=$APT_ETC/sources.list"
        echo "recovery_backup=cp -a $APT_ETC/sources.list $APT_ETC/sources.list.pocketpc-backup"
        echo "recovery_source=deb https://packages.termux.dev/apt/termux-main stable main"
        echo "recovery_hint=Inspect every source_file printed above; disable any legacy termux.net entry, including *.sources files, then run pkg update."
    fi
    echo "Classification : TERMUX_REPOSITORY_LEGACY"
    [ "$MODE" = "--require-modern" ] && exit 13
    exit 0
fi

if printf '%s\n' "$SOURCES" | cut -d'|' -f2- | grep -Fq 'packages.termux.dev/apt/termux-main'; then
    echo "repository_state=OFFICIAL_PRIMARY"
    echo "Classification : TERMUX_REPOSITORY_PRIMARY_OK"
    exit 0
fi

echo "repository_state=NON_PRIMARY_MIRROR_OR_CUSTOM"
echo "note=Termux supports multiple mirrors; compatibility is determined by the AAPT2 probe."
echo "Classification : TERMUX_REPOSITORY_NON_PRIMARY"
exit 0
