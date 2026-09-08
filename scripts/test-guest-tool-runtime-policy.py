#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

CHECKS = {
    "scripts/build-box64-aarch64.py": (
        '"guestRoot": "/opt/pocketpc/box64"',
        '"entrypoint": "bin/box64"',
        '"sourceCommit": actual_commit',
        'guest-package.zip',
        'ZipInfo(relative, (1980, 1, 1, 0, 0, 0))',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestToolTrustPolicy.kt": (
        'version = "0.4.4"',
        '2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a',
        'version = "11.0"',
        'db11d0fe6a169c457e23d007e20404643d067aa8',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestToolPackageVerifier.kt": (
        "GuestToolTrustPolicy.errors(manifest)",
        "GUEST_TOOL_SYMLINK_FORBIDDEN",
        "GUEST_TOOL_UNEXPECTED_FILE",
        "GUEST_TOOL_FILE_SHA256_MISMATCH",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestToolPackageManager.kt": (
        "GUEST_TOOL_ZIP_PATH_INVALID",
        "GUEST_TOOL_ZIP_DUPLICATE",
        "GUEST_TOOL_ZIP_FILE_TOO_LARGE",
        "GUEST_TOOL_ZIP_TOTAL_TOO_LARGE",
        "GuestToolPackageVerifier.verify",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestToolInstallManager.kt": (
        "GuestToolPackageVerifier.verify",
        "GUEST_TOOL_COPIED_PACKAGE_INVALID",
        ".tmp-tool-",
        ".backup-tool-",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestToolOverlayPlanner.kt": (
        "GuestToolPackageVerifier.verify",
        "GUEST_TOOL_ATTESTATION_FAILED",
        "BindAuthority.SYSTEM",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/GuestRuntimeProbe.kt": (
        "POCKETPC_TOOLCHAIN_PROBE_V3",
        "/opt/pocketpc/box64/bin/box64",
        "/opt/pocketpc/wine/bin/wine",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/RuntimeApp.kt": (
        "guestToolPackages.stageZip",
        "guestToolInstaller.install",
        "GuestToolOverlayPlanner.plan",
        "TOOL_STAGED_VERIFIED",
        "TOOL_INSTALLED_ATTESTED",
    ),
}


def main() -> int:
    failures: list[str] = []

    for relative, sentinels in CHECKS.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing file: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        for sentinel in sentinels:
            if sentinel not in text:
                failures.append(f"{relative}: missing sentinel {sentinel!r}")

    box64_build = (ROOT / "scripts/build-box64-aarch64.py").read_text(encoding="utf-8")
    if "run([\n        [" in box64_build:
        failures.append("Box64 build contains nested run argv")
    for sentinel in (
        "POCKETPC_BOX64_SMOKE_OK",
        '"executionMode": "native-aarch64"',
        "guest-package.zip",
    ):
        if sentinel not in box64_build:
            failures.append("Box64 build missing sentinel: " + sentinel)

    wine_build = (ROOT / "scripts/build-wine-x86_64.py").read_text(encoding="utf-8")
    for sentinel in (
        "POCKETPC_WIN64_SMOKE_OK",
        "POCKETPC_WIN_PROCESS_IPC_SMOKE_OK",
        "POCKETPC_WINSOCK_SMOKE_OK",
        "POCKETPC_WINMM_AUDIO_API_OK",
        "POCKETPC_RAW_INPUT_API_OK",
        "POCKETPC_D3D11_SMOKE_OK",
        "POCKETPC_D3D11_PRESENT_SMOKE_OK",
        '"executionMode": "box64-x86_64"',
        '"architecture": "x86_64"',
        "guest-package.zip",
    ):
        if sentinel not in wine_build:
            failures.append("Wine build missing sentinel: " + sentinel)

    android_driver = ROOT / "third_party/wine/ANDROID_DRIVER_REUSE.json"
    if not android_driver.is_file():
        failures.append("missing Wine Android driver reuse audit")
    else:
        android_text = android_driver.read_text(encoding="utf-8")
        for sentinel in (
            '"status": "UPSTREAM_PRESENT_DIRECT_REUSE_BLOCKED_BROKER_REQUIRED"',
            '"wineSourceCommit": "db11d0fe6a169c457e23d007e20404643d067aa8"',
            '"guestWineArchitecture": "x86_64"',
            '"hostArchitecture": "arm64-v8a"',
            '"protocolImplemented": false',
        ):
            if sentinel not in android_text:
                failures.append("Wine Android reuse audit missing sentinel: " + sentinel)

    approval = ROOT / "app/src/main/assets/proot-substrate-approval.json"
    if approval.is_file():
        text = approval.read_text(encoding="utf-8")
        if '"approved": false' not in text:
            failures.append("PRoot approval must remain false while device audit is pending")

    if failures:
        print("GUEST_TOOL_RUNTIME_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("GUEST_TOOL_RUNTIME_POLICY_OK")
    print("guest_tool_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
