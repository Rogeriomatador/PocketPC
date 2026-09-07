#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "PocketPC Termux static repository checks"
echo "Classification : STATIC_SOURCE_VALIDATION_ONLY"
echo

if ! command -v python >/dev/null 2>&1; then
    echo "python=MISSING" >&2
    echo "Install Python in Termux before running this check." >&2
    exit 2
fi

CHECKS=(
    "scripts/verify-android-build-lock.py"
    "scripts/test-gitignore-policy.py"
    "scripts/test-proot-artifact-policy.py"
    "scripts/verify-proot-approval.py"
    "scripts/test-device-evidence-bundle-verifier.py"
    "scripts/test-local-build-record-verifier.py"
    "scripts/test-device-install-record-verifier.py"
    "scripts/test-device-chain-verifier.py"
    "scripts/test-physical-validation-record-verifier.py"
    "scripts/test-first-physical-test-record-verifier.py"
    "scripts/test-preflight-record-verifier.py"
    "scripts/test-failure-triage-verifier.py"
    "scripts/test-powershell51-compat.py"
    "scripts/test-desktop-mode-policy.py"
    "scripts/test-desktop-enum-coverage.py"
    "scripts/test-ci-version-policy.py"
    "scripts/test-update-feed-policy.py"
    "scripts/test-pocketdrive-research-policy.py"
)

echo "Python syntax"
python -m compileall -q scripts
echo "PYTHON_COMPILEALL_OK"
echo

PASS=0
for check in "${CHECKS[@]}"; do
    echo "==> $check"
    python "$check"
    PASS=$((PASS + 1))
    echo
done

echo "PocketPC Termux static checks complete."
echo "checks_passed=$PASS"
echo "Classification : TERMUX_STATIC_POLICY_PASS"
echo
echo "Important:"
echo "  This does not compile Kotlin, run Android Lint, build an APK,"
echo "  install PocketPC, run a device test, or validate physical behavior."
