#!/usr/bin/env python3
"""Stage reviewed PRoot quarantine artifacts into Android JNI aliases.

This is a review bridge only. It refuses to touch app/src and never changes
proot-substrate-approval.json. The resulting directory may be inspected or
used by a later explicit packaging step after all approval gates are met.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "third_party/proot/ARTIFACT_CONTRACT.json"
SOURCE_LOCK = ROOT / "third_party/proot/LOCK.json"

EXPECTED_PACKAGING_ALIASES = {
    "proot": "libproot.so",
    "loader64": "libproot_loader.so",
    "libandroid-shmem": "libandroid-shmem.so",
    "libtalloc": "libtalloc.so",
}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--artifact-dir", type=Path, required=True)
    p.add_argument("--audit", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    args = p.parse_args()

    source = args.artifact_dir.resolve()
    out = args.out.resolve()
    repo = ROOT.resolve()
    if out == repo or repo in out.parents:
        raise SystemExit("STAGING_REFUSED_INSIDE_REPOSITORY")
    out.mkdir(parents=True, exist_ok=False)

    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    if audit.get("status") not in {
        "ELF_VALID_REVIEW_REQUIRED",
        "ELF_VALID_DEPENDENCIES_REVIEW_REQUIRED",
    }:
        raise SystemExit("STAGING_REFUSED_AUDIT_STATUS:" + str(audit.get("status")))
    if audit.get("androidPackagingBlockers"):
        raise SystemExit("STAGING_REFUSED_ANDROID_PACKAGING_BLOCKERS")

    aliases = {
        item["id"]: item["futurePackagingAlias"]
        for item in contract["requiredRoles"]
    }
    aliases["libtalloc"] = "libtalloc.so"
    if aliases != EXPECTED_PACKAGING_ALIASES:
        raise SystemExit("STAGING_REFUSED_PACKAGING_ALIASES")
    required = set(EXPECTED_PACKAGING_ALIASES)
    artifacts = audit.get("artifacts") or {}
    if set(artifacts) != required:
        raise SystemExit("STAGING_REFUSED_ROLE_SET")

    staged = []
    for role in sorted(required):
        record = artifacts[role]
        raw = source / record["fileName"]
        if not raw.is_file() or raw.is_symlink():
            raise SystemExit("STAGING_REFUSED_ARTIFACT:" + role)
        if raw.stat().st_size != int(record["bytes"]):
            raise SystemExit("STAGING_REFUSED_SIZE:" + role)
        actual = sha256(raw)
        if actual != record["sha256"]:
            raise SystemExit("STAGING_REFUSED_SHA256:" + role)
        target = out / aliases[role]
        shutil.copyfile(raw, target)
        if sha256(target) != actual:
            raise SystemExit("STAGING_REFUSED_COPY_DIGEST:" + role)
        staged.append({
            "role": role,
            "sourceFileName": raw.name,
            "packagingAlias": target.name,
            "bytes": target.stat().st_size,
            "sha256": actual,
            "executableRequired": role in {"proot", "loader64"},
        })

    evidence = {
        "schemaVersion": 1,
        "status": "APK_STAGING_CANDIDATE_NOT_APPROVED_NOT_DEVICE_TESTED",
        "sourceLockSha256": sha256(SOURCE_LOCK),
        "artifactContractSha256": sha256(CONTRACT),
        "auditStatus": audit["status"],
        "artifacts": staged,
        "approvalChanged": False,
        "repositoryModifiedByStaging": False,
        "notExecuted": ["APK packaging", "Android install", "PRoot execution", "guest shell", "Box64", "Wine", "Roblox"],
    }
    (out / "STAGING_EVIDENCE.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("PROOT_APK_STAGING_CANDIDATE_READY_NOT_APPROVED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
