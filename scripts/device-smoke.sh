#!/usr/bin/env bash
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="dev.pocketpc.core"
ACTIVITY=".MainActivity"

command -v adb >/dev/null 2>&1 || { echo "adb não encontrado" >&2; exit 2; }
[[ -f "$APK" ]] || { echo "APK não encontrado: $APK" >&2; exit 2; }

adb install -r "$APK"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/$ACTIVITY"
echo "PocketPC iniciado. Continue os gates manuais em docs/DEVICE_TEST.md."
