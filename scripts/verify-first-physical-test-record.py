#!/usr/bin/env python3
"""Verify the final PocketPC first-physical-test record."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD_LOCK = ROOT / "toolchains" / "android-build-lock.json"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
GIT40 = re.compile(r"^[0-9a-f]{40}$")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("physical_dir", type=pathlib.Path)
    parser.add_argument("--build-dir", type=pathlib.Path, required=True)
    parser.add_argument("--expected-commit")
    args = parser.parse_args()

    physical = args.physical_dir.resolve()
    build = args.build_dir.resolve()
    failures: list[str] = []

    final_path = physical / "first-physical-test-record.json"
    final_sidecar = physical / "first-physical-test-record.json.sha256"
    build_record_path = build / "local-build-record.json"
    physical_record_path = physical / "physical-validation-record.json"
    physical_verify_path = physical / "physical-validation-verification.txt"
    manual_launch_path = physical / "manual-launch.txt"

    for path in (
        final_path,
        build_record_path,
        physical_record_path,
        physical_verify_path,
        manual_launch_path,
    ):
        if not path.is_file():
            failures.append(f"required file missing: {path.name}")

    if failures:
        print("FIRST_PHYSICAL_TEST_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    try:
        final = load_json(final_path)
        build_record = load_json(build_record_path)
        physical_record = load_json(physical_record_path)
        build_lock = load_json(BUILD_LOCK)
    except Exception as error:
        print(
            f"FIRST_PHYSICAL_TEST_RECORD_FAILED\n- invalid JSON: {error}",
            file=sys.stderr,
        )
        return 1

    if final.get("schemaVersion") != 1:
        failures.append("unsupported schemaVersion")
    if final.get("classification") != "POCKETPC_FIRST_PHYSICAL_TEST_VERIFIED":
        failures.append("unexpected final classification")

    commit = str(final.get("sourceCommit", "")).lower()
    if not GIT40.fullmatch(commit):
        failures.append("sourceCommit is not a 40-char Git SHA")
    if args.expected_commit and commit != args.expected_commit.lower():
        failures.append(
            f"commit mismatch expected={args.expected_commit} actual={commit}"
        )

    if str(build_record.get("source", {}).get("commit", "")).lower() != commit:
        failures.append("local build source commit differs from final record")
    if build_record.get("source", {}).get("dirty") is not False:
        failures.append("local build is not clean")
    if (
        str(build_record.get("source", {}).get("embeddedRevision", "")).lower()
        != commit
    ):
        failures.append("local build embedded revision differs from final commit")

    expected_version = build_lock.get("app", {}).get("versionName")
    if final.get("appVersion") != expected_version:
        failures.append("final appVersion differs from build lock")
    if build_record.get("app", {}).get("versionName") != expected_version:
        failures.append("local build versionName differs from build lock")

    hashes = {
        "localBuildRecordSha256": build_record_path,
        "physicalValidationRecordSha256": physical_record_path,
        "manualLaunchSha256": manual_launch_path,
    }
    for field, path in hashes.items():
        expected = str(final.get(field, "")).lower()
        if not HEX64.fullmatch(expected):
            failures.append(f"invalid SHA-256 field: {field}")
        elif sha256_file(path) != expected:
            failures.append(f"SHA-256 mismatch for {field}")

    build_apk_sha = str(
        build_record.get("apk", {}).get("sha256", "")
    ).lower()
    final_apk_sha = str(final.get("apkSha256", "")).lower()
    if not HEX64.fullmatch(final_apk_sha):
        failures.append("final apkSha256 invalid")
    elif final_apk_sha != build_apk_sha:
        failures.append("final APK SHA-256 differs from build record")

    physical_bundle_sha = str(
        physical_record.get("bundleSha256", "")
    ).lower()
    final_bundle_sha = str(final.get("evidenceBundleSha256", "")).lower()
    if not HEX64.fullmatch(final_bundle_sha):
        failures.append("final evidenceBundleSha256 invalid")
    elif final_bundle_sha != physical_bundle_sha:
        failures.append("final bundle SHA-256 differs from physical record")

    if final.get("filesystemCriticalPassed") is not True:
        failures.append("final filesystem critical gate is not PASS")
    if "hostFilesystemCriticalPassed" in final:
        if final.get("hostFilesystemCriticalPassed") is not True:
            failures.append("final host filesystem gate is not PASS")
        if (
            final.get("hostFilesystemCriticalPassed")
            != final.get("filesystemCriticalPassed")
        ):
            failures.append("final legacy and host filesystem gates disagree")
    if "runtimeLinkSemanticsReady" in final and not isinstance(
        final.get("runtimeLinkSemanticsReady"),
        bool,
    ):
        failures.append("final runtimeLinkSemanticsReady must be boolean")
    if final.get("nativeHostLoaded") is not True:
        failures.append("final native host gate is not PASS")
    if final.get("appOpened") is not True:
        failures.append("final app-open gate is not PASS")
    if physical_record.get("filesystemCriticalPassed") is not True:
        failures.append("physical record filesystem critical gate is not PASS")
    if "hostFilesystemCriticalPassed" in physical_record:
        if physical_record.get("hostFilesystemCriticalPassed") is not True:
            failures.append("physical record host filesystem gate is not PASS")
        if (
            physical_record.get("hostFilesystemCriticalPassed")
            != physical_record.get("filesystemCriticalPassed")
        ):
            failures.append("physical legacy and host filesystem gates disagree")
    if "runtimeLinkSemanticsReady" in physical_record and not isinstance(
        physical_record.get("runtimeLinkSemanticsReady"),
        bool,
    ):
        failures.append("physical runtimeLinkSemanticsReady must be boolean")
    if (
        "runtimeLinkSemanticsReady" in final
        and "runtimeLinkSemanticsReady" in physical_record
        and final.get("runtimeLinkSemanticsReady")
        != physical_record.get("runtimeLinkSemanticsReady")
    ):
        failures.append("final runtime link readiness differs from physical record")
    if physical_record.get("nativeHostLoaded") is not True:
        failures.append("physical record native host gate is not PASS")
    if physical_record.get("classification") != "PHYSICAL_DEVICE_CHAIN_VERIFIED":
        failures.append("physical record classification is not verified")

    physical_verify_text = physical_verify_path.read_text(
        encoding="utf-8-sig",
        errors="replace",
    )
    if "PHYSICAL_VALIDATION_RECORD_OK" not in physical_verify_text:
        failures.append("physical validation verifier output is not PASS")

    manual_launch_text = manual_launch_path.read_text(
        encoding="utf-8-sig",
        errors="replace",
    )
    if not re.search(r"(?m)^Status:\s*ok\s*$", manual_launch_text):
        failures.append("final manual Activity launch output is not PASS")

    if not final_sidecar.is_file():
        failures.append("final record SHA-256 sidecar missing")
    else:
        tokens = final_sidecar.read_text(
            encoding="ascii",
            errors="strict",
        ).strip().split()
        actual = sha256_file(final_path)
        if not tokens or tokens[0].lower() != actual:
            failures.append("final record SHA-256 sidecar mismatch")

    if failures:
        print("FIRST_PHYSICAL_TEST_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("FIRST_PHYSICAL_TEST_RECORD_OK")
    print(f"source_commit={commit}")
    print(f"apk_sha256={final_apk_sha}")
    print(f"bundle_sha256={final_bundle_sha}")
    print(f"proot_ready={final.get('prootReady')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
