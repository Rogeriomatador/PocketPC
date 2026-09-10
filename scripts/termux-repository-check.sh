#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
APT_ETC="$PREFIX_DIR/etc/apt"
MODE="${1:-diagnostic}"

collect_sources() {
    local files=()
    [ -f "$APT_ETC/sources.list" ] && files+=("$APT_ETC/sources.list")
    if [ -d "$APT_ETC/sources.list.d" ]; then
        while IFS= read -r file; do
            files+=("$file")
        done < <(find "$APT_ETC/sources.list.d" -maxdepth 1 -type f -name '*.list' -print | sort)
    fi

    local file
    for file in "${files[@]}"; do
        awk '
            /^[[:space:]]*#/ {next}
            /^[[:space:]]*deb[[:space:]]/ {print}
        ' "$file"
    done
}

SOURCES="$(collect_sources || true)"
echo "PocketPC Termux repository check"
echo "Classification : TERMUX_REPOSITORY_DIAGNOSTIC"

if [ -z "$SOURCES" ]; then
    echo "repository_state=UNKNOWN_NO_ACTIVE_DEB_SOURCE"
    [ "$MODE" = "--require-modern" ] && exit 14
    exit 0
fi

printf '%s\n' "$SOURCES" | sed 's/^/  source=/'

if printf '%s\n' "$SOURCES" | grep -Eq 'https?://([^/]*[.])?termux[.]net([/[:space:]]|$)'; then
    echo "repository_state=LEGACY_TERMUX_NET"
    echo "recommended_main_repo=deb https://packages.termux.dev/apt/termux-main stable main"
    echo "action_required=CHANGE_TERMUX_MAIN_REPOSITORY"
    echo "note=pkg_update_does_not_change_repository"
    if command -v termux-change-repo >/dev/null 2>&1; then
        echo "recovery_command=termux-change-repo"
        echo "recovery_hint=Select Main repository, then choose the packages.termux.dev primary mirror."
    else
        echo "recovery_command=apt edit-sources"
        echo "recovery_hint=Replace the legacy main source with the recommended_main_repo line above."
    fi
    echo "Classification : TERMUX_REPOSITORY_LEGACY"
    [ "$MODE" = "--require-modern" ] && exit 13
    exit 0
fi

if printf '%s\n' "$SOURCES" | grep -Fq 'packages.termux.dev/apt/termux-main'; then
    echo "repository_state=OFFICIAL_PRIMARY"
    echo "Classification : TERMUX_REPOSITORY_PRIMARY_OK"
    exit 0
fi

echo "repository_state=NON_PRIMARY_MIRROR_OR_CUSTOM"
echo "note=Termux supports multiple mirrors; compatibility is determined by the AAPT2 probe."
echo "Classification : TERMUX_REPOSITORY_NON_PRIMARY"
exit 0
