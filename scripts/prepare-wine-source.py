#!/usr/bin/env python3
"""Prepare the pinned Wine 11.0 source and a reproducible Win64 build plan.

This does not claim that Wine runs under Box64. It verifies the exact source,
license evidence, and emits the configure/build commands for a first headless
Win64 smoke candidate.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/wine/LOCK.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def run(argv: list[str], cwd: Path) -> None:
    result = subprocess.run(argv, cwd=cwd, check=False)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(argv)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    work = args.work.resolve()
    if work == ROOT.resolve() or ROOT.resolve() in work.parents:
        raise SystemExit("WINE_WORK_MUST_BE_OUTSIDE_REPOSITORY")
    work.mkdir(parents=True, exist_ok=False)
    source = work / "source"

    run(["git", "init", str(source)], work)
    run(["git", "-C", str(source), "remote", "add", "origin", lock["repository"]], work)
    run(["git", "-C", str(source), "fetch", "--depth", "1", "origin", lock["commit"]], work)
    run(["git", "-C", str(source), "checkout", "--detach", "FETCH_HEAD"], work)

    actual = subprocess.check_output(
        ["git", "-C", str(source), "rev-parse", "HEAD"], text=True
    ).strip()
    if actual != lock["commit"]:
        raise SystemExit("WINE_SOURCE_COMMIT_MISMATCH")

    copying = (source / "COPYING.LIB").read_text(encoding="utf-8", errors="replace")
    if "GNU LESSER GENERAL PUBLIC LICENSE" not in copying or "Version 2.1" not in copying:
        raise SystemExit("WINE_LICENSE_EVIDENCE_MISMATCH")

    configure = [
        str(source / "configure"),
        "--enable-win64",
        "--without-x",
        "--without-wayland",
        "--without-alsa",
        "--without-pulse",
        "--without-dbus",
        "--without-cups",
        "--without-fontconfig",
        "--without-freetype",
        "--without-gphoto",
        "--without-gstreamer",
        "--without-oss",
        "--without-pcap",
        "--without-sane",
        "--without-usb",
        "--without-v4l2",
        "--without-opencl",
        "--without-opengl",
        "--without-vulkan",
    ]
    plan = {
        "schemaVersion": 1,
        "status": "SOURCE_VERIFIED_BUILD_PLAN_NOT_EXECUTED",
        "version": lock["version"],
        "commit": actual,
        "sourceLockSha256": sha256(LOCK_PATH),
        "sourceLicense": lock["license"],
        "purpose": "First Win64 loader/process smoke candidate under Box64; not final graphics runtime.",
        "configure": configure,
        "build": ["make", "-j2"],
        "install": ["make", "install", "DESTDIR=<staging>"],
        "notExecuted": [
            "Wine configure",
            "Wine build",
            "Wine install staging",
            "Box64 + Wine execution",
            "Windows smoke test",
            "DXVK/Vulkan",
            "Roblox",
        ],
    }
    (work / "wine-build-plan.json").write_text(
        json.dumps(plan, indent=2) + "\n", encoding="utf-8"
    )
    print("WINE_SOURCE_VERIFIED_BUILD_PLAN_READY_NOT_EXECUTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
