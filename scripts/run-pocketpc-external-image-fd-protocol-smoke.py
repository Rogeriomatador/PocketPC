#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
SOURCES = (
    BRIDGE / "pocketpc_graphics_transport.c",
    BRIDGE / "pocketpc_graphics_handle_binding.c",
    BRIDGE / "pocketpc_external_image_fd_protocol.c",
    BRIDGE / "test_pocketpc_external_image_fd_protocol.c",
)
HEADERS = (
    BRIDGE / "pocketpc_graphics_transport.h",
    BRIDGE / "pocketpc_fd_transport.h",
    BRIDGE / "pocketpc_graphics_handle_binding.h",
    BRIDGE / "pocketpc_external_image_fd_protocol.h",
)
FLAGS = (
    "-std=c11",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-Wpedantic",
)
SENTINEL = "POCKETPC_EXTERNAL_IMAGE_FD_PROTOCOL_SMOKE_OK"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    compiler = shutil.which("cc")
    if compiler is None:
        raise SystemExit("PVI1_SMOKE_COMPILER_NOT_FOUND")

    for path in (*SOURCES, *HEADERS):
        if not path.is_file():
            raise SystemExit(f"PVI1_SMOKE_SOURCE_MISSING:{path.relative_to(ROOT)}")

    work = args.work.resolve()
    evidence_path = args.evidence.resolve()
    work.mkdir(parents=True, exist_ok=True)
    evidence_path.parent.mkdir(parents=True, exist_ok=True)
    binary = work / "pocketpc-external-image-fd-protocol-smoke"

    command = [
        compiler,
        *FLAGS,
        "-I",
        str(BRIDGE),
        *(str(path) for path in SOURCES),
        "-o",
        str(binary),
    ]
    compile_result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if compile_result.returncode != 0:
        print(compile_result.stdout, end="")
        print(compile_result.stderr, end="")
        raise SystemExit("PVI1_SMOKE_COMPILE_FAILED")

    run_result = subprocess.run(
        [str(binary)],
        cwd=work,
        text=True,
        capture_output=True,
        check=False,
    )
    if run_result.returncode != 0 or SENTINEL not in run_result.stdout:
        print(run_result.stdout, end="")
        print(run_result.stderr, end="")
        raise SystemExit("PVI1_SMOKE_EXECUTION_FAILED")

    evidence = {
        "schemaVersion": 1,
        "status": "SOFTWARE_TEST_PASS",
        "test": "PocketPC external image FD protocol PVI1",
        "compiler": compiler,
        "compileFlags": list(FLAGS),
        "protocol": "PVI1",
        "protocolVersion": 1,
        "payloadBytes": 64,
        "transport": "AF_UNIX/SOCK_SEQPACKET + SCM_RIGHTS",
        "checks": [
            "real file descriptor transfer",
            "64-byte PVI1 metadata decode",
            "resource/generation/sequence offer binding",
            "format/usage/allocation/memory-type validation",
            "FD_CLOEXEC",
            "mismatched sequence rejection",
            "received fd cleanup",
        ],
        "sourceSha256": {
            str(path.relative_to(ROOT)): sha256(path)
            for path in (*SOURCES, *HEADERS)
        },
        "stdout": run_result.stdout.strip(),
        "limits": {
            "vulkanImportExecuted": False,
            "gpuSynchronizationExecuted": False,
            "wineExecuted": False,
            "box64Executed": False,
            "androidExecuted": False,
            "dxvkPresentExecuted": False,
            "robloxExecuted": False,
        },
    }
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print(SENTINEL)
    print(f"evidence={evidence_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
