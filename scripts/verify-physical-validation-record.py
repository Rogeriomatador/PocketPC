#!/usr/bin/env python3
"""Verify final PocketPC physical-validation output."""

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
    parser.add_argument("physical_dir", type=pathlib.Path)
    parser.add_argument("--build-dir", type=pathlib.Path, required=True)
    parser.add_argument("--install-dir", type=pathlib.Path, required=True)
    parser.add_argument("--expected-commit")
    args = parser.parse_args()

    physical = args.physical_dir.resolve()
    build = args.build_dir.resolve()
    install = args.install_dir.resolve()
    failures: list[str] = []

    record_path = physical / "physical-validation-record.json"
    sidecar_path = physical / "physical-validation-record.json.sha256"

    if not record_path.is_file():
        print(
            "PHYSICAL_VALIDATION_RECORD_FAILED\n- physical-validation-record.json missing",
            file=sys.stderr,
        )
        return 1

    try:
        record = json.loads(record_path.read_text(encoding="utf-8-sig"))
    except Exception as error:
        print(
            f"PHYSICAL_VALIDATION_RECORD_FAILED\n- invalid JSON: {error}",
            file=sys.stderr,
        )
        return 1

    if record.get("schemaVersion") != 1:
        failures.append("unsupported schemaVersion")
    if record.get("classification") != "PHYSICAL_DEVICE_CHAIN_VERIFIED":
        failures.append("unexpected physical validation classification")

    commit = str(record.get("sourceCommit", "")).lower()
    if not GIT40.fullmatch(commit):
        failures.append("sourceCommit is not a 40-char Git SHA")
    if args.expected_commit and commit != args.expected_commit.lower():
        failures.append(
            f"commit mismatch expected={args.expected_commit} actual={commit}"
        )

    required_hash_files = {
        "buildRecordSha256": build / "local-build-record.json",
        "installRecordSha256": install / "device-install-record.json",
        "automationResultSha256": physical / "automation-result.json",
        "evidenceSha256": physical / "device-evidence.json",
        "bundleSha256": physical / "pocketpc-evidence-bundle.zip",
    }

    for field, path in required_hash_files.items():
        expected = str(record.get(field, "")).lower()
        if not HEX64.fullmatch(expected):
            failures.append(f"invalid SHA-256 field: {field}")
            continue
        if not path.is_file():
            failures.append(f"required file missing for {field}: {path.name}")
            continue
        actual = sha256_file(path)
        if actual != expected:
            failures.append(f"SHA-256 mismatch for {field}")

    if record.get("filesystemCriticalPassed") is not True:
        failures.append("filesystemCriticalPassed is not true")
    if "hostFilesystemCriticalPassed" in record:
        if record.get("hostFilesystemCriticalPassed") is not True:
            failures.append("hostFilesystemCriticalPassed is not true")
        if (
            record.get("hostFilesystemCriticalPassed")
            != record.get("filesystemCriticalPassed")
        ):
            failures.append("legacy and host filesystem gates disagree")
    if "runtimeLinkSemanticsReady" in record and not isinstance(
        record.get("runtimeLinkSemanticsReady"),
        bool,
    ):
        failures.append("runtimeLinkSemanticsReady must be boolean")
    if record.get("nativeHostLoaded") is not True:
        failures.append("nativeHostLoaded is not true")

    automation_path = physical / "automation-result.json"
    if automation_path.is_file():
        try:
            automation = json.loads(
                automation_path.read_text(encoding="utf-8-sig")
            )
        except Exception as error:
            failures.append(f"invalid automation-result.json: {error}")
            automation = {}

        if automation.get("state") != "PASS":
            failures.append("automation result is not PASS")
        if str(automation.get("sourceRevision", "")).lower() != commit:
            failures.append("automation sourceRevision differs from final commit")
        if automation.get("sourceRevisionPinned") is not True:
            failures.append("automation source revision is not pinned")
        if automation.get("filesystemCriticalPassed") is not True:
            failures.append("automation filesystem critical gate is not PASS")
        if "hostFilesystemCriticalPassed" in automation:
            if automation.get("hostFilesystemCriticalPassed") is not True:
                failures.append("automation host filesystem gate is not PASS")
            if (
                automation.get("hostFilesystemCriticalPassed")
                != automation.get("filesystemCriticalPassed")
            ):
                failures.append("automation legacy and host filesystem gates disagree")
        if "runtimeLinkSemanticsReady" in automation and not isinstance(
            automation.get("runtimeLinkSemanticsReady"),
            bool,
        ):
            failures.append("automation runtimeLinkSemanticsReady must be boolean")
        if automation.get("nativeHostLoaded") is not True:
            failures.append("automation native host is not loaded")
        if (
            str(automation.get("bundleSha256", "")).lower()
            != str(record.get("bundleSha256", "")).lower()
        ):
            failures.append("automation bundleSha256 differs from final record")
        if (
            str(automation.get("evidenceSha256", "")).lower()
            != str(record.get("evidenceSha256", "")).lower()
        ):
            failures.append("automation evidenceSha256 differs from final record")

    bundle_verify = physical / "bundle-verification.txt"
    if not bundle_verify.is_file():
        failures.append("bundle-verification.txt missing")
    elif "POCKETPC_EVIDENCE_BUNDLE_OK" not in bundle_verify.read_text(
        encoding="utf-8-sig",
        errors="replace",
    ):
        failures.append("bundle verification output is not PASS")

    chain_verify = physical / "device-chain-verification.txt"
    if not chain_verify.is_file():
        failures.append("device-chain-verification.txt missing")
    elif "POCKETPC_DEVICE_CHAIN_OK" not in chain_verify.read_text(
        encoding="utf-8-sig",
        errors="replace",
    ):
        failures.append("device chain verification output is not PASS")

    if not sidecar_path.is_file():
        failures.append("physical-validation-record SHA-256 sidecar missing")
    else:
        tokens = sidecar_path.read_text(
            encoding="ascii",
            errors="strict",
        ).strip().split()
        actual = sha256_file(record_path)
        if not tokens or tokens[0].lower() != actual:
            failures.append("physical-validation-record sidecar mismatch")

    if failures:
        print("PHYSICAL_VALIDATION_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("PHYSICAL_VALIDATION_RECORD_OK")
    print(f"source_commit={commit}")
    print(f"bundle_sha256={record.get('bundleSha256')}")
    print(f"proot_ready={record.get('prootReady')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
