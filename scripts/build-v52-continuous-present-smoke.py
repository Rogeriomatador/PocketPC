#!/usr/bin/env python3
"""Cross-compile the deterministic Win64 v52 continuous-present smoke.

Compilation evidence is deliberately separate from runtime evidence. The smoke
exercises eight D3D11 Present calls when later run under the experimental v52
Wine guest; this script does not execute Wine or validate frame visibility.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_v52_continuous_present_smoke.c"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def validate_pe64(path: Path) -> None:
    raw = path.read_bytes()
    if len(raw) < 0x40 or raw[:2] != b"MZ":
        raise SystemExit("V52_SMOKE_PE_MZ_INVALID")
    pe_offset = struct.unpack_from("<I", raw, 0x3C)[0]
    if pe_offset + 26 > len(raw) or raw[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        raise SystemExit("V52_SMOKE_PE_SIGNATURE_INVALID")
    machine = struct.unpack_from("<H", raw, pe_offset + 4)[0]
    optional_magic = struct.unpack_from("<H", raw, pe_offset + 24)[0]
    if machine != 0x8664 or optional_magic != 0x20B:
        raise SystemExit(
            f"V52_SMOKE_PE_TARGET_MISMATCH:machine=0x{machine:04x}:optional=0x{optional_magic:04x}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    if not SOURCE.is_file():
        raise SystemExit(f"V52_SMOKE_SOURCE_MISSING:{SOURCE}")

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    exe = output_dir / "pocketpc-v52-continuous-present-smoke.exe"
    log = output_dir / "pocketpc-v52-continuous-present-smoke-build.log"
    evidence_path = output_dir / "pocketpc-v52-continuous-present-smoke-build-evidence.json"

    command = [
        "x86_64-w64-mingw32-gcc",
        "-O2",
        "-s",
        "-Wl,--no-insert-timestamp",
        "-o",
        str(exe),
        str(SOURCE),
        "-ld3d11",
        "-ldxgi",
        "-luuid",
        "-luser32",
    ]
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, text=True)
    if result.returncode != 0:
        raise SystemExit(f"V52_SMOKE_BUILD_FAILED:{result.returncode}:{log}")

    validate_pe64(exe)
    evidence = {
        "schemaVersion": 1,
        "status": "V52_CONTINUOUS_PRESENT_SMOKE_COMPILED_NOT_EXECUTED",
        "source": SOURCE.relative_to(ROOT).as_posix(),
        "sourceSha256": sha256(SOURCE),
        "executable": exe.name,
        "executableBytes": exe.stat().st_size,
        "executableSha256": sha256(exe),
        "architecture": "x86_64-windows-pe32+",
        "expectedFrameCount": 8,
        "expectedFrameSequences": list(range(1, 9)),
        "expectedGuestReadyValues": [1, 3, 5, 7, 9, 11, 13, 15],
        "expectedHostConsumedValues": [2, 4, 6, 8, 10, 12, 14, 16],
        "compiled": True,
        "wineExecuted": False,
        "continuousPresentExecuted": False,
        "hostFramesObserved": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "classification": {
            "build": "SOFTWARE_BUILD_EXECUTED",
            "wineRuntime": "NOT_EXECUTED",
            "v52Integration": "NOT_EXECUTED",
            "physical": "NOT_EXECUTED",
            "roblox": "NOT_EXECUTED",
        },
    }
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"POCKETPC_V52_SMOKE_BUILD_EVIDENCE={evidence_path}")
    print("V52_SMOKE_COMPILED=1")
    print("V52_SMOKE_EXECUTED=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
