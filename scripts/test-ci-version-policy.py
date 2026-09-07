#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "toolchains" / "android-build-lock.json"
ANDROID_CI = ROOT / ".github" / "workflows" / "android-ci.yml"
WINDOWS_CI = ROOT / ".github" / "workflows" / "windows-local-build-harness.yml"

STALE_VERSION_PATTERNS = (
    r"0[.]1[.]0-alpha(?:1[0-9]|20)\b",
    r"PocketPC-alpha(?:1[0-9]|20)\b",
    r"PocketPC-v0[.]1-alpha(?:1[0-9]|20)\b",
)


def main() -> int:
    failures: list[str] = []

    try:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        version_name = str(lock["app"]["versionName"])
        version_code = int(lock["app"]["versionCode"])
    except Exception as error:
        print(f"CI_VERSION_POLICY_FAILED\n- invalid build lock: {error}", file=sys.stderr)
        return 1

    files = {
        "android-ci": ANDROID_CI,
        "windows-ci": WINDOWS_CI,
    }

    for label, path in files.items():
        if not path.is_file():
            failures.append(f"{label} workflow missing: {path.relative_to(ROOT)}")
            continue

        text = path.read_text(encoding="utf-8")
        for pattern in STALE_VERSION_PATTERNS:
            if re.search(pattern, text):
                failures.append(
                    f"{label} contains stale pre-alpha21 version reference: {pattern}"
                )

    android_text = (
        ANDROID_CI.read_text(encoding="utf-8")
        if ANDROID_CI.is_file()
        else ""
    )
    windows_text = (
        WINDOWS_CI.read_text(encoding="utf-8")
        if WINDOWS_CI.is_file()
        else ""
    )

    required_android = (
        "toolchains/android-build-lock.json",
        "POCKETPC_VERSION_NAME",
        "POCKETPC_VERSION_CODE",
        "scripts/test-desktop-mode-policy.py",
        "scripts/test-update-feed-policy.py",
        "scripts/test-powershell51-compat.py",
        "scripts/test-device-evidence-bundle-verifier.py",
        ':app:testDebugUnitTest :app:lintDebug :app:assembleDebug',
    )
    for sentinel in required_android:
        if sentinel not in android_text:
            failures.append(f"android-ci missing sentinel: {sentinel}")

    required_windows = (
        "scripts/test-desktop-mode-policy.py",
        "scripts/test-update-feed-policy.py",
        "scripts/test-powershell51-compat.py",
        "scripts/test-device-evidence-bundle-verifier.py",
        "scripts/test-physical-validation-record-verifier.py",
        "scripts/test-first-physical-test-record-verifier.py",
        "scripts/build-local-windows.ps1",
    )
    for sentinel in required_windows:
        if sentinel not in windows_text:
            failures.append(f"windows-ci missing sentinel: {sentinel}")

    if f'versionName={version_name}' in android_text:
        failures.append(
            "android-ci hardcodes the current versionName instead of reading the lock"
        )
    if f'versionCode={version_code}' in android_text:
        failures.append(
            "android-ci hardcodes the current versionCode instead of reading the lock"
        )

    if failures:
        print("CI_VERSION_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("CI_VERSION_POLICY_OK")
    print(f"locked_version={version_name}")
    print(f"locked_version_code={version_code}")
    print("workflows_checked=2")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
