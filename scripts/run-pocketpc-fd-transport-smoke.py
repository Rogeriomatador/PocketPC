#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
HEADER = BRIDGE / "pocketpc_fd_transport.h"
SOURCE = BRIDGE / "pocketpc_fd_transport.c"
SMOKE = BRIDGE / "fd_transport_smoke.c"

EXPECTED_OUTPUT = (
    "FD_TRANSPORT_SMOKE_OK",
    "fd_number_serialized=false",
    "single_scm_rights_descriptor=true",
    "seqpacket_required=true",
    "cloexec=true",
    "vulkan_resource_import_executed=false",
)

COMPILE_FLAGS = (
    "-std=c11",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-Wpedantic",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def write_evidence(
    path: Path | None,
    *,
    compiler: str,
    output_lines: tuple[str, ...],
) -> None:
    if path is None:
        return

    evidence = {
        "schemaVersion": 1,
        "status": "FD_TRANSPORT_SOFTWARE_TEST_PASS",
        "classification": "SOFTWARE_TEST",
        "protocolVersion": 1,
        "compiler": compiler,
        "compileFlags": list(COMPILE_FLAGS),
        "sources": {
            "pocketpc_fd_transport.h": sha256(HEADER),
            "pocketpc_fd_transport.c": sha256(SOURCE),
            "fd_transport_smoke.c": sha256(SMOKE),
        },
        "observed": {
            "singleScmRightsDescriptor": True,
            "seqpacketRequired": True,
            "closeOnExec": True,
            "fdNumberSerialized": False,
        },
        "stdout": list(output_lines),
        "limits": {
            "guestVulkanResourceImportExecuted": False,
            "guestGpuSynchronizationExecuted": False,
            "wineVulkanWsiExecuted": False,
            "dxvkPresentExecuted": False,
            "androidPhysicalTestExecuted": False,
            "robloxExecuted": False,
        },
    }

    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(evidence, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--work",
        type=Path,
    )
    parser.add_argument(
        "--evidence",
        type=Path,
    )
    args = parser.parse_args()

    compiler = os.environ.get("CC", "cc")
    resolved_compiler = shutil.which(compiler)
    if resolved_compiler is None:
        print("FD_TRANSPORT_SMOKE_NOT_EXECUTED", file=sys.stderr)
        print(f"compiler_not_found={compiler}", file=sys.stderr)
        return 2

    for path in (HEADER, SOURCE, SMOKE):
        if not path.is_file():
            print("FD_TRANSPORT_SMOKE_NOT_EXECUTED", file=sys.stderr)
            print(f"source_missing={path}", file=sys.stderr)
            return 2

    temporary = None
    if args.work is None:
        temporary = tempfile.TemporaryDirectory(
            prefix="pocketpc-fd-transport-",
        )
        work = Path(temporary.name)
    else:
        work = args.work.resolve()
        work.mkdir(parents=True, exist_ok=True)

    binary = work / "fd-transport-smoke"
    compile_command = [
        resolved_compiler,
        *COMPILE_FLAGS,
        "-I",
        str(BRIDGE),
        str(SOURCE),
        str(SMOKE),
        "-o",
        str(binary),
    ]

    compile_result = subprocess.run(
        compile_command,
        text=True,
        capture_output=True,
        check=False,
    )
    if compile_result.returncode != 0:
        print("FD_TRANSPORT_SMOKE_COMPILE_FAILED", file=sys.stderr)
        if compile_result.stdout:
            print(compile_result.stdout, file=sys.stderr, end="")
        if compile_result.stderr:
            print(compile_result.stderr, file=sys.stderr, end="")
        return 1

    run_result = subprocess.run(
        [str(binary)],
        text=True,
        capture_output=True,
        check=False,
    )
    if run_result.returncode != 0:
        print("FD_TRANSPORT_SMOKE_EXECUTION_FAILED", file=sys.stderr)
        if run_result.stdout:
            print(run_result.stdout, file=sys.stderr, end="")
        if run_result.stderr:
            print(run_result.stderr, file=sys.stderr, end="")
        return 1

    output_lines = tuple(
        line.strip()
        for line in run_result.stdout.splitlines()
        if line.strip()
    )
    missing = [
        marker
        for marker in EXPECTED_OUTPUT
        if marker not in output_lines
    ]
    if missing:
        print("FD_TRANSPORT_SMOKE_OUTPUT_INVALID", file=sys.stderr)
        for marker in missing:
            print(f"missing={marker}", file=sys.stderr)
        return 1

    write_evidence(
        args.evidence,
        compiler=resolved_compiler,
        output_lines=output_lines,
    )

    print("FD_TRANSPORT_SOFTWARE_TEST_PASS")
    print(f"compiler={resolved_compiler}")
    for marker in EXPECTED_OUTPUT:
        print(marker)
    print("guest_vulkan_import_test_executed=false")
    print("guest_gpu_synchronization_test_executed=false")
    print("wine_vulkan_wsi_test_executed=false")
    print("physical_test_executed=false")

    if temporary is not None:
        temporary.cleanup()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
