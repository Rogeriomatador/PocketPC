#!/usr/bin/env python3
"""Self-test for verify-first-physical-test-record.py."""

from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-first-physical-test-record.py"
BUILD_LOCK = ROOT / "toolchains" / "android-build-lock.json"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: pathlib.Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2), encoding="utf-8")


def make_fixture(
    root: pathlib.Path,
) -> tuple[pathlib.Path, pathlib.Path, str]:
    app = json.loads(BUILD_LOCK.read_text(encoding="utf-8"))["app"]
    commit = "a" * 40
    apk_sha = "b" * 64
    bundle_sha = "c" * 64

    build = root / "build"
    physical = build / "physical-validation"
    build.mkdir()
    physical.mkdir()

    build_record = {
        "schemaVersion": 1,
        "source": {
            "commit": commit,
            "dirty": False,
            "embeddedRevision": commit,
        },
        "app": app,
        "apk": {
            "sha256": apk_sha,
        },
    }
    build_record_path = build / "local-build-record.json"
    write_json(build_record_path, build_record)

    physical_record = {
        "schemaVersion": 1,
        "classification": "PHYSICAL_DEVICE_CHAIN_VERIFIED",
        "sourceCommit": commit,
        "bundleSha256": bundle_sha,
        "filesystemCriticalPassed": True,
        "hostFilesystemCriticalPassed": True,
        "runtimeLinkSemanticsReady": False,
        "desktopOrientationLandscape": False,
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
    physical_record_path = physical / "physical-validation-record.json"
    write_json(physical_record_path, physical_record)
    (physical / "physical-validation-verification.txt").write_text(
        "PHYSICAL_VALIDATION_RECORD_OK\n",
        encoding="utf-8",
    )
    manual_launch_path = physical / "manual-launch.txt"
    manual_launch_path.write_text(
        "Starting: Intent { cmp=dev.pocketpc.core/.MainActivity }\n"
        "Status: ok\n",
        encoding="utf-8",
    )

    final = {
        "schemaVersion": 1,
        "classification": "POCKETPC_FIRST_PHYSICAL_TEST_VERIFIED",
        "sourceCommit": commit,
        "appVersion": app["versionName"],
        "localBuildRecordSha256": sha(build_record_path.read_bytes()),
        "physicalValidationRecordSha256": sha(
            physical_record_path.read_bytes()
        ),
        "manualLaunchSha256": sha(manual_launch_path.read_bytes()),
        "apkSha256": apk_sha,
        "evidenceBundleSha256": bundle_sha,
        "filesystemCriticalPassed": True,
        "hostFilesystemCriticalPassed": True,
        "runtimeLinkSemanticsReady": False,
        "desktopOrientationLandscape": False,
        "desktopFreeformAdvertised": False,
        "secondaryDisplayActivitiesAdvertised": True,
        "androidPcHardwareAdvertised": False,
        "externalDisplayCount": 0,
        "presentationDisplayCount": 0,
        "mouseCount": 1,
        "keyboardCount": 1,
        "gamepadCount": 0,
        "nativeHostLoaded": True,
        "appOpened": True,
        "substrateState": "SUBSTRATE_NOT_APPROVED",
        "prootReady": False,
    }
    final_path = physical / "first-physical-test-record.json"
    write_json(final_path, final)
    (physical / "first-physical-test-record.json.sha256").write_text(
        f"{sha(final_path.read_bytes())}  first-physical-test-record.json\n",
        encoding="ascii",
    )

    return build, physical, commit


def run(
    build: pathlib.Path,
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
            "--expected-commit",
            commit,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-first-physical-") as temp:
        root = pathlib.Path(temp)
        build, physical, commit = make_fixture(root)

        good = run(build, physical, commit)
        if (
            good.returncode != 0
            or "FIRST_PHYSICAL_TEST_RECORD_OK" not in good.stdout
        ):
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid final physical record was rejected")

        build_record = build / "local-build-record.json"
        # Change the file hash while preserving valid JSON so this exercise
        # reaches the verifier's authenticated-record mismatch branch.
        build_record.write_bytes(build_record.read_bytes() + b"\n")
        bad = run(build, physical, commit)
        if bad.returncode == 0:
            raise SystemExit("tampered build record unexpectedly passed")
        if "SHA-256 mismatch for localBuildRecordSha256" not in bad.stderr:
            print(bad.stderr, file=sys.stderr)
            raise SystemExit("tampered build-record mismatch was not reported")

    print("FIRST_PHYSICAL_TEST_RECORD_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
