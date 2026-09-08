#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
PIN = ROOT / "updates" / "bootstrap-signer.json"
VERIFIER = ROOT / "scripts" / "verify-bootstrap-signer.py"


def run_verifier(candidate: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            "--signer-sha256",
            candidate,
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def main() -> int:
    failures: list[str] = []

    try:
        metadata = json.loads(PIN.read_text(encoding="utf-8"))
        allowed = metadata["allowedSigningCertificateSha256"]
        good = str(allowed[0]).strip().lower()
    except Exception as error:
        print(
            "BOOTSTRAP_SIGNER_SELFTEST_FAILED\n"
            f"- cannot read physical signer pin: {error}",
            file=sys.stderr,
        )
        return 1

    positive = run_verifier(good)
    if positive.returncode != 0:
        failures.append(
            "physically pinned signer was rejected: "
            + positive.stderr.strip()
        )
    if "BOOTSTRAP_SIGNER_VERIFY_OK" not in positive.stdout:
        failures.append(
            "positive verifier result is missing BOOTSTRAP_SIGNER_VERIFY_OK"
        )

    different = "0" * 64
    if different == good:
        different = "f" * 64

    negative = run_verifier(different)
    if negative.returncode == 0:
        failures.append(
            "different but well-formed signer was incorrectly accepted"
        )
    if "BOOTSTRAP_SIGNER_VERIFY_FAILED" not in negative.stderr:
        failures.append(
            "negative signer result is missing BOOTSTRAP_SIGNER_VERIFY_FAILED"
        )

    malformed = run_verifier("not-a-sha256")
    if malformed.returncode == 0:
        failures.append(
            "malformed signer digest was incorrectly accepted"
        )
    if "not 64 hex characters" not in malformed.stderr:
        failures.append(
            "malformed signer failure reason is missing"
        )

    if failures:
        print("BOOTSTRAP_SIGNER_SELFTEST_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("BOOTSTRAP_SIGNER_SELFTEST_OK")
    print(f"positive_signer={good}")
    print("negative_signer_rejected=true")
    print("malformed_signer_rejected=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
