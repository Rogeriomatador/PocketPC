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

FETCH_OK=false
for attempt in 1 2 3; do
    if git fetch origin "$BRANCH"; then
        FETCH_OK=true
        break
    fi
    echo "git_fetch_attempt=$attempt failed"
    if [ "$attempt" -lt 3 ]; then
        sleep $((attempt * 2))
    fi
done

if [ "$FETCH_OK" != "true" ]; then
    echo "Classification : TERMUX_UPDATE_NETWORK_FAILED" >&2
    echo "Unable to fetch $BRANCH after retries; refusing to validate stale source." >&2
    exit 4
fi

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

echo "Checking Termux package repository before toolchain validation..."
bash scripts/termux-repository-check.sh --require-modern
echo

bash scripts/termux-on-device-preflight.sh
echo

echo "Checking Android SDK licenses with sdkmanager..."
echo "This step is interactive only when there are pending licenses."
echo
bash scripts/termux-bootstrap-sdkmanager-licenses.sh
echo

bash scripts/termux-kotlin-unit-test.sh

echo
echo "Classification : TERMUX_UPDATE_AND_KOTLIN_TEST_PASS"
echo "validated_revision=$REVISION"
echo
echo "Important:"
echo "  This wrapper does not assemble, sign, publish, or install an APK."
echo "  PASS here proves only the repository policies plus the Termux Kotlin/unit-test gate."
