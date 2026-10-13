#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import zipfile
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "toolchains" / "android-build-lock.json"
DEFAULT_OUTPUT = ROOT / "updates" / "stable.json"

REVISION_RE = re.compile(r"^[0-9a-fA-F]{40}$")
VERSION_RE = re.compile(r"^[A-Za-z0-9._+-]{1,96}$")
CHANNEL_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,31}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
V52_CAPABILITY = "pocketpc.vulkan.continuous-present.v52"
V52_CAPABILITY_PATH = "share/pocketpc/runtime-graphics-capabilities.json"
MAX_EMBEDDED_JSON_BYTES = 16 * 1024 * 1024
MAX_GUEST_FILE_COUNT = 100_000
MAX_GUEST_PATH_CHARS = 4096


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def https_url(value: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.netloc:
        raise argparse.ArgumentTypeError(
            "artifact URL must be an absolute HTTPS URL"
        )
    return value


def revision(value: str) -> str:
    if not REVISION_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(
            "source revision must be exactly 40 hexadecimal characters"
        )
    return value.lower()


def positive_version_code(value: str) -> int:
    parsed = int(value)
    if parsed <= 0 or parsed > 2_100_000_000:
        raise argparse.ArgumentTypeError("version code out of range")
    return parsed


def version_name(value: str) -> str:
    if not VERSION_RE.fullmatch(value):
        raise argparse.ArgumentTypeError("invalid version name")
    return value


def channel(value: str) -> str:
    if not CHANNEL_RE.fullmatch(value):
        raise argparse.ArgumentTypeError("invalid update channel")
    return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Render a PocketPC update feed from a real APK. "
            "An optional experimental Wine v52 guest package can be bound "
            "to the exact same PocketPC source revision. This does not upload "
            "or sign either artifact."
        )
    )
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    parser.add_argument("--apk-url", required=True, type=https_url)
    parser.add_argument("--source-revision", required=True, type=revision)
    parser.add_argument("--experimental-runtime-zip", type=pathlib.Path)
    parser.add_argument("--experimental-runtime-url", type=https_url)
    parser.add_argument("--notes", default="")
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--version-code", type=positive_version_code)
    parser.add_argument("--version-name", type=version_name)
    parser.add_argument("--channel", type=channel, default="stable")
    return parser.parse_args()


