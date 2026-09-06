#!/usr/bin/env python3
"""Deterministic self-test for the PRoot ELF artifact policy."""

from __future__ import annotations

import json
import pathlib
import struct
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
AUDITOR = ROOT / "scripts" / "audit-proot-artifacts.py"


def minimal_elf(path: pathlib.Path, machine: int = 183) -> None:
    ident = bytearray(16)
    ident[0:4] = b"\x7fELF"
    ident[4] = 2  # ELFCLASS64
    ident[5] = 1  # little endian
    ident[6] = 1  # version

    header = bytes(ident) + struct.pack(
        "<HHIQQQIHHHHHH",
        2,       # ET_EXEC
        machine,
        1,       # EV_CURRENT
        0,       # entry
        0,       # phoff
        0,       # shoff
        0,       # flags
        64,      # ehsize
        0, 0,    # phentsize/phnum
        0, 0, 0, # shentsize/shnum/shstrndx
    )
    path.write_bytes(header)


def run_audit(directory: pathlib.Path, report: pathlib.Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(AUDITOR),
            "--artifact-dir",
            str(directory),
            "--report",
            str(report),
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-elf-policy-") as temp:
        root = pathlib.Path(temp)
        artifacts = root / "artifacts"
        artifacts.mkdir()

        minimal_elf(artifacts / "proot")
        minimal_elf(artifacts / "loader")
        minimal_elf(artifacts / "libandroid-shmem.so")
        minimal_elf(artifacts / "libtalloc.so.2")

        good_report = root / "good.json"
        good = run_audit(artifacts, good_report)
        if good.returncode != 0:
            print(good.stdout, file=sys.stderr)
            print(good.stderr, file=sys.stderr)
            raise SystemExit("valid synthetic AArch64 artifacts did not pass")

        parsed = json.loads(good_report.read_text(encoding="utf-8"))
        if parsed["status"] not in {
            "ELF_VALID_REVIEW_REQUIRED",
            "ELF_VALID_DEPENDENCIES_REVIEW_REQUIRED",
        }:
            raise SystemExit(f"unexpected good status: {parsed['status']}")

        # Replace loader with an x86_64 ELF and require fail-closed behavior.
        minimal_elf(artifacts / "loader", machine=62)
        bad_report = root / "bad.json"
        bad = run_audit(artifacts, bad_report)
        if bad.returncode == 0:
            raise SystemExit("x86_64 loader unexpectedly passed AArch64 contract")

        bad_parsed = json.loads(bad_report.read_text(encoding="utf-8"))
        if not any("machine" in failure for failure in bad_parsed["failures"]):
            raise SystemExit("machine mismatch was not reported")

    print("PROOT_ELF_POLICY_SELFTEST_OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
