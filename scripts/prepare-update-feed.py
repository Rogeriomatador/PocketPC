#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "toolchains" / "android-build-lock.json"
DEFAULT_OUTPUT = ROOT / "updates" / "stable.json"

REVISION_RE = re.compile(r"^[0-9a-fA-F]{40}$")
VERSION_RE = re.compile(r"^[A-Za-z0-9._+-]{1,96}$")
CHANNEL_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,31}$")


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
            "apk URL must be an absolute HTTPS URL"
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
            "This does not upload or sign the APK."
        )
    )
    parser.add_argument("--apk", required=True, type=pathlib.Path)
    parser.add_argument("--apk-url", required=True, type=https_url)
    parser.add_argument("--source-revision", required=True, type=revision)
    parser.add_argument("--notes", default="")
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--version-code", type=positive_version_code)
    parser.add_argument("--version-name", type=version_name)
    parser.add_argument("--channel", type=channel, default="stable")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    apk = args.apk.resolve()
    if not apk.is_file():
        print(f"UPDATE_FEED_PREPARE_FAILED\n- APK not found: {apk}", file=sys.stderr)
        return 1
    if apk.stat().st_size <= 0:
        print("UPDATE_FEED_PREPARE_FAILED\n- APK is empty", file=sys.stderr)
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

    digest = sha256_file(apk)
    feed = {
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
    print(f"output={args.output}")
    if not args.publish:
        print(
            "classification=UNPUBLISHED_FAIL_CLOSED "
            "(URL/hash intentionally omitted from feed)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
