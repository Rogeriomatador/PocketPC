#!/usr/bin/env python3
"""Cross-verify PocketPC build, device install and evidence bundle."""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def run_check(args: list[str], label: str, failures: list[str]) -> None:
    completed = subprocess.run(
        [sys.executable, *args],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        failures.append(
            f"{label} verifier failed: {completed.stderr.strip() or completed.stdout.strip()}"
        )


def load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=pathlib.Path, required=True)
    parser.add_argument("--install-dir", type=pathlib.Path, required=True)
    parser.add_argument("--bundle", type=pathlib.Path, required=True)
    parser.add_argument("--expected-commit")
    parser.add_argument("--require-installed-apk-hash", action="store_true")
    parser.add_argument("--require-filesystem-pass", action="store_true")
    parser.add_argument("--require-host-filesystem-pass", action="store_true")
    parser.add_argument("--require-native-host", action="store_true")
    args = parser.parse_args()

    build_dir = args.build_dir.resolve()
    install_dir = args.install_dir.resolve()
    bundle = args.bundle.resolve()
    failures: list[str] = []

    build_record = load_json(build_dir / "local-build-record.json")
    install_record = load_json(install_dir / "device-install-record.json")
    commit = str(build_record.get("source", {}).get("commit", "")).lower()
    expected_commit = args.expected_commit or commit

    run_check(
        [
            str(ROOT / "scripts" / "verify-local-build-record.py"),
            str(build_dir),
            "--expected-commit",
            expected_commit,
        ],
        "local build",
        failures,
    )

    install_args = [
        str(ROOT / "scripts" / "verify-device-install-record.py"),
        str(install_dir),
        "--expected-commit",
        expected_commit,
    ]
    if args.require_installed_apk_hash:
        install_args.append("--require-installed-apk-hash")
    run_check(install_args, "device install", failures)

    run_check(
        [
            str(ROOT / "scripts" / "verify-device-evidence-bundle.py"),
            str(bundle),
            "--expected-revision",
            expected_commit,
        ],
        "device evidence bundle",
        failures,
    )

    with zipfile.ZipFile(bundle, "r") as archive:
        identity = json.loads(
            archive.read("identity/build-identity.json").decode("utf-8")
        )
        evidence = json.loads(
            archive.read("evidence/device-evidence.json").decode("utf-8")
        )

    build_source = build_record.get("source", {})
    build_app = build_record.get("app", {})
    build_apk = build_record.get("apk", {})
    install_source = install_record.get("source", {})
    install_build = install_record.get("build", {})
    install_device = install_record.get("device", {})
    evidence_device = evidence.get("device", {})

    if build_source.get("dirty") is not False:
        failures.append("full device chain requires a clean pinned build")
    if identity.get("sourceRevisionPinned") is not True:
        failures.append("evidence bundle build identity is not revision-pinned")

    if str(identity.get("sourceRevision", "")).lower() != commit:
        failures.append("bundle sourceRevision differs from build commit")
    if str(install_source.get("commit", "")).lower() != commit:
        failures.append("install source commit differs from build commit")

    for key in ("packageName", "versionName", "versionCode"):
        if build_app.get(key) != install_build.get(key):
            failures.append(f"install build {key} differs from local build")
        if build_app.get(key) != identity.get(key):
            failures.append(f"bundle identity {key} differs from local build")

    if build_apk.get("sha256") != install_build.get("apkSha256"):
        failures.append("install APK SHA-256 differs from local build")

    build_certs = sorted(build_apk.get("signingCertificateSha256", []))
    bundle_certs = sorted(identity.get("signingCertificateSha256", []))
    if build_certs != bundle_certs:
        failures.append("installed app signing identity differs from build record")

    device_pairs = (
        ("manufacturer", "manufacturer"),
        ("model", "model"),
        ("androidApi", "androidApi"),
    )
    for install_key, evidence_key in device_pairs:
        if install_device.get(install_key) != evidence_device.get(evidence_key):
            failures.append(f"device identity differs for {install_key}")

    install_abis = set(install_device.get("abis", []))
    evidence_abis = set(evidence_device.get("abis", []))
    if install_abis != evidence_abis:
        failures.append("device ABI set differs between install and evidence")

    if install_record.get("launch", {}).get("status") != "PASS":
        failures.append("install record did not confirm MainActivity launch")

    filesystem = evidence.get("filesystem", {})
    if args.require_filesystem_pass:
        if filesystem.get("allCriticalPassed") is not True:
            failures.append("all device filesystem capabilities are not PASS")

    if args.require_host_filesystem_pass:
        host_pass = filesystem.get("hostCriticalPassed")
        if host_pass is not True:
            failures.append("Android host filesystem critical gate is not PASS")

    if args.require_native_host:
        native_host = evidence.get("nativeHost", {})
        if native_host.get("loaded") is not True:
            failures.append("PocketPC native runtime host is not loaded")

    if failures:
        print("POCKETPC_DEVICE_CHAIN_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("POCKETPC_DEVICE_CHAIN_OK")
    print(f"source_commit={commit}")
    print(f"apk_sha256={build_apk.get('sha256')}")
    print(f"device={install_device.get('manufacturer')} {install_device.get('model')}")
    print(f"bundle={bundle}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
