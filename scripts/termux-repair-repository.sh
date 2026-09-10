#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:-}"
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
APT_ETC="$PREFIX_DIR/etc/apt"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$HOME/.pocketpc/termux-apt-backups/$STAMP"

echo "PocketPC Termux repository repair"
echo "Classification : TERMUX_REPOSITORY_REPAIR_DIAGNOSTIC"
echo

if [ "$MODE" != "--apply" ]; then
    echo "apply_required=true"
    echo "command=bash scripts/termux-repair-repository.sh --apply"
    exit 15
fi

command -v python >/dev/null 2>&1 || {
    echo "MISSING_TOOL=python" >&2
    exit 2
}

mkdir -p "$(dirname "$BACKUP")"
cp -a "$APT_ETC" "$BACKUP"
echo "backup=$BACKUP"

python - "$APT_ETC" <<'PY'
from pathlib import Path
import sys

apt_etc = Path(sys.argv[1])
legacy_token = "termux.net"

def clean_list(path: Path) -> None:
    if not path.is_file():
        return
    out = []
    changed = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if legacy_token in raw and raw.lstrip().startswith(("deb ", "deb-src ")):
            out.append("# PocketPC disabled legacy source: " + raw)
            changed = True
        else:
            out.append(raw)
    if changed:
        path.write_text("\n".join(out) + "\n", encoding="utf-8")
        print(f"disabled_legacy_list={path}")

def clean_sources(path: Path) -> None:
    text = path.read_text(encoding="utf-8", errors="replace")
    stanzas = []
    current = []
    for raw in text.splitlines():
        if not raw.strip():
            if current:
                stanzas.append(current)
                current = []
        else:
            current.append(raw)
    if current:
        stanzas.append(current)

    kept = []
    removed = 0
    for stanza in stanzas:
        joined = "\n".join(stanza)
        if legacy_token in joined:
            removed += 1
        else:
            kept.append(stanza)

    if removed:
        rendered = "\n\n".join("\n".join(stanza) for stanza in kept)
        if rendered:
            rendered += "\n"
        path.write_text(rendered, encoding="utf-8")
        print(f"removed_legacy_deb822_stanzas={path}:{removed}")

clean_list(apt_etc / "sources.list")

sources_dir = apt_etc / "sources.list.d"
if sources_dir.is_dir():
    for path in sorted(sources_dir.iterdir()):
        if not path.is_file():
            continue
        if path.suffix == ".list":
            clean_list(path)
        elif path.suffix == ".sources":
            clean_sources(path)

main = apt_etc / "sources.list"
official = "deb https://packages.termux.dev/apt/termux-main stable main"
existing = main.read_text(encoding="utf-8", errors="replace") if main.is_file() else ""
if official not in existing:
    with main.open("a", encoding="utf-8") as out:
        if existing and not existing.endswith("\n"):
            out.write("\n")
        out.write(official + "\n")
    print(f"added_official_main={main}")
PY

echo
bash "$ROOT/scripts/termux-repository-check.sh" --require-modern

echo
echo "Classification : TERMUX_REPOSITORY_REPAIR_PASS"
echo "backup=$BACKUP"
echo "Important: this only repairs repository configuration; package signatures are validated separately."
