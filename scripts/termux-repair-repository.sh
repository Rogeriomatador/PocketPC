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

TERMUX_VARIANT="$(bash "$ROOT/scripts/termux-detect-variant.sh" --value 2>/dev/null || printf '%s' classic_or_unknown)"
echo "termux_variant=$TERMUX_VARIANT"

mkdir -p "$(dirname "$BACKUP")"
cp -a "$APT_ETC" "$BACKUP"
echo "backup=$BACKUP"

python - "$APT_ETC" "$TERMUX_VARIANT" <<'PY'
from pathlib import Path
import sys

apt_etc = Path(sys.argv[1])
variant = sys.argv[2]

classic_hosts = ("packages.termux.dev", "packages-cf.termux.dev")
google_host = "termux.net"

def should_remove(text: str) -> bool:
    if variant == "googleplay":
        return any(host in text for host in classic_hosts)
    return google_host in text

def clean_list(path: Path) -> None:
    if not path.is_file():
        return
    out = []
    changed = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = raw.lstrip()
        if stripped.startswith(("deb ", "deb-src ")) and should_remove(raw):
            out.append("# PocketPC disabled incompatible source: " + raw)
            changed = True
        else:
            out.append(raw)
    if changed:
        path.write_text("\n".join(out) + "\n", encoding="utf-8")
        print(f"disabled_incompatible_list={path}")

def split_stanzas(text: str):
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
    return stanzas

def clean_sources(path: Path) -> None:
    text = path.read_text(encoding="utf-8", errors="replace")
    stanzas = split_stanzas(text)
    kept = []
    removed = 0
    for stanza in stanzas:
        joined = "\n".join(stanza)
        if should_remove(joined):
            removed += 1
        else:
            kept.append(stanza)
    if removed:
        rendered = "\n\n".join("\n".join(stanza) for stanza in kept)
        if rendered:
            rendered += "\n"
        path.write_text(rendered, encoding="utf-8")
        print(f"removed_incompatible_deb822_stanzas={path}:{removed}")

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

all_text = ""
for path in [apt_etc / "sources.list"]:
    if path.is_file():
        all_text += "\n" + path.read_text(encoding="utf-8", errors="replace")
if sources_dir.is_dir():
    for path in sources_dir.iterdir():
        if path.is_file() and path.suffix in {".list", ".sources"}:
            all_text += "\n" + path.read_text(encoding="utf-8", errors="replace")

main = apt_etc / "sources.list"
if variant == "googleplay":
    wanted = "deb https://termux.net stable main"
    present = "termux.net" in all_text
else:
    wanted = "deb https://packages.termux.dev/apt/termux-main stable main"
    present = any(host in all_text for host in classic_hosts)

if not present:
    existing = main.read_text(encoding="utf-8", errors="replace") if main.is_file() else ""
    with main.open("a", encoding="utf-8") as out:
        if existing and not existing.endswith("\n"):
            out.write("\n")
        out.write(wanted + "\n")
    print(f"added_compatible_main={main}:{wanted}")
PY

echo
bash "$ROOT/scripts/termux-repository-check.sh" --require-compatible

echo
echo "Classification : TERMUX_REPOSITORY_REPAIR_PASS"
echo "backup=$BACKUP"
echo "Important: repair is variant-aware and removes only incompatible main-repository entries."
