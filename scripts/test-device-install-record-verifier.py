#!/usr/bin/env python3
"""Self-test for verify-device-install-record.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-device-install-record.py"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_fixture(root: pathlib.Path, tamper: bool = False) -> str:
    commit = "a" * 40
    apk_sha = "b" * 64
    record = {
        "schemaVersion": 1,
        "classification": "DEVICE_INSTALL_APK_HASH_VERIFIED",
        "generatedAtUtc": "2026-09-06T00:00:00Z",
        "source": {
            "commit": commit,
            "embeddedRevision": commit,
            "dirty": False,
        },
        "build": {
            "buildRecordSha256": "c" * 64,
            "apkFileName": "PocketPC.apk",
            "apkSha256": apk_sha,
            "apkBytes": 123,
            "packageName": "dev.pocketpc.core",
            "versionName": "0.1.0-alpha13",
            "versionCode": 13,
        },
        "device": {
            "serialSha256": "d" * 64,
            "manufacturer": "Synthetic",
            "model": "Device",
            "androidApi": 37,
            "abis": ["arm64-v8a"],
            "emulator": False,
            "buildFingerprint": "synthetic/fingerprint",
        },
        "install": {
            "adbInstall": "PASS",
            "packagePathPresent": True,
            "installedVersionName": "0.1.0-alpha13",
            "installedVersionCode": 13,
            "installedApkHashCheck": "PASS",
            "installedApkSha256": apk_sha,
            "pullError": None,
        },
        "launch": {
            "activity": "dev.pocketpc.core/.MainActivity",
            "status": "PASS",
            "pid": "1234",
            "outputFile": "activity-launch.txt",
        },
    }
    if tamper:
        record["install"]["installedApkSha256"] = "e" * 64

    record_path = root / "device-install-record.json"
    record_path.write_text(json.dumps(record, indent=2), encoding="utf-8")
    (root / "activity-launch.txt").write_text(
        "Starting: Intent\nStatus: ok\nActivity: dev.pocketpc.core/.MainActivity\n",
        encoding="utf-8",
    )
    (root / "device-install-record.json.sha256").write_text(
        f"{sha(record_path.read_bytes())}  device-install-record.json\n",
        encoding="ascii",
    )
    return commit


def run(root: pathlib.Path, commit: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            str(root),
            "--expected-commit",
            commit,
            "--require-installed-apk-hash",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-install-record-") as temp:
        root = pathlib.Path(temp)
        commit = make_fixture(root)
        good = run(root, commit)
        if good.returncode != 0 or "DEVICE_INSTALL_RECORD_OK" not in good.stdout:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid install fixture rejected")

        commit = make_fixture(root, tamper=True)
        bad = run(root, commit)
        if bad.returncode == 0:
            raise SystemExit("tampered installed APK hash unexpectedly passed")
        if "installed APK SHA-256 differs" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("tamper reason not reported")

    print("DEVICE_INSTALL_RECORD_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
