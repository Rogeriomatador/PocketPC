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
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("experimental runtime ZIP contains duplicate paths")

        manifest, _ = read_zip_json(archive, "guest-tool-manifest.json")
        capability, capability_payload = read_zip_json(
            archive,
            V52_CAPABILITY_PATH,
        )

        if manifest.get("id") != "wine":
            raise ValueError("experimental runtime manifest id must be wine")
        if manifest.get("architecture") != "x86_64":
            raise ValueError("experimental runtime architecture must be x86_64")

        files = manifest.get("files")
        if not isinstance(files, list):
            raise ValueError("experimental runtime manifest files must be a list")
        matching = [
            item
            for item in files
            if isinstance(item, dict)
            and item.get("path") == V52_CAPABILITY_PATH
        ]
        if len(matching) != 1:
            raise ValueError(
                "experimental runtime capability must appear exactly once in manifest"
            )
        manifest_capability = matching[0]
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
