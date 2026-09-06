#!/usr/bin/env python3
"""Verify PocketPC Windows preflight record."""

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
    parser.add_argument("record", type=pathlib.Path)
    parser.add_argument("--require-device", action="store_true")
    args = parser.parse_args()

    record_path = args.record.resolve()
    sidecar_path = pathlib.Path(str(record_path) + ".sha256")
    failures: list[str] = []

    if not record_path.is_file():
        print("PREFLIGHT_RECORD_FAILED\n- record missing", file=sys.stderr)
        return 1

    try:
        record = json.loads(record_path.read_text(encoding="utf-8-sig"))
    except Exception as error:
        print(f"PREFLIGHT_RECORD_FAILED\n- invalid JSON: {error}", file=sys.stderr)
        return 1

    if record.get("schemaVersion") != 1:
        failures.append("unsupported schemaVersion")

    checks = record.get("checks")
    if not isinstance(checks, list):
        failures.append("checks must be an array")
        checks = []

    ids: set[str] = set()
    counts = {"PASS": 0, "WARN": 0, "FAIL": 0}
    by_id: dict[str, str] = {}

    for item in checks:
        if not isinstance(item, dict):
            failures.append("check entry must be an object")
            continue
        check_id = item.get("id")
        status = item.get("status")
        if not isinstance(check_id, str) or not check_id:
            failures.append("check id invalid")
            continue
        if check_id in ids:
            failures.append(f"duplicate check id: {check_id}")
        ids.add(check_id)

        if status not in counts:
            failures.append(f"invalid check status for {check_id}: {status}")
            continue
        counts[status] += 1
        by_id[check_id] = status

    if record.get("passCount") != counts["PASS"]:
        failures.append("passCount mismatch")
    if record.get("warnCount") != counts["WARN"]:
        failures.append("warnCount mismatch")
    if record.get("failCount") != counts["FAIL"]:
        failures.append("failCount mismatch")

    expected_class = (
        "PREFLIGHT_FAIL"
        if counts["FAIL"] > 0
        else "PREFLIGHT_PASS_WITH_WARNINGS"
        if counts["WARN"] > 0
        else "PREFLIGHT_PASS"
    )
    if record.get("classification") != expected_class:
        failures.append(
            f"classification mismatch expected={expected_class}"
        )

    core_required = (
        "toolchain-lock",
        "git",
        "git-head",
        "git-clean",
        "python3",
        "java",
        "android-sdk",
        "adb",
    )
    for check_id in core_required:
        if check_id not in by_id:
            failures.append(f"required check missing: {check_id}")
        elif by_id[check_id] == "FAIL":
            failures.append(f"required check failed: {check_id}")

    context = record.get("context")
    if not isinstance(context, dict):
        failures.append("context must be an object")
        context = {}

    commit = str(context.get("sourceCommit", "")).lower()
    if not GIT40.fullmatch(commit):
        failures.append("context.sourceCommit invalid")

    if args.require_device:
        for check_id in (
            "adb-authorization",
            "physical-device",
            "device-abi",
            "device-api",
        ):
            if by_id.get(check_id) != "PASS":
                failures.append(
                    f"required physical-device check is not PASS: {check_id}"
                )

        serial_hash = str(
            context.get("deviceSerialSha256", "")
        ).lower()
        if not HEX64.fullmatch(serial_hash):
            failures.append("deviceSerialSha256 invalid/missing")

    if not sidecar_path.is_file():
        failures.append("preflight SHA-256 sidecar missing")
    else:
        tokens = sidecar_path.read_text(
            encoding="ascii",
            errors="strict",
        ).strip().split()
        actual = sha256_file(record_path)
        if not tokens or tokens[0].lower() != actual:
            failures.append("preflight SHA-256 sidecar mismatch")

    if failures:
        print("PREFLIGHT_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("PREFLIGHT_RECORD_OK")
    print(f"classification={record.get('classification')}")
    print(f"source_commit={commit}")
    print(f"pass={counts['PASS']} warn={counts['WARN']} fail={counts['FAIL']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
