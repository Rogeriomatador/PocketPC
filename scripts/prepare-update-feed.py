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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Render PocketPC stable update feed from a real APK. "
            "This does not upload or sign the APK."
        )
    )
    parser.add_argument(
        "--apk",
        required=True,
        type=pathlib.Path,
        help="Path to the already-built APK",
    )
    parser.add_argument(
        "--apk-url",
        required=True,
        type=https_url,
        help="Final HTTPS URL from which the app can download the APK",
    )
    parser.add_argument(
        "--source-revision",
        required=True,
        type=revision,
        help="Exact 40-hex Git revision embedded in the APK",
    )
    parser.add_argument(
        "--notes",
        default="",
        help="Release notes exposed in the PocketPC Update Center",
    )
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        default=DEFAULT_OUTPUT,
        help="Feed output path",
    )
    parser.add_argument(
        "--publish",
        action="store_true",
        help=(
            "Set published=true. Without this flag the generated feed "
            "remains fail-closed."
        ),
    )
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
        version_name = str(app["versionName"])
        version_code = int(app["versionCode"])
    except Exception as error:
        print(
            f"UPDATE_FEED_PREPARE_FAILED\n- invalid build lock: {error}",
            file=sys.stderr,
        )
        return 1

    digest = sha256_file(apk)

    feed = {
        "schemaVersion": 1,
        "channel": "stable",
        "published": bool(args.publish),
        "versionCode": version_code,
        "versionName": version_name,
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
    print(f"version={version_name}")
    print(f"version_code={version_code}")
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
