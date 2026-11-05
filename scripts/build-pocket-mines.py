#!/usr/bin/env python3
"""Build and attest original Pocket Mines as Win64 GDI PE, not a runtime test."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples/windows/pocket-mines.c"
NAME = "pocket-mines-win64.exe"


def inspect_pe64_gui(path: Path) -> dict[str, int]:
    raw = path.read_bytes()
    if len(raw) < 256 or raw[:2] != b"MZ":
        raise ValueError("POCKET_MINES_NOT_PE")
    pe_offset = struct.unpack_from("<I", raw, 0x3C)[0]
    if pe_offset < 64 or pe_offset + 24 + 70 > len(raw):
        raise ValueError("POCKET_MINES_HEADER_TRUNCATED")
    if raw[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        raise ValueError("POCKET_MINES_INVALID_PE_SIGNATURE")
    machine = struct.unpack_from("<H", raw, pe_offset + 4)[0]
    magic = struct.unpack_from("<H", raw, pe_offset + 24)[0]
    subsystem = struct.unpack_from("<H", raw, pe_offset + 24 + 68)[0]
    if (machine, magic, subsystem) != (0x8664, 0x20B, 2):
        raise ValueError(f"POCKET_MINES_WRONG_TARGET:{machine:04x}:{magic:04x}:{subsystem}")
    return {"machine": machine, "optionalMagic": magic, "subsystem": subsystem}


def build(output_dir: Path, compiler: str = "x86_64-w64-mingw32-gcc") -> Path:
    output_dir = output_dir.resolve()
    if output_dir == ROOT or ROOT in output_dir.parents:
        raise ValueError("POCKET_MINES_OUTPUT_INSIDE_REPOSITORY")
    binary = shutil.which(compiler)
    if binary is None:
        raise RuntimeError("MINGW_W64_COMPILER_UNAVAILABLE")
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / NAME
    subprocess.run(
        [binary, "-std=c11", "-O2", "-s", "-Wall", "-Wextra",
         "-Werror", "-mwindows", "-Wl,--no-insert-timestamp",
         "-o", str(target), str(SOURCE), "-lgdi32", "-luser32"],
        check=True, cwd=ROOT,
    )
    metadata = inspect_pe64_gui(target)
    sha256 = hashlib.sha256(target.read_bytes()).hexdigest()
    evidence = {
        "schemaVersion": 1, "file": NAME, "bytes": target.stat().st_size,
        "sha256": sha256, "architecture": "windows-x86_64",
        "graphics": "GDI", "pe": metadata,
        "runtimeOnAndroid": "NOT_EXECUTED",
        "notes": "Win64 PE compiled, no PocketPC/Android runtime claim.",
    }
    (output_dir / "pocket-mines-build-evidence.json").write_text(
        json.dumps(evidence, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    (output_dir / (NAME + ".sha256")).write_text(
        f"{sha256}  {NAME}\n", encoding="utf-8"
    )
    print("POCKET_MINES_WIN64_COMPILED_NOT_DEVICE_TESTED")
    print(f"sha256={sha256}")
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    build(parser.parse_args().output_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
