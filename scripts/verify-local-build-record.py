#!/usr/bin/env python3
"""Verify PocketPC local Windows build output.

This validates the build record, APK bytes/hash sidecar, source identity and
the signing-certificate hashes captured by apksigner.
"""

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
CERT_RE = re.compile(
    r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})",
    re.IGNORECASE,
)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("build_dir", type=pathlib.Path)
    parser.add_argument("--expected-commit")
    args = parser.parse_args()

    root = args.build_dir.resolve()
    failures: list[str] = []

    try:
        build_lock = json.loads(BUILD_LOCK.read_text(encoding="utf-8"))
    except Exception as error:
        print(f"LOCAL_BUILD_RECORD_FAILED\n- invalid build lock: {error}", file=sys.stderr)
        return 1

    expected_app = build_lock.get("app", {})

    record_path = root / "local-build-record.json"
    signing_path = root / "apk-signing.txt"

    if not record_path.is_file():
        print("LOCAL_BUILD_RECORD_FAILED\n- local-build-record.json missing", file=sys.stderr)
        return 1

    try:
        record = json.loads(record_path.read_text(encoding="utf-8-sig"))
    except Exception as error:
        print(f"LOCAL_BUILD_RECORD_FAILED\n- invalid build record: {error}", file=sys.stderr)
        return 1

    if record.get("schemaVersion") != 1:
        failures.append("unsupported build-record schemaVersion")

    source = record.get("source")
    app = record.get("app")
    checks = record.get("checks")
    apk = record.get("apk")

    if not isinstance(source, dict):
        failures.append("source object missing")
        source = {}
    if not isinstance(app, dict):
        failures.append("app object missing")
        app = {}
    if not isinstance(checks, dict):
        failures.append("checks object missing")
        checks = {}
    if not isinstance(apk, dict):
        failures.append("apk object missing")
        apk = {}

    commit = str(source.get("commit", "")).lower()
    dirty = source.get("dirty")
    embedded = str(source.get("embeddedRevision", ""))

    if not GIT40.fullmatch(commit):
        failures.append("source.commit is not a 40-char Git SHA")

    if dirty is False:
        if embedded.lower() != commit:
            failures.append("clean build embeddedRevision must equal source commit")
    elif dirty is True:
        if embedded != "LOCAL_UNPINNED":
            failures.append("dirty build must embed LOCAL_UNPINNED")
    else:
        failures.append("source.dirty must be boolean")

    if args.expected_commit and commit != args.expected_commit.lower():
        failures.append(
            f"source commit mismatch expected={args.expected_commit} actual={commit}"
        )

    if app.get("packageName") != expected_app.get("packageName"):
        failures.append("unexpected packageName")
    if app.get("versionName") != expected_app.get("versionName"):
        failures.append("unexpected versionName")
    if app.get("versionCode") != expected_app.get("versionCode"):
        failures.append("unexpected versionCode")

    required_pass = (
        "unitTests",
        "lint",
        "assembleDebug",
        "apkStructure",
        "unapprovedSubstrateRejected",
    )
    for key in required_pass:
        if checks.get(key) != "PASS":
            failures.append(f"check is not PASS: {key}")

    policy_state = checks.get("pythonPolicyChecks")
    if policy_state not in ("PASS", "SKIPPED_NO_PYTHON"):
        failures.append(f"unexpected pythonPolicyChecks state: {policy_state!r}")

    file_name = apk.get("fileName")
    if not isinstance(file_name, str) or pathlib.PurePath(file_name).name != file_name:
        failures.append("unsafe/invalid APK fileName")
        apk_path = None
    else:
        apk_path = root / file_name

    if apk_path is not None:
        if not apk_path.is_file():
            failures.append(f"APK missing: {file_name}")
        else:
            actual_bytes = apk_path.stat().st_size
            actual_sha = sha256_file(apk_path)
            if apk.get("bytes") != actual_bytes:
                failures.append("APK byte length mismatch")
            expected_sha = str(apk.get("sha256", "")).lower()
            if not HEX64.fullmatch(expected_sha):
                failures.append("APK SHA-256 invalid in record")
            elif expected_sha != actual_sha:
                failures.append("APK SHA-256 mismatch")

            sidecar = root / f"{file_name}.sha256"
            if not sidecar.is_file():
                failures.append("APK SHA-256 sidecar missing")
            else:
                side_hash = sidecar.read_text(
                    encoding="ascii",
                    errors="strict",
                ).strip().split()[0]
                if side_hash.lower() != actual_sha:
                    failures.append("APK SHA-256 sidecar mismatch")

    certs = apk.get("signingCertificateSha256")
    if not isinstance(certs, list) or not certs:
        failures.append("signingCertificateSha256 missing/empty")
        cert_set: set[str] = set()
    else:
        cert_set = set()
        for cert in certs:
            value = str(cert).lower()
            if not HEX64.fullmatch(value):
                failures.append("invalid signing-certificate SHA-256 in record")
            else:
                cert_set.add(value)

    if not signing_path.is_file():
        failures.append("apk-signing.txt missing")
    else:
        signing_text = signing_path.read_text(
            encoding="utf-8-sig",
            errors="replace",
        )
        captured = {
            match.group(1).lower()
            for match in CERT_RE.finditer(signing_text)
        }
        if not captured:
            failures.append("apk-signing.txt contains no certificate SHA-256")
        elif captured != cert_set:
            failures.append("signing certificate set differs from build record")

    classification = record.get("classification")
    if dirty is True and classification != "LOCAL_BUILD_DIRTY_UNPINNED":
        failures.append("dirty build classification mismatch")
    if dirty is False:
        if policy_state == "PASS":
            expected_class = "LOCAL_BUILD_POLICY_CHECKED"
        else:
            expected_class = "LOCAL_BUILD_PYTHON_POLICY_SKIPPED"
        if classification != expected_class:
            failures.append(
                f"clean build classification mismatch expected={expected_class}"
            )

    if failures:
        print("LOCAL_BUILD_RECORD_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("LOCAL_BUILD_RECORD_OK")
    print(f"classification={classification}")
    print(f"source_commit={commit}")
    print(f"apk_sha256={apk.get('sha256')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
