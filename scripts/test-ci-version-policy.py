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
HOME_OTA = ROOT / ".github" / "workflows" / "publish-home-test-update.yml"

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
        "home-ota": HOME_OTA,
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
    home_ota_text = (
        HOME_OTA.read_text(encoding="utf-8")
        if HOME_OTA.is_file()
        else ""
    )

    required_android = (
        "toolchains/android-build-lock.json",
        "POCKETPC_VERSION_NAME",
        "POCKETPC_VERSION_CODE",
        "scripts/test-desktop-mode-policy.py",
        "scripts/test-update-feed-policy.py",
        "scripts/test-pocketdrive-research-policy.py",
        "scripts/test-kotlin-source-regressions.py",
        "scripts/test-termux-aapt2-policy.py",
        "steps.build_lock.outputs.jdk",
        "steps.build_lock.outputs.gradle",
        "steps.build_lock.outputs.platform",
        "steps.build_lock.outputs.build_tools",
        "steps.build_lock.outputs.ndk",
        "steps.build_lock.outputs.cmake",
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
        "scripts/test-pocketdrive-research-policy.py",
        "scripts/test-powershell51-compat.py",
        "scripts/test-device-evidence-bundle-verifier.py",
        "scripts/test-physical-validation-record-verifier.py",
        "scripts/test-first-physical-test-record-verifier.py",
        "scripts/test-kotlin-source-regressions.py",
        "scripts/test-termux-aapt2-policy.py",
        "scripts/build-local-windows.ps1",
        "steps.build_lock.outputs.jdk",
    )
    for sentinel in required_windows:
        if sentinel not in windows_text:
            failures.append(f"windows-ci missing sentinel: {sentinel}")

    required_home_ota = (
        "toolchains/android-build-lock.json",
        "id: android_lock",
        "steps.android_lock.outputs.jdk",
        "steps.android_lock.outputs.gradle",
        "steps.android_lock.outputs.platform",
        "steps.android_lock.outputs.build_tools",
        "steps.android_lock.outputs.ndk",
        "steps.android_lock.outputs.cmake",
        "scripts/verify-android-build-lock.py",
        "scripts/test-kotlin-source-regressions.py",
        "scripts/test-termux-aapt2-policy.py",
        '"git", "rev-list", "--count", "HEAD"',
        "0.1.0-alpha22.home.",
    )
    for sentinel in required_home_ota:
        if sentinel not in home_ota_text:
            failures.append(f"home-ota missing sentinel: {sentinel}")

    if "GITHUB_RUN_NUMBER" in home_ota_text:
        failures.append(
            "home-ota must not derive Android versionCode from workflow run number; "
            "use git commit count so local and GitHub OTA builds share one monotonic sequence"
        )

    hardcoded_toolchain_patterns = (
        r'platforms;android-[0-9]+(?:[.][0-9]+)?',
        r'build-tools;[0-9]+[.][0-9]+[.][0-9]+',
        r'ndk;[0-9]+[.][0-9]+[.][0-9]+',
        r'cmake;[0-9]+[.][0-9]+[.][0-9]+',
        r"gradle-version:\s*['\"]?[0-9]+[.][0-9]+[.][0-9]+",
        r"java-version:\s*['\"]?[0-9]+",
    )
    for label, text in (
        ("android-ci", android_text),
        ("home-ota", home_ota_text),
    ):
        for pattern in hardcoded_toolchain_patterns:
            if re.search(pattern, text):
                failures.append(
                    f"{label} hardcodes build toolchain instead of reading the lock: {pattern}"
                )

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
    print("workflows_checked=3")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
