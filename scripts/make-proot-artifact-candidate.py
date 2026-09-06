#!/usr/bin/env python3
"""Create a review-only artifact lock candidate from an ELF audit report."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_LOCK = ROOT / "third_party" / "proot" / "LOCK.json"
ARTIFACT_CONTRACT = ROOT / "third_party" / "proot" / "ARTIFACT_CONTRACT.json"


def file_sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()

    report = json.loads(args.report.read_text(encoding="utf-8"))
    source_lock = json.loads(SOURCE_LOCK.read_text(encoding="utf-8"))

    if report.get("status") == "FAILED":
        raise SystemExit("cannot create candidate from failed ELF audit")
    if not report.get("artifacts"):
        raise SystemExit("ELF report has no artifacts")

    talloc = next(
        item for item in source_lock["components"] if item["id"] == "libtalloc"
    )

    candidate = {
        "schemaVersion": 2,
        "status": "REVIEW_REQUIRED_NOT_APPROVED",
        "sourceLockSha256": file_sha256(SOURCE_LOCK),
        "artifactContractSha256": file_sha256(ARTIFACT_CONTRACT),
        "sourceRecipeCommit": source_lock["recipeAuthority"]["commit"],
        "target": source_lock["target"],
        "elfAuditStatus": report["status"],
        "unreviewedNeeded": report.get("unreviewedNeeded", []),
        "androidPackagingBlockers": report.get("androidPackagingBlockers", []),
        "artifacts": report["artifacts"],
        "licenseReview": {
            "status": "REQUIRED",
            "talloc": talloc.get("licenseEvidence", {"status": "UNKNOWN"}),
        },
        "deviceReview": {"status": "REQUIRED"},
        "promotion": {
            "approved": False,
            "note": (
                "Evidence capture only. Packaging blockers, license review, "
                "artifact lock and device review must be resolved separately."
            ),
        },
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(candidate, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(candidate, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
