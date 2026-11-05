#!/usr/bin/env python3
"""Audit license evidence from the exact sources used by the PRoot NDK build."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/proot/LOCK.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def component_root(path: Path) -> Path:
    if not path.is_dir() or path.is_symlink():
        raise SystemExit("LICENSE_AUDIT_COMPONENT_ROOT_MISSING:" + str(path))
    children = [child for child in path.iterdir() if child.is_dir() and not child.is_symlink()]
    files = [child for child in path.iterdir() if child.is_file()]
    if files:
        return path
    if len(children) == 1:
        return children[0]
    raise SystemExit("LICENSE_AUDIT_COMPONENT_ROOT_AMBIGUOUS:" + str(path))


def require_text(path: Path, sentinels: tuple[str, ...]) -> dict[str, object]:
    if not path.is_file() or path.is_symlink():
        raise SystemExit("LICENSE_AUDIT_FILE_MISSING:" + str(path))
    text = path.read_text(encoding="utf-8", errors="replace")
    for sentinel in sentinels:
        if sentinel not in text:
            raise SystemExit(
                "LICENSE_AUDIT_SENTINEL_MISSING:" +
                path.name +
                ":" +
                sentinel
            )
    return {
        "path": path.name,
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    source_root = args.source_root.resolve()
    out = args.out.resolve()
    if not source_root.is_dir():
        raise SystemExit("LICENSE_AUDIT_SOURCE_ROOT_MISSING")

    proot = component_root(source_root / "proot")
    shmem = component_root(source_root / "libandroid-shmem")
    talloc = component_root(source_root / "libtalloc")

    evidence = {
        "proot": require_text(
            proot / "COPYING",
            (
                "GNU GENERAL PUBLIC LICENSE",
                "Version 2, June 1991",
            ),
        ),
        "libandroid-shmem": require_text(
            shmem / "LICENSE",
            (
                "Redistribution and use in source and binary forms",
                "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS",
            ),
        ),
        "libtalloc-header": require_text(
            talloc / "talloc.h",
            (
                "following LGPL license applies to the talloc",
                "GNU Lesser General Public",
                "version 3 of the License",
                "any later version",
            ),
        ),
        "libtalloc-source": require_text(
            talloc / "talloc.c",
            (
                "following LGPL license applies to the talloc",
                "GNU Lesser General Public",
                "version 3 of the License",
                "any later version",
            ),
        ),
    }

    report = {
        "schemaVersion": 1,
        "status": "SOURCE_LICENSE_AUDIT_PASS",
        "sourceLockSha256": sha256(LOCK),
        "licenses": {
            "proot": "GPL-2.0",
            "libandroid-shmem": "BSD-3-Clause",
            "libtalloc-library": "LGPL-3.0-or-later",
        },
        "evidence": evidence,
        "scope": (
            "Exact extracted source trees used by the NDK build. "
            "This audit identifies license notices; redistribution still "
            "requires the corresponding source/build materials to accompany "
            "the candidate bundle."
        ),
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PROOT_SOURCE_LICENSE_AUDIT_PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
