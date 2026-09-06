#!/usr/bin/env python3
"""Audit quarantined PRoot ELF artifacts before Android packaging.

The audit is intentionally fail-closed and never copies files into app/src.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import shutil
import struct
import subprocess
import sys
from typing import Any

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "third_party" / "proot" / "ARTIFACT_CONTRACT.json"

NEEDED_RE = re.compile(r"\(NEEDED\).*Shared library: \[(.+?)\]")
SONAME_RE = re.compile(r"\(SONAME\).*Library soname: \[(.+?)\]")
PATH_RE = re.compile(r"\((RPATH|RUNPATH)\).*Library (?:rpath|runpath): \[(.*?)\]")


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def elf_header(path: pathlib.Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        raw = handle.read(64)
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")

    elf_class = raw[4]
    endian = raw[5]
    if endian not in (1, 2):
        raise ValueError(f"unsupported ELF endianness: {endian}")
    order = "<" if endian == 1 else ">"

    elf_type, machine = struct.unpack_from(order + "HH", raw, 16)
    return {
        "class": elf_class,
        "endianness": endian,
        "type": elf_type,
        "machine": machine,
    }


def readelf_tool() -> str:
    for candidate in ("llvm-readelf", "readelf"):
        found = shutil.which(candidate)
        if found:
            return found
    raise RuntimeError("llvm-readelf/readelf not found")


def dynamic_info(path: pathlib.Path, tool: str) -> dict[str, Any]:
    completed = subprocess.run(
        [tool, "-dW", str(path)],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"readelf failed for {path.name}: {completed.stderr.strip()}"
        )

    needed: list[str] = []
    soname: str | None = None
    rpath: str | None = None
    runpath: str | None = None

    for line in completed.stdout.splitlines():
        match = NEEDED_RE.search(line)
        if match:
            needed.append(match.group(1))
            continue

        match = SONAME_RE.search(line)
        if match:
            soname = match.group(1)
            continue

        match = PATH_RE.search(line)
        if match:
            if match.group(1) == "RPATH":
                rpath = match.group(2)
            else:
                runpath = match.group(2)

    return {
        "needed": sorted(set(needed)),
        "soname": soname,
        "rpath": rpath,
        "runpath": runpath,
    }


def resolve_roles(
    artifact_dir: pathlib.Path,
    contract: dict[str, Any],
) -> tuple[dict[str, pathlib.Path], list[str]]:
    failures: list[str] = []
    roles: dict[str, pathlib.Path] = {}

    for role in contract["requiredRoles"]:
        path = artifact_dir / role["sourceBasename"]
        if not path.exists():
            failures.append(f"{role['id']}: missing {role['sourceBasename']}")
            continue
        roles[role["id"]] = path

    for role in contract.get("discoveredRoles", []):
        matches = sorted(artifact_dir.glob(role["glob"]))
        matches = [path for path in matches if path.is_file() and not path.is_symlink()]
        if len(matches) != 1:
            failures.append(
                f"{role['id']}: expected exactly one regular match for "
                f"{role['glob']!r}, found {[p.name for p in matches]}"
            )
            continue
        roles[role["id"]] = matches[0]

    return roles, failures


def audit(args: argparse.Namespace) -> int:
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    artifact_dir = args.artifact_dir.resolve()

    if contract.get("schemaVersion") != 1:
        raise SystemExit("unsupported artifact contract schema")
    if not artifact_dir.is_dir():
        raise SystemExit(f"artifact directory does not exist: {artifact_dir}")

    roles, failures = resolve_roles(artifact_dir, contract)
    tool = readelf_tool()
    policy = contract["elfPolicy"]
    target = contract["target"]
    report: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "REVIEW_REQUIRED",
        "contractStatus": contract["status"],
        "artifactDirectory": str(artifact_dir),
        "readelf": tool,
        "artifacts": {},
        "failures": failures,
        "unreviewedNeeded": [],
    }

    system_needed = set(policy.get("androidSystemNeeded", []))
    forbidden_needed = set(policy.get("forbiddenNeeded", []))
    unreviewed_needed: set[str] = set()

    for role_id, path in roles.items():
        if path.is_symlink():
            failures.append(f"{role_id}: quarantine artifact must not be a symlink")
            continue
        if not path.is_file():
            failures.append(f"{role_id}: not a regular file")
            continue

        try:
            header = elf_header(path)
            dynamic = dynamic_info(path, tool)
        except Exception as error:
            failures.append(f"{role_id}: {error}")
            continue

        if header["class"] != target["elfClass"]:
            failures.append(
                f"{role_id}: ELF class {header['class']} != {target['elfClass']}"
            )
        if header["endianness"] != target["endianness"]:
            failures.append(
                f"{role_id}: endian {header['endianness']} != {target['endianness']}"
            )
        if header["machine"] != target["machine"]:
            failures.append(
                f"{role_id}: machine {header['machine']} != {target['machine']}"
            )
        if header["type"] not in policy["allowTypes"]:
            failures.append(f"{role_id}: forbidden ELF type {header['type']}")

        if policy.get("forbidRpath") and dynamic["rpath"] is not None:
            failures.append(f"{role_id}: RPATH is forbidden: {dynamic['rpath']}")
        if policy.get("forbidRunpath") and dynamic["runpath"] is not None:
            failures.append(f"{role_id}: RUNPATH is forbidden: {dynamic['runpath']}")

        for needed in dynamic["needed"]:
            if needed in forbidden_needed:
                failures.append(f"{role_id}: forbidden DT_NEEDED {needed}")
            elif needed not in system_needed:
                unreviewed_needed.add(needed)

        report["artifacts"][role_id] = {
            "fileName": path.name,
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
            "elf": header,
            "dynamic": dynamic,
        }

    report["unreviewedNeeded"] = sorted(unreviewed_needed)
    report["failures"] = failures

    if failures:
        report["status"] = "FAILED"
    elif unreviewed_needed:
        report["status"] = "ELF_VALID_DEPENDENCIES_REVIEW_REQUIRED"
    else:
        report["status"] = "ELF_VALID_REVIEW_REQUIRED"

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    print(json.dumps(report, indent=2))
    if failures:
        return 1

    print(
        "\nELF structural audit passed. Packaging is still NOT approved; "
        "human dependency/license/device review is required."
    )
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--artifact-dir",
        type=pathlib.Path,
        required=True,
        help="Quarantine directory containing raw produced artifacts.",
    )
    parser.add_argument(
        "--contract",
        type=pathlib.Path,
        default=DEFAULT_CONTRACT,
    )
    parser.add_argument(
        "--report",
        type=pathlib.Path,
        required=True,
    )
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(audit(parse_args()))
