#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

MODE="${1:-diagnostic}"

detect_variant() {
    local version="${TERMUX_VERSION:-}"
    if [[ "$version" == googleplay.* ]]; then
        printf '%s\n' "googleplay"
        return
    fi

    if command -v termux-info >/dev/null 2>&1; then
        local info
        info="$(termux-info 2>/dev/null || true)"
        if printf '%s\n' "$info" | grep -Eqi 'TERMUX_VERSION=googleplay[.]|Application version:[[:space:]]*googleplay[.]'; then
            printf '%s\n' "googleplay"
            return
        fi
    fi

    if command -v dpkg-query >/dev/null 2>&1; then
        local tools_version
        tools_version="$(dpkg-query -W -f='${Version}' termux-tools 2>/dev/null || true)"
        case "$tools_version" in
            3.*)
                printf '%s\n' "googleplay"
                return
                ;;
        esac
    fi

    printf '%s\n' "classic_or_unknown"
}

VARIANT="$(detect_variant)"

if [ "$MODE" = "--value" ]; then
    printf '%s\n' "$VARIANT"
    exit 0
fi

echo "PocketPC Termux variant"
echo "termux_variant=$VARIANT"
if [ -n "${TERMUX_VERSION:-}" ]; then
    echo "termux_version=${TERMUX_VERSION}"
fi

case "$VARIANT" in
    googleplay)
        echo "Classification : TERMUX_VARIANT_GOOGLE_PLAY"
        ;;
    *)
        echo "Classification : TERMUX_VARIANT_CLASSIC_OR_UNKNOWN"
        ;;
esac
