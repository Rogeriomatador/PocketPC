#!/usr/bin/env python3
"""Verify a PocketPC physical-device evidence bundle.

This validates bundle structure and hash relationships. It does not by itself
upgrade evidence to DEVICE TESTED; the exact APK/commit and device context must
still be reviewed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import stat
import sys
import zipfile

HEX64 = re.compile(r"^[0-9a-f]{64}$")
GIT40 = re.compile(r"^[0-9a-fA-F]{40}$")
MANIFEST = "bundle-manifest.json"
MAX_ENTRIES = 64
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_name(name: str) -> bool:
    if not name or name.startswith("/") or "\\" in name:
        return False
    parts = name.split("/")
    return all(part not in ("", ".", "..") for part in parts)


def is_zip_symlink(info: zipfile.ZipInfo) -> bool:
    mode = (info.external_attr >> 16) & 0xFFFF
    return stat.S_ISLNK(mode)


def load_json(
    archive: zipfile.ZipFile,
    path: str,
    failures: list[str],
) -> dict | None:
    try:
        value = json.loads(archive.read(path).decode("utf-8"))
    except Exception as error:
        failures.append(
            f"invalid JSON {path}: {error.__class__.__name__}: {error}"
        )
        return None

    if not isinstance(value, dict):
        failures.append(f"JSON root must be an object: {path}")
        return None
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=pathlib.Path)
    parser.add_argument("--expected-revision")
    args = parser.parse_args()

    failures: list[str] = []

    try:
        bundle_bytes = args.bundle.read_bytes()
    except OSError as error:
        print(
            f"POCKETPC_EVIDENCE_BUNDLE_FAILED\n- read failed: {error}",
            file=sys.stderr,
        )
        return 1

    bundle_sha = sha256(bundle_bytes)

    try:
        archive = zipfile.ZipFile(args.bundle, "r")
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile) as error:
        print(
            f"POCKETPC_EVIDENCE_BUNDLE_FAILED\n- invalid ZIP: {error}",
            file=sys.stderr,
        )
        return 1

    with archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]

        if len(infos) > MAX_ENTRIES:
            failures.append(
                f"ZIP entry count exceeds limit: {len(infos)} > {MAX_ENTRIES}"
            )

        total_uncompressed = sum(info.file_size for info in infos)
        if total_uncompressed > MAX_TOTAL_BYTES:
            failures.append(
                "ZIP uncompressed size exceeds limit: "
                f"{total_uncompressed} > {MAX_TOTAL_BYTES}"
            )

        if len(names) != len(set(names)):
            failures.append("ZIP contains duplicate entry names")

        for info in infos:
            if not safe_name(info.filename):
                failures.append(f"unsafe ZIP entry path: {info.filename}")
            if info.is_dir():
                failures.append(
                    f"directory ZIP entry is not allowed: {info.filename}"
                )
            if is_zip_symlink(info):
                failures.append(
                    f"ZIP symlink entry is not allowed: {info.filename}"
                )
            if info.flag_bits & 0x1:
                failures.append(
                    f"encrypted ZIP entry is not allowed: {info.filename}"
                )
            if info.file_size > MAX_ENTRY_BYTES:
                failures.append(
                    f"ZIP entry exceeds size limit: {info.filename}"
                )

        manifest = None
        if MANIFEST not in names:
            failures.append(f"missing {MANIFEST}")
        else:
            manifest = load_json(archive, MANIFEST, failures)

        entries = manifest.get("entries", []) if manifest else []
        if manifest is not None and manifest.get("schemaVersion") != 1:
            failures.append("unsupported bundle manifest schema")
        if not isinstance(entries, list):
            failures.append("bundle manifest entries must be an array")
            entries = []

        declared: dict[str, dict] = {}
        for item in entries:
            if not isinstance(item, dict):
                failures.append("manifest entry must be an object")
                continue

            path = item.get("path")
            if not isinstance(path, str) or not safe_name(path):
                failures.append(f"invalid manifest path: {path!r}")
                continue
            if path == MANIFEST:
                failures.append("manifest must not hash itself")
                continue
            if path in declared:
                failures.append(f"duplicate manifest path: {path}")
                continue
            declared[path] = item

        actual_payload_names = set(names) - {MANIFEST}
        if set(declared) != actual_payload_names:
            failures.append(
                "manifest entry set does not exactly match ZIP payload entries"
            )

        for path, item in declared.items():
            try:
                data = archive.read(path)
            except KeyError:
                failures.append(f"declared entry missing from ZIP: {path}")
                continue

            if item.get("bytes") != len(data):
                failures.append(f"byte length mismatch: {path}")

            expected_sha = str(item.get("sha256", "")).lower()
            if not HEX64.fullmatch(expected_sha):
                failures.append(f"invalid declared SHA-256: {path}")
            elif sha256(data) != expected_sha:
                failures.append(f"SHA-256 mismatch: {path}")

        required = {
            "evidence/device-evidence.json",
            "evidence/device-evidence.sha256",
            "identity/build-identity.json",
            "bundle-info.json",
            "policy/proot-substrate-approval.json",
            "policy/proot/LOCK.json",
            "policy/proot/ARTIFACT_CONTRACT.json",
        }
        missing = required - actual_payload_names
        if missing:
            failures.append(f"missing required entries: {sorted(missing)}")

        if not missing:
            evidence_bytes = archive.read("evidence/device-evidence.json")

            try:
                sidecar = archive.read(
                    "evidence/device-evidence.sha256"
                ).decode("utf-8").strip()
            except UnicodeDecodeError as error:
                failures.append(f"invalid evidence SHA sidecar UTF-8: {error}")
                sidecar = ""

            sidecar_hash = sidecar.split()[0] if sidecar else ""
            evidence_hash = sha256(evidence_bytes)
            if sidecar_hash != evidence_hash:
                failures.append("device evidence sidecar SHA-256 mismatch")

            bundle_info = load_json(archive, "bundle-info.json", failures)
            identity = load_json(
                archive,
                "identity/build-identity.json",
                failures,
            )

            evidence_json = None
            try:
                parsed = json.loads(evidence_bytes.decode("utf-8"))
                if isinstance(parsed, dict):
                    evidence_json = parsed
                else:
                    failures.append(
                        "device evidence JSON root must be an object"
                    )
            except Exception as error:
                failures.append(
                    "invalid JSON evidence/device-evidence.json: "
                    f"{error.__class__.__name__}: {error}"
                )

            if bundle_info is not None:
                if bundle_info.get("evidenceSha256") != evidence_hash:
                    failures.append("bundle-info evidenceSha256 mismatch")

            if identity is not None and evidence_json is not None:
                evidence_identity = evidence_json.get("buildIdentity", {})
                if not isinstance(evidence_identity, dict):
                    failures.append(
                        "device evidence buildIdentity must be an object"
                    )
                    evidence_identity = {}

                identity_fields = (
                    "packageName",
                    "versionName",
                    "versionCode",
                    "sourceRevision",
                    "sourceRevisionPinned",
                    "debug",
                    "signingCertificateSha256",
                )
                for field in identity_fields:
                    if evidence_identity.get(field) != identity.get(field):
                        failures.append(
                            f"device evidence buildIdentity differs for {field}"
                        )

                if evidence_json.get("pocketPcVersion") != identity.get(
                    "versionName"
                ):
                    failures.append(
                        "device evidence pocketPcVersion differs from "
                        "build identity versionName"
                    )

                evidence_schema = evidence_json.get("schemaVersion")
                version_name = str(identity.get("versionName", ""))
                requires_desktop = (
                    isinstance(evidence_schema, int)
                    and not isinstance(evidence_schema, bool)
                    and evidence_schema >= 4
                ) or version_name.startswith("0.1.0-alpha19")

                if requires_desktop:
                    desktop = evidence_json.get("desktop")
                    if not isinstance(desktop, dict):
                        failures.append(
                            "alpha19 device evidence desktop must be an object"
                        )
                        desktop = {}

                    if desktop.get("orientationLandscape") is not True:
                        failures.append(
                            "alpha19 desktop orientation is not landscape"
                        )

                    for field in (
                        "secondaryDisplayActivities",
                        "freeformWindowManagement",
                        "pcHardwareType",
                    ):
                        if not isinstance(desktop.get(field), bool):
                            failures.append(
                                f"alpha19 desktop {field} must be boolean"
                            )

                    for field in (
                        "screenWidthDp",
                        "screenHeightDp",
                        "externalDisplayCount",
                        "presentationDisplayCount",
                    ):
                        value = desktop.get(field)
                        if (
                            not isinstance(value, int)
                            or isinstance(value, bool)
                            or value < 0
                        ):
                            failures.append(
                                f"alpha19 desktop {field} must be "
                                "a non-negative integer"
                            )

                    peripherals = desktop.get("peripherals")
                    if not isinstance(peripherals, dict):
                        failures.append(
                            "alpha19 desktop peripherals must be an object"
                        )
                        peripherals = {}

                    for field in (
                        "mouseCount",
                        "keyboardCount",
                        "gamepadCount",
                    ):
                        value = peripherals.get(field)
                        if (
                            not isinstance(value, int)
                            or isinstance(value, bool)
                            or value < 0
                        ):
                            failures.append(
                                f"alpha19 peripherals {field} must be "
                                "a non-negative integer"
                            )

                    displays = desktop.get("externalDisplays")
                    if not isinstance(displays, list):
                        failures.append(
                            "alpha19 externalDisplays must be an array"
                        )
                        displays = []

                    count = desktop.get("externalDisplayCount")
                    if (
                        isinstance(count, int)
                        and not isinstance(count, bool)
                        and count != len(displays)
                    ):
                        failures.append(
                            "alpha19 externalDisplayCount differs from "
                            "externalDisplays length"
                        )

                revision = str(identity.get("sourceRevision", ""))
                pinned = identity.get("sourceRevisionPinned")

                if pinned is True and not GIT40.fullmatch(revision):
                    failures.append(
                        "identity says revision is pinned but it is not a "
                        "40-char Git SHA"
                    )
                if pinned is False and revision != "LOCAL_UNPINNED":
                    failures.append(
                        "unpinned build identity must say LOCAL_UNPINNED"
                    )
                if pinned not in (True, False):
                    failures.append("sourceRevisionPinned must be boolean")

                certs = identity.get("signingCertificateSha256")
                if not isinstance(certs, list) or not certs:
                    failures.append(
                        "signingCertificateSha256 must be a non-empty array"
                    )
                else:
                    for cert in certs:
                        if not isinstance(cert, str) or not HEX64.fullmatch(
                            cert.lower()
                        ):
                            failures.append(
                                "invalid signing certificate SHA-256"
                            )

                if bundle_info is not None:
                    if bundle_info.get("sourceRevision") != revision:
                        failures.append(
                            "bundle-info sourceRevision differs from "
                            "build identity"
                        )
                    if bundle_info.get("versionName") != identity.get(
                        "versionName"
                    ):
                        failures.append(
                            "bundle-info versionName differs from build identity"
                        )
                    if bundle_info.get("versionCode") != identity.get(
                        "versionCode"
                    ):
                        failures.append(
                            "bundle-info versionCode differs from build identity"
                        )

                if args.expected_revision:
                    if revision.lower() != args.expected_revision.lower():
                        failures.append(
                            "revision mismatch "
                            f"expected={args.expected_revision} actual={revision}"
                        )

            approval = load_json(
                archive,
                "policy/proot-substrate-approval.json",
                failures,
            )
            if approval is not None:
                if approval.get("approved") is False:
                    if approval.get("status") != "NOT_APPROVED":
                        failures.append(
                            "denied approval has unexpected status"
                        )
                elif approval.get("approved") is not True:
                    failures.append(
                        "approval approved field is not boolean"
                    )

    if failures:
        print("POCKETPC_EVIDENCE_BUNDLE_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("POCKETPC_EVIDENCE_BUNDLE_OK")
    print(f"bundle_sha256={bundle_sha}")
    print(f"path={args.bundle}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
