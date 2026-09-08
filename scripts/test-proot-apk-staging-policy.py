#!/usr/bin/env python3
from __future__ import annotations
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/stage-proot-apk-candidate.py"
APPROVAL = ROOT / "app/src/main/assets/proot-substrate-approval.json"


def main() -> int:
    failures = []
    text = SCRIPT.read_text(encoding="utf-8")
    approval = APPROVAL.read_text(encoding="utf-8")
    required = [
        "STAGING_REFUSED_INSIDE_REPOSITORY",
        "STAGING_REFUSED_AUDIT_STATUS",
        "STAGING_REFUSED_ANDROID_PACKAGING_BLOCKERS",
        "STAGING_REFUSED_ROLE_SET",
        "STAGING_REFUSED_SHA256",
        "APK_STAGING_CANDIDATE_NOT_APPROVED_NOT_DEVICE_TESTED",
        "approvalChanged\": False",
        "repositoryModifiedByStaging\": False",
        '"libproot.so"',
        '"libproot_loader.so"',
        '"libandroid-shmem"',
        '"libtalloc.so"',
    ]
    for sentinel in required:
        if sentinel not in text:
            failures.append("missing staging sentinel: " + sentinel)
    if '"approved": false' not in approval:
        failures.append("embedded approval must remain false")
    if '"status": "NOT_APPROVED"' not in approval:
        failures.append("embedded approval status must remain NOT_APPROVED")
    if failures:
        print("PROOT_APK_STAGING_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1
    print("PROOT_APK_STAGING_POLICY_OK")
    print("embedded_approval=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
