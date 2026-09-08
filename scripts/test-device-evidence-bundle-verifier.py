#!/usr/bin/env python3
"""Self-test for verify-device-evidence-bundle.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-device-evidence-bundle.py"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_bundle(path: pathlib.Path, tamper: bool = False) -> None:
    identity_obj = {
        "schemaVersion": 1,
        "packageName": "dev.pocketpc.core",
        "versionName": "0.1.0-alpha19",
        "versionCode": 19,
        "sourceRevision": "LOCAL_UNPINNED",
        "sourceRevisionPinned": False,
        "debug": True,
        "signingCertificateSha256": ["a" * 64],
        "installerPackage": None,
    }
    identity = json.dumps(identity_obj, sort_keys=True).encode()

    evidence = json.dumps(
        {
            "schemaVersion": 4,
            "pocketPcVersion": "0.1.0-alpha19",
            "buildIdentity": identity_obj,
            "desktop": {
                "orientationLandscape": False,
                "screenWidthDp": 469,
                "screenHeightDp": 1043,
                "secondaryDisplayActivities": True,
                "freeformWindowManagement": False,
                "pcHardwareType": False,
                "externalDisplayCount": 0,
                "presentationDisplayCount": 0,
                "peripherals": {
                    "mouseCount": 1,
                    "keyboardCount": 1,
                    "gamepadCount": 0,
                },
                "externalDisplays": [],
            },
        },
        sort_keys=True,
    ).encode()

    sidecar = f"{sha(evidence)}  device-evidence-latest.json\n".encode()
    approval = json.dumps(
        {
            "schemaVersion": 1,
            "status": "NOT_APPROVED",
            "approved": False,
            "sourceLockSha256": "",
            "artifactContractSha256": "",
            "artifactLockSha256": "",
            "artifacts": [],
            "review": {
                "sourceAudit": False,
                "elfAudit": False,
                "licenseAudit": False,
                "deviceAudit": False,
            },
        },
        sort_keys=True,
    ).encode()

    payload = {
        "evidence/device-evidence.json": evidence,
        "evidence/device-evidence.sha256": sidecar,
        "identity/build-identity.json": identity,
        "bundle-info.json": json.dumps(
            {
                "schemaVersion": 1,
                "evidenceSha256": sha(evidence),
                "sourceRevision": "LOCAL_UNPINNED",
                "versionName": "0.1.0-alpha19",
                "versionCode": 19,
            },
            sort_keys=True,
        ).encode(),
        "policy/proot-substrate-approval.json": approval,
        "policy/proot/LOCK.json": b'{"schemaVersion":1}',
        "policy/proot/ARTIFACT_CONTRACT.json": b'{"schemaVersion":1}',
    }

    manifest_entries = [
        {"path": name, "bytes": len(data), "sha256": sha(data)}
        for name, data in sorted(payload.items())
    ]
    manifest = json.dumps(
        {
            "schemaVersion": 1,
            "generatedAtUtc": "2026-09-06T00:00:00Z",
            "entries": manifest_entries,
        },
        sort_keys=True,
    ).encode()

    if tamper:
        payload["evidence/device-evidence.json"] += b"tampered"

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in payload.items():
            archive.writestr(name, data)
        archive.writestr("bundle-manifest.json", manifest)


def run(path: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(VERIFIER), str(path)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-bundle-test-") as temp:
        root = pathlib.Path(temp)
        good = root / "good.zip"
        bad = root / "bad.zip"

        make_bundle(good)
        result = run(good)
        if result.returncode != 0 or "POCKETPC_EVIDENCE_BUNDLE_OK" not in result.stdout:
            print(result.stdout, file=sys.stderr)
            print(result.stderr, file=sys.stderr)
            raise SystemExit("valid bundle was rejected")

        make_bundle(bad, tamper=True)
        result = run(bad)
        if result.returncode == 0:
            raise SystemExit("tampered bundle unexpectedly passed")
        if "SHA-256 mismatch" not in result.stderr and "sidecar" not in result.stderr:
            print(result.stderr, file=sys.stderr)
            raise SystemExit("tamper reason was not reported")

    print("POCKETPC_EVIDENCE_BUNDLE_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
