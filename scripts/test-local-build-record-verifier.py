#!/usr/bin/env python3
"""Self-test for verify-local-build-record.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-local-build-record.py"
BUILD_LOCK = ROOT / "toolchains" / "android-build-lock.json"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def create_fixture(root: pathlib.Path) -> tuple[pathlib.Path, str]:
    build_lock = json.loads(BUILD_LOCK.read_text(encoding="utf-8"))
    app = build_lock["app"]
    commit = "a" * 40
    cert = "b" * 64
    apk_name = f"PocketPC-{app['versionName']}-aaaaaaaaaaaa-debug.apk"
    apk_bytes = b"synthetic-apk-bytes"
    apk_path = root / apk_name
    apk_path.write_bytes(apk_bytes)
    apk_sha = sha(apk_bytes)

    (root / f"{apk_name}.sha256").write_text(
        f"{apk_sha}  {apk_name}\n",
        encoding="ascii",
    )
    (root / "apk-signing.txt").write_text(
        f"Signer #1 certificate SHA-256 digest: {cert}\n",
        encoding="utf-8",
    )

    record = {
        "schemaVersion": 1,
        "classification": "LOCAL_BUILD_POLICY_CHECKED",
        "generatedAtUtc": "2026-09-06T00:00:00Z",
        "source": {
            "commit": commit,
            "dirty": False,
            "embeddedRevision": commit,
        },
        "app": {
            "packageName": app["packageName"],
            "versionName": app["versionName"],
            "versionCode": app["versionCode"],
        },
        "toolchain": {},
        "checks": {
            "pythonPolicyChecks": "PASS",
            "unitTests": "PASS",
            "lint": "PASS",
            "assembleDebug": "PASS",
            "apkStructure": "PASS",
            "unapprovedSubstrateRejected": "PASS",
        },
        "apk": {
            "fileName": apk_name,
            "bytes": len(apk_bytes),
            "sha256": apk_sha,
            "signingCertificateSha256": [cert],
        },
    }
    (root / "local-build-record.json").write_text(
        json.dumps(record, indent=2),
        encoding="utf-8",
    )
    return apk_path, commit


def run(root: pathlib.Path, commit: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            str(root),
            "--expected-commit",
            commit,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-local-build-test-") as temp:
        root = pathlib.Path(temp)
        apk, commit = create_fixture(root)

        good = run(root, commit)
        if good.returncode != 0 or "LOCAL_BUILD_RECORD_OK" not in good.stdout:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid local build fixture was rejected")

        record_path = root / "local-build-record.json"
        record = json.loads(record_path.read_text(encoding="utf-8"))
        record["checks"]["pythonPolicyChecks"] = ["policy output", "PASS"]
        record_path.write_text(json.dumps(record, indent=2), encoding="utf-8")

        contaminated = run(root, commit)
        if contaminated.returncode == 0:
            raise SystemExit("array-valued Python policy state unexpectedly passed")
        if "unexpected pythonPolicyChecks state" not in contaminated.stderr:
            print(contaminated.stderr, file=sys.stderr)
            raise SystemExit("array-valued policy failure was not reported")

        record["checks"]["pythonPolicyChecks"] = "PASS"
        record_path.write_text(json.dumps(record, indent=2), encoding="utf-8")

        apk.write_bytes(apk.read_bytes() + b"tampered")
        bad = run(root, commit)
        if bad.returncode == 0:
            raise SystemExit("tampered APK unexpectedly passed")
        if "APK SHA-256 mismatch" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("tampered APK hash failure was not reported")

    print("LOCAL_BUILD_RECORD_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
