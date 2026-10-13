#!/usr/bin/env python3
"""Fail-closed verifier for a public PocketPC gameplay-ready release.

A PASS record is an index to immutable physical evidence, not a substitute for it.
Home Test builds intentionally do not use this stable/public-release gate.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RECORD = ROOT / "release/public-release-readiness.json"
GIT40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_GATES = (
    "exactRevisionBuiltAndTested",
    "prootRootfsExecuted",
    "box64X8664Executed",
    "wineWin64Executed",
    "wineWindowIntegrated",
    "vulkanDxvkContinuousPresent",
    "robloxPlayerStarted",
    "robloxPlayerRendered",
    "robloxServerJoined",
    "networkObserved",
    "inputGameplayObserved",
    "audioObserved",
    "minimumStableSessionObserved",
)
MIN_GAMEPLAY_SESSION_MILLIS = 5 * 60 * 1000


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "record",
        nargs="?",
        type=pathlib.Path,
        default=DEFAULT_RECORD,
    )
    parser.add_argument("--expected-revision")
    args = parser.parse_args()

    failures: list[str] = []
    try:
        record = json.loads(args.record.read_text(encoding="utf-8"))
    except Exception as error:
        print(
            "PUBLIC_RELEASE_READINESS_FAILED\n"
            f"- record: {error.__class__.__name__}: {error}",
            file=sys.stderr,
        )
        return 1

    if record.get("schemaVersion") != 1:
        failures.append("schemaVersion must be 1")
    if record.get("status") != "PHYSICAL_GAMEPLAY_PASS":
        failures.append("status must be PHYSICAL_GAMEPLAY_PASS")

    revision = str(record.get("sourceRevision") or "").lower()
    if not GIT40.fullmatch(revision):
        failures.append("sourceRevision must be a full 40-hex revision")
    expected = str(args.expected_revision or "").lower()
    if expected and revision != expected:
        failures.append(
            f"sourceRevision mismatch: record={revision or 'missing'} expected={expected}"
        )

    bundle_sha = str(record.get("evidenceBundleSha256") or "").lower()
    if not HEX64.fullmatch(bundle_sha):
        failures.append("evidenceBundleSha256 must be a full SHA-256")

    evidence_url = str(record.get("immutableEvidenceUrl") or "")
    if not (
        evidence_url.startswith(
            "https://github.com/Rogeriomatador/PocketPC"
        )
        and "/releases/download/" in evidence_url
    ):
        failures.append(
            "immutableEvidenceUrl must point to an immutable PocketPC GitHub release asset"
        )

    device = record.get("device") or {}
    if not str(device.get("manufacturer") or "").strip():
        failures.append("device.manufacturer is required")
    if not str(device.get("model") or "").strip():
        failures.append("device.model is required")
    if not isinstance(device.get("androidApi"), int):
        failures.append("device.androidApi must be an integer")

    session = record.get("session") or {}
    stable_millis = session.get("stableSessionMillis")
    if not isinstance(stable_millis, int) or stable_millis < MIN_GAMEPLAY_SESSION_MILLIS:
        failures.append(
            "session.stableSessionMillis must prove at least five minutes"
        )
    if session.get("crashObserved") is not False:
        failures.append("session.crashObserved must be false")

    gates = record.get("gates") or {}
    for gate in REQUIRED_GATES:
        if gates.get(gate) is not True:
            failures.append(f"required physical gate is not true: {gate}")

    unknown = sorted(set(gates) - set(REQUIRED_GATES))
    if unknown:
        failures.append(f"unknown readiness gates: {unknown}")

    if failures:
        print("PUBLIC_RELEASE_READINESS_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("PUBLIC_RELEASE_READINESS_OK")
    print(f"sourceRevision={revision}")
    print(f"evidenceBundleSha256={bundle_sha}")
    print(f"stableSessionMillis={stable_millis}")
    print("robloxGameplayPhysical=true")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
