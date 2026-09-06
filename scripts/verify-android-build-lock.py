#!/usr/bin/env python3
"""Verify PocketPC Android build-toolchain lock against repository config."""

from __future__ import annotations

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "toolchains" / "android-build-lock.json"
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def require_contains(
    failures: list[str],
    text: str,
    needle: str,
    label: str,
) -> None:
    if needle not in text:
        failures.append(f"{label} missing expected text: {needle}")


def main() -> int:
    failures: list[str] = []

    try:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
    except Exception as error:
        print(f"ANDROID_BUILD_LOCK_FAILED\n- invalid lock: {error}", file=sys.stderr)
        return 1

    if lock.get("schemaVersion") != 1:
        failures.append("unsupported lock schemaVersion")
    if lock.get("status") != "PINNED":
        failures.append("lock status must be PINNED")

    app = lock.get("app", {})
    gradle = lock.get("gradle", {})
    plugins = lock.get("plugins", {})
    jdk = lock.get("jdk", {})
    android = lock.get("android", {})
    evidence = lock.get("evidence", {})

    gradle_version = str(gradle.get("version", ""))
    gradle_sha = str(gradle.get("distributionSha256", "")).lower()
    gradle_url = str(gradle.get("distributionUrl", ""))

    if not re.fullmatch(r"\d+[.]\d+[.]\d+", gradle_version):
        failures.append("gradle.version is invalid")
    if not HEX64.fullmatch(gradle_sha):
        failures.append("gradle distribution SHA-256 is invalid")
    if not gradle_url.endswith(f"gradle-{gradle_version}-bin.zip"):
        failures.append("gradle distribution URL/version mismatch")

    if jdk.get("major") != 17:
        failures.append("JDK major must remain 17 for AGP 9.4 gate")

    compile_sdk = android.get("compileSdk")
    build_tools = str(android.get("buildTools", ""))
    ndk = str(android.get("ndk", ""))
    cmake = str(android.get("cmake", ""))

    expected_components = {
        "platform-tools",
        f"platforms;android-{compile_sdk}",
        f"build-tools;{build_tools}",
        f"ndk;{ndk}",
        f"cmake;{cmake}",
    }
    actual_components = set(android.get("requiredComponents", []))
    if actual_components != expected_components:
        failures.append(
            "android.requiredComponents differs from derived toolchain set"
        )

    if evidence.get("sourceRevisionEnvironment") != "POCKETPC_SOURCE_REVISION":
        failures.append("source revision environment key changed unexpectedly")
    if evidence.get("dirtyTreeEmbeddedRevision") != "LOCAL_UNPINNED":
        failures.append("dirty tree revision marker must be LOCAL_UNPINNED")

    root_gradle = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    app_gradle = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    android_ci = (
        ROOT / ".github" / "workflows" / "android-ci.yml"
    ).read_text(encoding="utf-8")
    builder = (
        ROOT / "scripts" / "build-local-windows.ps1"
    ).read_text(encoding="utf-8")

    require_contains(
        failures,
        root_gradle,
        f'id("com.android.application") version "{plugins.get("androidGradlePlugin")}"',
        "root build.gradle.kts",
    )
    require_contains(
        failures,
        root_gradle,
        f'id("org.jetbrains.kotlin.plugin.compose") version "{plugins.get("kotlinComposePlugin")}"',
        "root build.gradle.kts",
    )

    require_contains(
        failures,
        app_gradle,
        f"compileSdk = {compile_sdk}",
        "app/build.gradle.kts",
    )
    require_contains(
        failures,
        app_gradle,
        f'ndkVersion = "{ndk}"',
        "app/build.gradle.kts",
    )
    require_contains(
        failures,
        app_gradle,
        f'versionName = "{app.get("versionName")}"',
        "app/build.gradle.kts",
    )
    require_contains(
        failures,
        app_gradle,
        f'versionCode = {app.get("versionCode")}',
        "app/build.gradle.kts",
    )
    require_contains(
        failures,
        app_gradle,
        f'version = "{cmake}"',
        "app/build.gradle.kts",
    )

    require_contains(
        failures,
        android_ci,
        f'gradle-version: '{gradle_version}'',
        "android-ci.yml",
    )
    require_contains(
        failures,
        android_ci,
        f'"platforms;android-{compile_sdk}"',
        "android-ci.yml",
    )
    require_contains(
        failures,
        android_ci,
        f'"build-tools;{build_tools}"',
        "android-ci.yml",
    )
    require_contains(
        failures,
        android_ci,
        f'"ndk;{ndk}"',
        "android-ci.yml",
    )
    require_contains(
        failures,
        android_ci,
        f'"cmake;{cmake}"',
        "android-ci.yml",
    )

    require_contains(
        failures,
        builder,
        'toolchains\\android-build-lock.json',
        "build-local-windows.ps1",
    )
    if re.search(r'\$GradleVersion\s*=\s*"\d', builder):
        failures.append(
            "build-local-windows.ps1 hardcodes Gradle version instead of lock"
        )

    if failures:
        print("ANDROID_BUILD_LOCK_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("ANDROID_BUILD_LOCK_OK")
    print(f"app_version={app.get('versionName')}")
    print(f"gradle={gradle_version}")
    print(f"gradle_sha256={gradle_sha}")
    print(f"compile_sdk={compile_sdk}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
