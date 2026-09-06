#!/usr/bin/env python3
"""Fail-closed validation for PocketPC's packaged PRoot approval manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
HEX64 = re.compile(r"^[0-9a-f]{64}$")
ROLES = {"proot", "loader64", "libandroid-shmem", "libtalloc"}


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--approval",
        type=pathlib.Path,
        default=ROOT / "app/src/main/assets/proot-substrate-approval.json",
    )
    parser.add_argument(
        "--source-lock",
        type=pathlib.Path,
        default=ROOT / "third_party/proot/LOCK.json",
    )
    parser.add_argument(
        "--artifact-contract",
        type=pathlib.Path,
        default=ROOT / "third_party/proot/ARTIFACT_CONTRACT.json",
    )
    parser.add_argument(
        "--artifact-lock",
        type=pathlib.Path,
        default=ROOT / "third_party/proot/ARTIFACTS.lock.json",
    )
    args = parser.parse_args()

    approval = load(args.approval)
    failures: list[str] = []

    if approval.get("schemaVersion") != 1:
        failures.append("approval schemaVersion must be 1")

    approved = approval.get("approved")
    if not isinstance(approved, bool):
        failures.append("approved must be boolean")
        approved = False

    artifacts = approval.get("artifacts")
    if not isinstance(artifacts, list):
        failures.append("artifacts must be an array")
        artifacts = []

    review = approval.get("review")
    if not isinstance(review, dict):
        failures.append("review must be an object")
        review = {}

    digest_keys = (
        "sourceLockSha256",
        "artifactContractSha256",
        "artifactLockSha256",
    )

    if not approved:
        if approval.get("status") != "NOT_APPROVED":
            failures.append("approved=false requires status NOT_APPROVED")
        if artifacts:
            failures.append("approved=false requires empty artifacts")
        if any(approval.get(key, "") for key in digest_keys):
            failures.append("approved=false requires empty policy digests")
        if any(review.get(key) for key in ("sourceAudit", "elfAudit", "licenseAudit", "deviceAudit")):
            failures.append("approved=false requires all review gates false")
    else:
        if approval.get("status") != "APPROVED":
            failures.append("approved=true requires status APPROVED")

        if not args.artifact_lock.is_file():
            failures.append("ARTIFACTS.lock.json is required for approval")
        else:
            artifact_lock = load(args.artifact_lock)
            if artifact_lock.get("status") != "APPROVED":
                failures.append("artifact lock status must be APPROVED")
            promotion = artifact_lock.get("promotion", {})
            if promotion.get("approved") is not True:
                failures.append("artifact lock promotion.approved must be true")
            if artifact_lock.get("androidPackagingBlockers"):
                failures.append("artifact lock still contains Android packaging blockers")
            if artifact_lock.get("unreviewedNeeded"):
                failures.append("artifact lock still contains unreviewed dependencies")

        expected_digests = {
            "sourceLockSha256": digest(args.source_lock),
            "artifactContractSha256": digest(args.artifact_contract),
            "artifactLockSha256": digest(args.artifact_lock)
                if args.artifact_lock.is_file()
                else None,
        }
        for key, expected in expected_digests.items():
            actual = str(approval.get(key, "")).lower()
            if expected is None:
                continue
            if not HEX64.fullmatch(actual):
                failures.append(f"{key} is not a SHA-256")
            elif actual != expected:
                failures.append(f"{key} does not match repository policy file")

        if not all(review.get(key) is True for key in (
            "sourceAudit",
            "elfAudit",
            "licenseAudit",
            "deviceAudit",
        )):
            failures.append("all review gates must be true for approval")

        roles = [item.get("role") for item in artifacts if isinstance(item, dict)]
        if set(roles) != ROLES or len(roles) != len(ROLES):
            failures.append(f"artifact roles must be exactly {sorted(ROLES)}")

        for item in artifacts:
            if not isinstance(item, dict):
                failures.append("artifact entry must be an object")
                continue
            name = str(item.get("fileName", ""))
            sha = str(item.get("sha256", "")).lower()
            size = item.get("bytes")
            if not name.startswith("lib") or not name.endswith(".so") or "/" in name or "\\" in name:
                failures.append(f"artifact alias is not Android lib<name>.so: {name}")
            if not HEX64.fullmatch(sha):
                failures.append(f"artifact SHA-256 invalid: {item.get('role')}")
            if not isinstance(size, int) or size <= 0:
                failures.append(f"artifact bytes invalid: {item.get('role')}")

    if failures:
        print("PROOT APPROVAL POLICY FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    state = "APPROVED" if approved else "LOCKED_NOT_APPROVED"
    print(f"PROOT_APPROVAL_POLICY_OK state={state}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
