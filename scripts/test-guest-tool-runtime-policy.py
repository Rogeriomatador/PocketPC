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
        "POCKETPC_TOOLCHAIN_PROBE_V2",
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