def read_zip_json(
    archive: zipfile.ZipFile,
    path: str,
) -> tuple[dict[str, object], bytes]:
    try:
        info = archive.getinfo(path)
    except KeyError as error:
        raise ValueError(f"missing {path}") from error
    if info.is_dir() or info.file_size <= 0 or info.file_size > MAX_EMBEDDED_JSON_BYTES:
        raise ValueError(f"invalid {path} size")
    payload = archive.read(info)
    try:
        parsed = json.loads(payload.decode("utf-8"))
    except Exception as error:
        raise ValueError(f"invalid JSON in {path}: {error}") from error
    if not isinstance(parsed, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return parsed, payload


def validate_guest_path(value: object) -> str:
    if not isinstance(value, str) or not value or len(value) > MAX_GUEST_PATH_CHARS:
        raise ValueError("experimental runtime manifest contains invalid file path")
    if "\\" in value or value.startswith("/"):
        raise ValueError(f"experimental runtime manifest path is unsafe: {value!r}")
    path = pathlib.PurePosixPath(value)
    if value != path.as_posix() or any(part in ("", ".", "..") for part in path.parts):
        raise ValueError(f"experimental runtime manifest path is unsafe: {value!r}")
    return value


def verify_manifest_files(
    archive: zipfile.ZipFile,
    manifest: dict[str, object],
) -> dict[str, dict[str, object]]:
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise ValueError("experimental runtime manifest files must be a non-empty list")
    if len(files) > MAX_GUEST_FILE_COUNT:
        raise ValueError("experimental runtime manifest has too many files")

    records: dict[str, dict[str, object]] = {}
    for raw in files:
        if not isinstance(raw, dict):
            raise ValueError("experimental runtime manifest file record is invalid")
        path = validate_guest_path(raw.get("path"))
        if path == "guest-tool-manifest.json" or path in records:
            raise ValueError(f"experimental runtime manifest path is duplicated: {path}")
        expected_sha = raw.get("sha256")
        expected_bytes = raw.get("bytes")
        executable = raw.get("executable")
        if not isinstance(expected_sha, str) or not SHA256_RE.fullmatch(expected_sha):
            raise ValueError(f"experimental runtime manifest SHA-256 invalid: {path}")
        if not isinstance(expected_bytes, int) or isinstance(expected_bytes, bool) or expected_bytes < 0:
            raise ValueError(f"experimental runtime manifest byte size invalid: {path}")
        if not isinstance(executable, bool):
            raise ValueError(f"experimental runtime executable flag invalid: {path}")
        records[path] = raw

    archive_names = archive.namelist()
    if len(archive_names) != len(set(archive_names)):
        raise ValueError("experimental runtime ZIP contains duplicate paths")
    expected_names = {"guest-tool-manifest.json", *records.keys()}
    actual_names = set(archive_names)
    if actual_names != expected_names:
        missing = sorted(expected_names - actual_names)[:8]
        extra = sorted(actual_names - expected_names)[:8]
        raise ValueError(
            "experimental runtime ZIP/manifest file set mismatch "
            f"missing={missing} extra={extra}"
        )

    for path, record in records.items():
        info = archive.getinfo(path)
        if info.is_dir():
            raise ValueError(f"experimental runtime file is a directory: {path}")
        if info.file_size != record["bytes"]:
            raise ValueError(f"experimental runtime file byte size mismatch: {path}")
        digest = hashlib.sha256()
        with archive.open(info, "r") as stream:
            while True:
                chunk = stream.read(1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
        if digest.hexdigest() != record["sha256"]:
            raise ValueError(f"experimental runtime file SHA-256 mismatch: {path}")

    return records


def verify_v52_runtime_package(
    package_path: pathlib.Path,
    source_revision: str,
) -> dict[str, object]:
    package = package_path.resolve()
    if not package.is_file():
        raise ValueError(f"experimental runtime not found: {package}")
    if package.stat().st_size <= 0:
        raise ValueError("experimental runtime is empty")

    with zipfile.ZipFile(package, "r") as archive:
        manifest, _ = read_zip_json(archive, "guest-tool-manifest.json")

        if manifest.get("schemaVersion") != 1:
            raise ValueError("experimental runtime manifest schema must be 1")
        if manifest.get("id") != "wine":
            raise ValueError("experimental runtime manifest id must be wine")
        version = manifest.get("version")
        if not isinstance(version, str) or not version or len(version) > 128:
            raise ValueError("experimental runtime manifest version is invalid")
        if manifest.get("architecture") != "x86_64":
            raise ValueError("experimental runtime architecture must be x86_64")
        if manifest.get("guestRoot") != "/opt/pocketpc/wine":
            raise ValueError("experimental runtime guest root is invalid")
        if manifest.get("entrypoint") != "bin/wine":
            raise ValueError("experimental runtime entrypoint must be bin/wine")
        source_commit = manifest.get("sourceCommit")
        if not isinstance(source_commit, str) or not REVISION_RE.fullmatch(source_commit):
            raise ValueError("experimental runtime Wine source commit is invalid")
        if manifest.get("license") != "LGPL-2.1-or-later":
            raise ValueError("experimental runtime license declaration is invalid")
        if manifest.get("executionMode") != "box64-x86_64":
            raise ValueError("experimental runtime execution mode is invalid")

        records = verify_manifest_files(archive, manifest)
        entrypoint = records.get("bin/wine")
        if entrypoint is None or entrypoint.get("executable") is not True:
            raise ValueError("experimental runtime entrypoint is not executable")

        capability, capability_payload = read_zip_json(
            archive,
            V52_CAPABILITY_PATH,
        )
        manifest_capability = records.get(V52_CAPABILITY_PATH)
        if manifest_capability is None:
            raise ValueError(
                "experimental runtime capability must appear exactly once in manifest"
            )
        expected_capability_sha = sha256_bytes(capability_payload)
        if manifest_capability.get("sha256") != expected_capability_sha:
            raise ValueError("experimental runtime capability SHA-256 mismatch")
        if manifest_capability.get("bytes") != len(capability_payload):
            raise ValueError("experimental runtime capability byte size mismatch")
        if manifest_capability.get("executable") is not False:
            raise ValueError("experimental runtime capability must not be executable")

        if capability.get("schemaVersion") != 1:
            raise ValueError("experimental runtime capability schema must be 1")
        if capability.get("wineVulkanAbi") != 52:
            raise ValueError("experimental runtime Wine Vulkan ABI must be 52")
        if capability.get("capabilities") != [V52_CAPABILITY]:
            raise ValueError("experimental runtime capability set is not exact v52")
        if capability.get("pocketPcSourceRevision") != source_revision:
            raise ValueError(
                "experimental runtime PocketPC source revision does not match APK"
            )
        if capability.get("experimental") is not True:
            raise ValueError("experimental runtime must declare experimental=true")
        for key in (
            "officialBuildSelected",
            "runtimeExecuted",
            "integrationExecuted",
            "physicalVisibleFrame",
            "robloxExecuted",
        ):
            if capability.get(key) is not False:
                raise ValueError(
                    f"experimental runtime must remain fail-closed for {key}"
                )

    digest = sha256_file(package)
    if not SHA256_RE.fullmatch(digest):
        raise AssertionError("internal SHA-256 generation failed")
    return {
        "kind": "wine",
        "experimental": True,
        "guestToolVersion": version,
        "pocketPcSourceRevision": source_revision,
        "sha256": digest,
        "bytes": package.stat().st_size,
        "wineVulkanAbi": 52,
        "capabilities": [V52_CAPABILITY],
    }


def main() -> int:
    args = parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        print(f"UPDATE_FEED_PREPARE_FAILED\n- APK not found: {apk}", file=sys.stderr)
        return 1
    if apk.stat().st_size <= 0:
        print("UPDATE_FEED_PREPARE_FAILED\n- APK is empty", file=sys.stderr)
        return 1

    runtime_zip_set = args.experimental_runtime_zip is not None
    runtime_url_set = args.experimental_runtime_url is not None
    if runtime_zip_set != runtime_url_set:
        print(
            "UPDATE_FEED_PREPARE_FAILED\n- experimental runtime ZIP and URL must be supplied together",
            file=sys.stderr,
        )
        return 1

    try:
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        app = lock["app"]
        package_name = str(app["packageName"])
        locked_name = str(app["versionName"])
        locked_code = int(app["versionCode"])
    except Exception as error:
        print(
            f"UPDATE_FEED_PREPARE_FAILED\n- invalid build lock: {error}",
            file=sys.stderr,
        )
        return 1

    selected_name = args.version_name or locked_name
    selected_code = args.version_code or locked_code
    if (args.version_name is None) != (args.version_code is None):
        print(
            "UPDATE_FEED_PREPARE_FAILED\n- version name/code overrides must be supplied together",
            file=sys.stderr,
        )
        return 1

    runtime: dict[str, object] | None = None
    if args.experimental_runtime_zip is not None:
        try:
            runtime = verify_v52_runtime_package(
                args.experimental_runtime_zip,
                args.source_revision,
            )
        except Exception as error:
            print(
                f"UPDATE_FEED_PREPARE_FAILED\n- invalid experimental runtime: {error}",
                file=sys.stderr,
            )
            return 1

    digest = sha256_file(apk)
    feed: dict[str, object] = {
        "schemaVersion": 1,
        "channel": args.channel,
        "published": bool(args.publish),
        "versionCode": selected_code,
        "versionName": selected_name,
        "sourceRevision": args.source_revision if args.publish else "",
        "packageName": package_name,
        "minApi": 26,
        "apkUrl": args.apk_url if args.publish else "",
        "apkSha256": digest if args.publish else "",
        "notes": args.notes,
    }
    if args.publish and runtime is not None:
        runtime["url"] = args.experimental_runtime_url
        feed["experimentalRuntime"] = runtime

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(feed, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print("UPDATE_FEED_PREPARED")
    print(f"published={str(feed['published']).lower()}")
    print(f"channel={feed['channel']}")
    print(f"version={selected_name}")
    print(f"version_code={selected_code}")
    print(f"source_revision={args.source_revision}")
    print(f"apk_sha256={digest}")
    if runtime is not None:
        print("experimental_runtime_verified=true")
        print(f"experimental_runtime_guest_tool_version={runtime['guestToolVersion']}")
        print(f"experimental_runtime_sha256={runtime['sha256']}")
        print(f"experimental_runtime_bytes={runtime['bytes']}")
        print("experimental_runtime_wine_vulkan_abi=52")
        print(f"experimental_runtime_pocketpc_revision={args.source_revision}")
    print(f"output={args.output}")
    if not args.publish:
        print(
            "classification=UNPUBLISHED_FAIL_CLOSED "
            "(URL/hash intentionally omitted from feed)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
