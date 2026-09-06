#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINNED_TERMUX_COMMIT="32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68"

usage() {
  echo "Usage: $0 <termux-packages-checkout> <work-output-dir>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage

TERMUX_REPO="$(realpath "$1")"
WORK="$(mkdir -p "$2" && realpath "$2")"
REL_BUILD=".pocketpc-quarantine-build-$$"
HOST_BUILD="$TERMUX_REPO/$REL_BUILD"
PACKAGES="$HOST_BUILD/packages"
EXTRACTED="$WORK/extracted"
QUARANTINE="$WORK/quarantine/arm64-v8a"
REPORT="$WORK/proot-elf-audit.json"

cleanup() {
  rm -rf "$HOST_BUILD"
}
trap cleanup EXIT

command -v git >/dev/null
command -v docker >/dev/null
command -v dpkg-deb >/dev/null
command -v python3 >/dev/null
command -v readlink >/dev/null

[[ -d "$TERMUX_REPO/.git" ]] || {
  echo "Not a git checkout: $TERMUX_REPO" >&2
  exit 1
}

ACTUAL_COMMIT="$(git -C "$TERMUX_REPO" rev-parse HEAD)"
[[ "$ACTUAL_COMMIT" == "$PINNED_TERMUX_COMMIT" ]] || {
  echo "Wrong termux-packages commit." >&2
  echo "expected=$PINNED_TERMUX_COMMIT" >&2
  echo "actual=$ACTUAL_COMMIT" >&2
  exit 1
}

[[ -z "$(git -C "$TERMUX_REPO" status --porcelain --untracked-files=no)" ]] || {
  echo "termux-packages checkout has tracked modifications; refusing reproducibility audit." >&2
  exit 1
}

python3 "$ROOT/scripts/audit-proot-sources.py"

rm -rf "$HOST_BUILD" "$EXTRACTED" "$QUARANTINE"
mkdir -p "$PACKAGES" "$EXTRACTED" "$QUARANTINE"

(
  cd "$TERMUX_REPO"
  ./scripts/run-docker.sh     ./build-package.sh     -a aarch64     --format debian     -o "$REL_BUILD/packages"     libandroid-shmem     libtalloc     proot
)

mapfile -t DEBS < <(find "$PACKAGES" -maxdepth 1 -type f -name '*.deb' -print | sort)
[[ ${#DEBS[@]} -gt 0 ]] || {
  echo "No .deb output found in $PACKAGES" >&2
  exit 1
}

for deb in "${DEBS[@]}"; do
  dpkg-deb -x "$deb" "$EXTRACTED"
done

find_one() {
  local pattern="$1"
  mapfile -t matches < <(find "$EXTRACTED" -path "$pattern" -print)
  [[ ${#matches[@]} -eq 1 ]] || {
    echo "Expected exactly one match for $pattern, found ${#matches[@]}" >&2
    printf '%s\n' "${matches[@]}" >&2
    return 1
  }
  printf '%s\n' "${matches[0]}"
}

PROOT_SRC="$(find_one '*/bin/proot')"
LOADER_SRC="$(find_one '*/libexec/proot/loader')"
SHMEM_SRC="$(find_one '*/lib/libandroid-shmem.so')"

mapfile -t TALLOC_LINKS < <(
  find "$EXTRACTED" -path '*/lib/libtalloc.so' -print | sort
)
[[ ${#TALLOC_LINKS[@]} -eq 1 ]] || {
  echo "Expected exactly one libtalloc.so development link, found ${#TALLOC_LINKS[@]}" >&2
  printf '%s\n' "${TALLOC_LINKS[@]}" >&2
  exit 1
}

TALLOC_REAL="$(readlink -f "${TALLOC_LINKS[0]}")"
[[ -f "$TALLOC_REAL" ]] || {
  echo "Resolved talloc library is not a file: $TALLOC_REAL" >&2
  exit 1
}

cp --dereference "$PROOT_SRC" "$QUARANTINE/proot"
cp --dereference "$LOADER_SRC" "$QUARANTINE/loader"
cp --dereference "$SHMEM_SRC" "$QUARANTINE/libandroid-shmem.so"
cp --dereference "$TALLOC_REAL" "$QUARANTINE/$(basename "$TALLOC_REAL")"

chmod 0700 "$QUARANTINE/proot" "$QUARANTINE/loader"
chmod 0600 "$QUARANTINE/libandroid-shmem.so" "$QUARANTINE/$(basename "$TALLOC_REAL")"

python3 "$ROOT/scripts/audit-proot-artifacts.py"   --artifact-dir "$QUARANTINE"   --report "$REPORT"

echo
echo "Quarantine build completed."
echo "Artifacts: $QUARANTINE"
echo "ELF report: $REPORT"
echo "Nothing was copied into app/src/main/jniLibs."
