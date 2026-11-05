#!/usr/bin/env python3
"""Build an external PRoot device-validation bundle without production approval."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/proot/LOCK.json"
CONTRACT = ROOT / "third_party/proot/ARTIFACT_CONTRACT.json"
BUILD_RECIPE = ROOT / "third_party/proot/NDK_BUILD.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def copy_plain(source: Path, destination: Path) -> None:
    if not source.is_file() or source.is_symlink():
        raise SystemExit("VALIDATION_BUNDLE_SOURCE_INVALID:" + str(source))
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    if sha256(source) != sha256(destination):
        raise SystemExit("VALIDATION_BUNDLE_COPY_DIGEST_MISMATCH:" + source.name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--staged-dir", type=Path, required=True)
    parser.add_argument("--license-audit", type=Path, required=True)
    parser.add_argument("--source-cache", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    staged = args.staged_dir.resolve()
    source_cache = args.source_cache.resolve()
    out = args.out.resolve()
    repo = ROOT.resolve()
    if out == repo or repo in out.parents:
        raise SystemExit("VALIDATION_BUNDLE_REFUSED_INSIDE_REPOSITORY")
    out.mkdir(parents=True, exist_ok=False)

    staging_evidence_path = staged / "STAGING_EVIDENCE.json"
    if not staging_evidence_path.is_file():
        raise SystemExit("VALIDATION_BUNDLE_STAGING_EVIDENCE_MISSING")
    staging = json.loads(staging_evidence_path.read_text(encoding="utf-8"))
    if staging.get("status") != "APK_STAGING_CANDIDATE_NOT_APPROVED_NOT_DEVICE_TESTED":
        raise SystemExit("VALIDATION_BUNDLE_STAGING_STATUS_INVALID")
    if staging.get("approvalChanged") is not False:
        raise SystemExit("VALIDATION_BUNDLE_APPROVAL_MUTATION_REFUSED")

    license_audit = json.loads(args.license_audit.read_text(encoding="utf-8"))
    if license_audit.get("status") != "SOURCE_LICENSE_AUDIT_PASS":
        raise SystemExit("VALIDATION_BUNDLE_LICENSE_AUDIT_REQUIRED")
    if license_audit.get("sourceLockSha256") != sha256(LOCK):
        raise SystemExit("VALIDATION_BUNDLE_LICENSE_LOCK_MISMATCH")

    if staging.get("artifactContractSha256") != sha256(CONTRACT):
        raise SystemExit("VALIDATION_BUNDLE_CONTRACT_MISMATCH")
    if staging.get("sourceLockSha256") != sha256(LOCK):
        raise SystemExit("VALIDATION_BUNDLE_SOURCE_LOCK_MISMATCH")

    expected_roles = {
        "proot": "libproot.so",
        "loader64": "libproot_loader.so",
        "libandroid-shmem": "libandroid-shmem.so",
        "libtalloc": "libtalloc.so",
    }
    records = staging.get("artifacts") or []
    by_role = {item["role"]: item for item in records}
    if set(by_role) != set(expected_roles):
        raise SystemExit("VALIDATION_BUNDLE_ROLE_SET_INVALID")

    jni = out / "jniLibs/arm64-v8a"
    artifacts = []
    for role, alias in expected_roles.items():
        item = by_role[role]
        if item.get("packagingAlias") != alias:
            raise SystemExit("VALIDATION_BUNDLE_ALIAS_MISMATCH:" + role)
        source = staged / alias
        if source.stat().st_size != int(item["bytes"]):
            raise SystemExit("VALIDATION_BUNDLE_SIZE_MISMATCH:" + role)
        if sha256(source) != item["sha256"]:
            raise SystemExit("VALIDATION_BUNDLE_SHA_MISMATCH:" + role)
        destination = jni / alias
        copy_plain(source, destination)
        artifacts.append({
            "role": role,
            "fileName": alias,
            "bytes": destination.stat().st_size,
            "sha256": sha256(destination),
            "executableRequired": role in {"proot", "loader64"},
        })

    assets = out / "assets"
    manifest = {
        "schemaVersion": 1,
        "status": "DEVICE_VALIDATION_CANDIDATE",
        "productionApproved": False,
        "sourceLockSha256": sha256(LOCK),
        "artifactContractSha256": sha256(CONTRACT),
        "artifacts": artifacts,
        "review": {
            "sourceAudit": True,
            "elfAudit": True,
            "licenseAudit": True,
            "packagingAudit": True,
            "deviceAudit": False,
        },
        "allowedExecution": [
            "SHELL",
            "ROOTFS",
        ],
        "notApprovedFor": [
            "production runtime",
            "automatic application launch",
            "Roblox",
        ],
    }
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "proot-device-validation.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    source_assets = assets / "proot-validation-sources"
    for component in ("proot", "libandroid-shmem", "libtalloc"):
        archive = source_cache / (component + ".source")
        copy_plain(archive, source_assets / archive.name)

    for policy in (LOCK, CONTRACT, BUILD_RECIPE):
        copy_plain(policy, source_assets / "policy" / policy.name)
    copy_plain(
        ROOT / "scripts/build-proot-ndk.py",
        source_assets / "policy/build-proot-ndk.py",
    )
    patches = ROOT / "third_party/proot/patches"
    if patches.is_dir():
        for patch in sorted(patches.iterdir()):
            if patch.is_file():
                copy_plain(patch, source_assets / "patches" / patch.name)

    copy_plain(
        args.license_audit.resolve(),
        source_assets / "LICENSE_AUDIT.json",
    )
    copy_plain(
        staging_evidence_path,
        source_assets / "STAGING_EVIDENCE.json",
    )

    evidence = {
        "schemaVersion": 1,
        "status": "DEVICE_VALIDATION_BUNDLE_READY_NOT_PRODUCTION_APPROVED",
        "manifestSha256": sha256(assets / "proot-device-validation.json"),
        "artifacts": artifacts,
        "productionApprovalChanged": False,
        "deviceExecution": "NOT_EXECUTED",
    }
    (out / "DEVICE_VALIDATION_BUNDLE_EVIDENCE.json").write_text(
        json.dumps(evidence, indent=2) + "\n",
        encoding="utf-8",
    )
    print("PROOT_DEVICE_VALIDATION_BUNDLE_READY_NOT_APPROVED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
