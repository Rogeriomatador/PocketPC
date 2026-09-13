#!/usr/bin/env python3
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify-public-release-readiness.py"
TRACKED_RECORD = ROOT / "release/public-release-readiness.json"
REVISION = "a" * 40

GATES = {
    "exactRevisionBuiltAndTested": True,
    "prootRootfsExecuted": True,
    "box64X8664Executed": True,
    "wineWin64Executed": True,
    "wineWindowIntegrated": True,
    "vulkanDxvkContinuousPresent": True,
    "robloxPlayerStarted": True,
    "robloxPlayerRendered": True,
    "robloxServerJoined": True,
    "networkObserved": True,
    "inputGameplayObserved": True,
    "audioObserved": True,
    "minimumStableSessionObserved": True,
}


def invoke(record: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            str(record),
            "--expected-revision",
            REVISION,
        ],
        check=False,
        capture_output=True,
        text=True,
    )


def main() -> int:
    failures: list[str] = []
    tracked = subprocess.run(
        [sys.executable, str(VERIFIER), str(TRACKED_RECORD)],
        check=False,
        capture_output=True,
        text=True,
    )
    if tracked.returncode == 0:
        failures.append("tracked blocked record unexpectedly passed")

    base = {
        "schemaVersion": 1,
        "status": "PHYSICAL_GAMEPLAY_PASS",
        "sourceRevision": REVISION,
        "evidenceBundleSha256": "b" * 64,
        "immutableEvidenceUrl": (
            "https://github.com/Rogeriomatador/PocketPC/"
            "releases/download/evidence-test/device-evidence.zip"
        ),
        "device": {
            "manufacturer": "test",
            "model": "test",
            "androidApi": 36,
        },
        "session": {
            "stableSessionMillis": 300000,
            "crashObserved": False,
        },
        "gates": dict(GATES),
    }

    with tempfile.TemporaryDirectory() as directory:
        path = pathlib.Path(directory) / "record.json"

        path.write_text(json.dumps(base), encoding="utf-8")
        passed = invoke(path)
        if passed.returncode != 0 or "PUBLIC_RELEASE_READINESS_OK" not in passed.stdout:
            failures.append("complete exact-revision record did not pass")

        blocked = json.loads(json.dumps(base))
        blocked["gates"]["robloxServerJoined"] = False
        path.write_text(json.dumps(blocked), encoding="utf-8")
        result = invoke(path)
        if result.returncode == 0:
            failures.append("missing Roblox server evidence passed")

        short = json.loads(json.dumps(base))
        short["session"]["stableSessionMillis"] = 299999
        path.write_text(json.dumps(short), encoding="utf-8")
        result = invoke(path)
        if result.returncode == 0:
            failures.append("short gameplay session passed")

        stale = json.loads(json.dumps(base))
        stale["sourceRevision"] = "c" * 40
        path.write_text(json.dumps(stale), encoding="utf-8")
        result = invoke(path)
        if result.returncode == 0:
            failures.append("wrong source revision passed")

    if failures:
        print("PUBLIC_RELEASE_READINESS_SELFTEST_FAILED", file=sys.stderr)
        for failure in failures:
            print("- " + failure, file=sys.stderr)
        return 1

    print("PUBLIC_RELEASE_READINESS_SELFTEST_OK")
    print("tracked_record=BLOCKED_NOT_EXECUTED")
    print("positive_and_negative_cases=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
