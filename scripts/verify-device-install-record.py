#!/usr/bin/env python3
"""Verify PocketPC Device Install Gate output."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys

HEX64 = re.compile(r"^[0-9a-f]{64}$")
GIT40 = re.compile(r"^[0-9a-f]{40}$")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("install_dir", type=pathlib.Path)
    parser.add_argument("--expected-commit")
    parser.add_argument("--require-installed-apk-hash", action="store_true")
    args = parser.parse_args()

    root = args.install_dir.resolve()
    record_path = root / "device-install-record.json"
    sidecar_path = root / "device-install-record.json.sha256"
    launch_path = root / "activity-launch.txt"
    failures: list[str] = []

    if not record_path.is_file():
        print("DEVICE_INSTALL_RECORD_FAILED\n- device-install-record.json missing", file=sys.stderr)
        return 1

    try:
        record = json.loads(record_path.read_text(encoding="utf-8-sig"))
    except Exception as error:
        print(f"DEVICE_INSTALL_RECORD_FAILED\n- invalid JSON: {error}", file=sys.stderr)
        return 1

    if record.get("schemaVersion") != 1:
        failures.append("unsupported schemaVersion")

    source = record.get("source", {})
    build = record.get("build", {})
    device = record.get("device", {})
    install = record.get("install", {})
    launch = record.get("launch", {})

    commit = str(source.get("commit", "")).lower()
    embedded = str(source.get("embeddedRevision", ""))
    dirty = source.get("dirty")

    if not GIT40.fullmatch(commit):
        failures.append("source.commit is not a 40-char Git SHA")
    if dirty is False and embedded.lower() != commit:
        failures.append("clean install record embeddedRevision must equal commit")
    if dirty is True and embedded != "LOCAL_UNPINNED":
        failures.append("dirty install record must be LOCAL_UNPINNED")
    if dirty not in (True, False):
        failures.append("source.dirty must be boolean")

    if args.expected_commit and commit != args.expected_commit.lower():
        failures.append(f"commit mismatch expected={args.expected_commit} actual={commit}")

    apk_sha = str(build.get("apkSha256", "")).lower()
    build_record_sha = str(build.get("buildRecordSha256", "")).lower()
    serial_sha = str(device.get("serialSha256", "")).lower()
    for label, value in (
        ("buildRecordSha256", build_record_sha),
        ("apkSha256", apk_sha),
        ("device.serialSha256", serial_sha),
    ):
        if not HEX64.fullmatch(value):
            failures.append(f"invalid SHA-256: {label}")

    abis = device.get("abis")
    if not isinstance(abis, list) or "arm64-v8a" not in abis:
        failures.append("device ABI list does not include arm64-v8a")
    if device.get("emulator") is not False:
        failures.append("record is not a physical-device result")
    if not isinstance(device.get("androidApi"), int) or device.get("androidApi") < 26:
        failures.append("Android API is invalid/unsupported")

    if install.get("adbInstall") != "PASS":
        failures.append("adb install gate is not PASS")
    if install.get("packagePathPresent") is not True:
        failures.append("installed package path not confirmed")
    if install.get("installedVersionName") != build.get("versionName"):
        failures.append("installed versionName differs from build")
    if install.get("installedVersionCode") != build.get("versionCode"):
        failures.append("installed versionCode differs from build")

    hash_state = install.get("installedApkHashCheck")
    if hash_state not in ("PASS", "UNAVAILABLE"):
        failures.append(f"unexpected installed APK hash state: {hash_state}")
    if hash_state == "PASS":
        installed_sha = str(install.get("installedApkSha256", "")).lower()
        if not HEX64.fullmatch(installed_sha):
            failures.append("installed APK SHA-256 invalid")
        elif installed_sha != apk_sha:
            failures.append("installed APK SHA-256 differs from local APK")
    elif args.require_installed_apk_hash:
        failures.append("installed APK hash verification was required")

    if launch.get("status") != "PASS":
        failures.append("MainActivity launch is not PASS")
    if launch.get("activity") != f"{build.get('packageName')}/.MainActivity":
        failures.append("unexpected launched activity")
    if not launch_path.is_file():
        failures.append("activity-launch.txt missing")
    else:
        launch_text = launch_path.read_text(encoding="utf-8-sig", errors="replace")
        if not re.search(r"(?m)^Status:\s*ok\s*$", launch_text):
            failures.append("activity-launch.txt does not contain Status: ok")

    if not sidecar_path.is_file():
        failures.append("install-record SHA-256 sidecar missing")
    else:
        sidecar = sidecar_path.read_text(encoding="ascii", errors="strict").strip().split()
        actual_record_sha = sha256_file(record_path)
        if not sidecar or sidecar[0].lower() != actual_record_sha:
            failures.append("install-record SHA-256 sidecar mismatch")

    classification = record.get("classification")
    expected_classification = (
        "DEVICE_INSTALL_APK_HASH_VERIFIED" if hash_state == "PASS"
        else "DEVICE_INSTALL_METADATA_VERIFIED"
    )
    if classification != expected_classification:
        failures.append(
            f"classification mismatch expected={expected_classification}"
        )

    if failures:
        print("DEVICE_INSTALL_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("DEVICE_INSTALL_RECORD_OK")
    print(f"classification={classification}")
    print(f"source_commit={commit}")
    print(f"device_serial_sha256={serial_sha}")
    print(f"apk_sha256={apk_sha}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
