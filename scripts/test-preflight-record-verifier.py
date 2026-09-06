#!/usr/bin/env python3
"""Self-test for verify-preflight-record.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-preflight-record.py"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_fixture(root: pathlib.Path) -> pathlib.Path:
    checks = [
        {"id": "toolchain-lock", "status": "PASS", "detail": "ok"},
        {"id": "git", "status": "PASS", "detail": "ok"},
        {"id": "git-head", "status": "PASS", "detail": "ok"},
        {"id": "git-clean", "status": "PASS", "detail": "ok"},
        {"id": "python3", "status": "PASS", "detail": "ok"},
        {"id": "java", "status": "PASS", "detail": "ok"},
        {"id": "android-sdk", "status": "PASS", "detail": "ok"},
        {"id": "adb", "status": "PASS", "detail": "ok"},
        {"id": "adb-authorization", "status": "PASS", "detail": "ok"},
        {"id": "physical-device", "status": "PASS", "detail": "ok"},
        {"id": "device-abi", "status": "PASS", "detail": "ok"},
        {"id": "device-api", "status": "PASS", "detail": "ok"},
        {"id": "gradle-cache", "status": "WARN", "detail": "download later"},
    ]
    record = {
        "schemaVersion": 1,
        "classification": "PREFLIGHT_PASS_WITH_WARNINGS",
        "generatedAtUtc": "2026-09-06T00:00:00Z",
        "passCount": 12,
        "warnCount": 1,
        "failCount": 0,
        "context": {
            "sourceCommit": "a" * 40,
            "deviceSerialSha256": "b" * 64,
        },
        "checks": checks,
    }
    path = root / "preflight-record.json"
    path.write_text(json.dumps(record, indent=2), encoding="utf-8")
    pathlib.Path(str(path) + ".sha256").write_text(
        f"{sha(path.read_bytes())}  preflight-record.json\n",
        encoding="ascii",
    )
    return path


def run(path: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            str(path),
            "--require-device",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-preflight-") as temp:
        root = pathlib.Path(temp)
        path = make_fixture(root)

        good = run(path)
        if good.returncode != 0 or "PREFLIGHT_RECORD_OK" not in good.stdout:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid preflight fixture was rejected")

        data = json.loads(path.read_text(encoding="utf-8"))
        data["failCount"] = 1
        path.write_text(json.dumps(data, indent=2), encoding="utf-8")
        pathlib.Path(str(path) + ".sha256").write_text(
            f"{sha(path.read_bytes())}  preflight-record.json\n",
            encoding="ascii",
        )

        bad = run(path)
        if bad.returncode == 0:
            raise SystemExit("inconsistent preflight counts unexpectedly passed")
        if "failCount mismatch" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("preflight count mismatch was not reported")

    print("PREFLIGHT_RECORD_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
