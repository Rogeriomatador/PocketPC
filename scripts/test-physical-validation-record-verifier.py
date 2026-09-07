#!/usr/bin/env python3
"""Self-test for verify-physical-validation-record.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-physical-validation-record.py"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write(path: pathlib.Path, data: bytes) -> None:
    path.write_bytes(data)


def make_fixture(root: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path, str]:
    commit = "a" * 40
    build = root / "build"
    install = root / "install"
    physical = root / "physical"
    build.mkdir()
    install.mkdir()
    physical.mkdir()

    build_record = b'{"synthetic":"build"}'
    install_record = b'{"synthetic":"install"}'
    evidence = b'{"synthetic":"evidence"}'
    bundle = b"synthetic-bundle"
    automation = {
        "schemaVersion": 1,
        "state": "PASS",
        "sourceRevision": commit,
        "sourceRevisionPinned": True,
        "bundleSha256": sha(bundle),
        "evidenceSha256": sha(evidence),
        "filesystemCriticalPassed": True,
        "hostFilesystemCriticalPassed": True,
        "runtimeLinkSemanticsReady": False,
        "desktop": {
            "orientationLandscape": True,
            "screenWidthDp": 915,
            "screenHeightDp": 412,
            "freeformWindowManagement": False,
            "secondaryDisplayActivities": True,
            "pcHardwareType": False,
            "externalDisplayCount": 0,
            "presentationDisplayCount": 0,
            "mouseCount": 1,
            "keyboardCount": 1,
            "gamepadCount": 0,
        },
        "nativeHostLoaded": True,
    }
    automation_bytes = json.dumps(automation, indent=2).encode()

    write(build / "local-build-record.json", build_record)
    write(install / "device-install-record.json", install_record)
    write(physical / "device-evidence.json", evidence)
    write(physical / "pocketpc-evidence-bundle.zip", bundle)
    write(physical / "automation-result.json", automation_bytes)
    (physical / "bundle-verification.txt").write_text(
        "POCKETPC_EVIDENCE_BUNDLE_OK\n",
        encoding="utf-8",
    )
    (physical / "device-chain-verification.txt").write_text(
        "POCKETPC_DEVICE_CHAIN_OK\n",
        encoding="utf-8",
    )

    record = {
        "schemaVersion": 1,
        "classification": "PHYSICAL_DEVICE_CHAIN_VERIFIED",
        "generatedAtUtc": "2026-09-06T00:00:00Z",
        "sourceCommit": commit,
        "buildRecordSha256": sha(build_record),
        "installRecordSha256": sha(install_record),
        "automationResultSha256": sha(automation_bytes),
        "evidenceSha256": sha(evidence),
        "bundleSha256": sha(bundle),
        "filesystemCriticalPassed": True,
        "hostFilesystemCriticalPassed": True,
        "runtimeLinkSemanticsReady": False,
        "desktopOrientationLandscape": True,
        "desktopFreeformAdvertised": False,
        "secondaryDisplayActivitiesAdvertised": True,
        "androidPcHardwareAdvertised": False,
        "externalDisplayCount": 0,
        "presentationDisplayCount": 0,
        "mouseCount": 1,
        "keyboardCount": 1,
        "gamepadCount": 0,
        "nativeHostLoaded": True,
        "substrateState": "SUBSTRATE_NOT_APPROVED",
        "prootReady": False,
    }
    record_path = physical / "physical-validation-record.json"
    record_path.write_text(json.dumps(record, indent=2), encoding="utf-8")
    (physical / "physical-validation-record.json.sha256").write_text(
        f"{sha(record_path.read_bytes())}  physical-validation-record.json\n",
        encoding="ascii",
    )

    return build, install, physical, commit


def run(
    build: pathlib.Path,
    install: pathlib.Path,
    physical: pathlib.Path,
    commit: str,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            str(physical),
            "--build-dir",
            str(build),
            "--install-dir",
            str(install),
            "--expected-commit",
            commit,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-physical-record-") as temp:
        root = pathlib.Path(temp)
        build, install, physical, commit = make_fixture(root)

        good = run(build, install, physical, commit)
        if good.returncode != 0 or "PHYSICAL_VALIDATION_RECORD_OK" not in good.stdout:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid physical record was rejected")

        (physical / "device-evidence.json").write_bytes(
            (physical / "device-evidence.json").read_bytes() + b"tampered"
        )
        bad = run(build, install, physical, commit)
        if bad.returncode == 0:
            raise SystemExit("tampered evidence unexpectedly passed")
        if "SHA-256 mismatch for evidenceSha256" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("tampered evidence hash mismatch was not reported")

    print("PHYSICAL_VALIDATION_RECORD_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
