#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIN = ROOT / "updates" / "bootstrap-signer.json"
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Verify that a candidate PocketPC signing certificate "
            "matches the physically observed bootstrap signer pin."
        )
    )
    parser.add_argument(
        "--signer-sha256",
        required=True,
        help="64-hex SHA-256 digest of the candidate APK signing certificate",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    candidate = args.signer_sha256.strip().lower()

    if not SHA256_RE.fullmatch(candidate):
        print(
            "BOOTSTRAP_SIGNER_VERIFY_FAILED\n"
            "- candidate signer SHA-256 is not 64 hex characters",
            file=sys.stderr,
        )
        return 1

    try:
        metadata = json.loads(PIN.read_text(encoding="utf-8"))
    except Exception as error:
        print(
            f"BOOTSTRAP_SIGNER_VERIFY_FAILED\n"
            f"- invalid bootstrap signer metadata: {error}",
            file=sys.stderr,
        )
        return 1

    failures: list[str] = []

    if metadata.get("schemaVersion") != 1:
        failures.append("bootstrap signer schemaVersion must be 1")

    if metadata.get("packageName") != "dev.pocketpc.core":
        failures.append("bootstrap signer packageName must be dev.pocketpc.core")

    allowed_raw = metadata.get("allowedSigningCertificateSha256")
    if not isinstance(allowed_raw, list) or not allowed_raw:
        failures.append("allowed signer list must be a non-empty array")
        allowed: set[str] = set()
    else:
        allowed = set()
        for value in allowed_raw:
            normalized = str(value).strip().lower()
            if not SHA256_RE.fullmatch(normalized):
                failures.append(
                    "bootstrap signer metadata contains an invalid SHA-256"
                )
                continue
            allowed.add(normalized)

    if candidate not in allowed:
        failures.append(
            "candidate APK signer does not match the physically pinned "
            "PocketPC bootstrap signer"
        )

    if failures:
        print("BOOTSTRAP_SIGNER_VERIFY_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("BOOTSTRAP_SIGNER_VERIFY_OK")
    print(f"candidate_sha256={candidate}")
    print(
        "bootstrap_version="
        + str(metadata.get("bootstrapVersionName", "unknown"))
    )
    print(
        "bootstrap_source_revision="
        + str(metadata.get("bootstrapSourceRevision", "unknown"))
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
