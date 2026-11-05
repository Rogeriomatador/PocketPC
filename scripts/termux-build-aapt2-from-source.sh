#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "PocketPC AAPT2 source-build compatibility alias"
echo "canonical_script=scripts/termux-build-modern-aapt2.sh"
echo

exec bash "$ROOT/scripts/termux-build-modern-aapt2.sh" "$@"
