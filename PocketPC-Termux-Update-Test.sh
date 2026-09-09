#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BRANCH="improve/alpha22-desktop-continuity"

cd "$ROOT"

echo "PocketPC - Termux update + validation"
echo "Classification : SOFTWARE_TEST_ATTEMPT"
echo

if ! command -v git >/dev/null 2>&1; then
    echo "MISSING_TOOL=git" >&2
    exit 2
fi

if ! git diff --quiet --ignore-submodules -- ||
   ! git diff --cached --quiet --ignore-submodules --; then
    echo "WORKTREE_DIRTY_REFUSING_UPDATE" >&2
    echo "Classification : TERMUX_UPDATE_BLOCKED_DIRTY_TREE"
    exit 3
fi

git fetch origin "$BRANCH"

if git show-ref --verify --quiet "refs/heads/$BRANCH"; then
    git switch "$BRANCH"
else
    git switch --track -c "$BRANCH" "origin/$BRANCH"
fi

git merge --ff-only "origin/$BRANCH"

REVISION="$(git rev-parse HEAD)"
echo "updated_revision=$REVISION"
echo

bash scripts/termux-static-check.sh
echo

bash scripts/termux-on-device-preflight.sh
echo

LICENSE_FILE="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}/licenses/android-sdk-license"
if [ ! -s "$LICENSE_FILE" ]; then
    echo "Android SDK license is not registered for this Termux SDK."
    echo "Starting the official sdkmanager license flow..."
    echo
    bash scripts/termux-bootstrap-sdkmanager-licenses.sh
    echo
fi

bash scripts/termux-kotlin-unit-test.sh

echo
echo "Classification : TERMUX_UPDATE_AND_KOTLIN_TEST_PASS"
echo "validated_revision=$REVISION"
echo
echo "Important:"
echo "  This wrapper does not assemble, sign, publish, or install an APK."
echo "  PASS here proves only the repository policies plus the Termux Kotlin/unit-test gate."
