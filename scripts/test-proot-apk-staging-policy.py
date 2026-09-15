#!/usr/bin/env python3
from __future__ import annotations
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/stage-proot-apk-candidate.py"
APPROVAL = ROOT / "app/src/main/assets/proot-substrate-approval.json"


def main() -> int:
    failures = []
    text = SCRIPT.read_text(encoding="utf-8")
    approval_text = APPROVAL.read_text(encoding="utf-8")
    required = [
        "STAGING_REFUSED_INSIDE_REPOSITORY",
        "STAGING_REFUSED_AUDIT_STATUS",
        "STAGING_REFUSED_ANDROID_PACKAGING_BLOCKERS",
        "STAGING_REFUSED_PACKAGING_ALIASES",
        "STAGING_REFUSED_ROLE_SET",
        "STAGING_REFUSED_ARTIFACT",
        "STAGING_REFUSED_SIZE",
        "STAGING_REFUSED_SHA256",
        "STAGING_REFUSED_COPY_DIGEST",
        "APK_STAGING_CANDIDATE_NOT_APPROVED_NOT_DEVICE_TESTED",
        "approvalChanged\": False",
        "repositoryModifiedByStaging\": False",
        '"APK packaging"',
        '"Android install"',
        '"PRoot execution"',
        '"guest shell"',
        '"Box64"',
        '"Wine"',
        '"Roblox"',
        '"libproot.so"',
        '"libproot_loader.so"',
        '"libandroid-shmem.so"',
        '"libtalloc.so"',
    ]
    for sentinel in required:
        if sentinel not in text:
            failures.append("missing staging sentinel: " + sentinel)

    try:
        approval = json.loads(approval_text)
    except Exception as error:
        failures.append("embedded approval JSON invalid: " + str(error))
        approval = {}

    if approval.get("approved") is not False:
        failures.append("embedded approval must remain false")
    if approval.get("status") != "NOT_APPROVED":
        failures.append("embedded approval status must remain NOT_APPROVED")

    forbidden_write_targets = (
        "app/src/main/assets/proot-substrate-approval.json",
        "app/src/main/jniLibs",
        "app/src/main/resources",
    )
    for target in forbidden_write_targets:
        if target in text:
            failures.append("candidate staging must not write into app tree: " + target)

    if "repo in out.parents" not in text or "out == repo" not in text:
        failures.append("staging output must fail closed anywhere inside repository")
    if "out.mkdir(parents=True, exist_ok=False)" not in text:
        failures.append("staging output must refuse pre-existing destination")
    if "if not raw.is_file() or raw.is_symlink()" not in text:
        failures.append("staging source must reject symlink artifacts")

    if failures:
        print("PROOT_APK_STAGING_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1
    print("PROOT_APK_STAGING_POLICY_OK")
    print("embedded_approval=false")
    print("candidate_repository_writes=false")
    print("candidate_device_execution=NOT_EXECUTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
