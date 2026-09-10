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
FD_HEADER = BRIDGE / "pocketpc_fd_transport.h"
FD_SOURCE = BRIDGE / "pocketpc_fd_transport.c"
FD_SMOKE = BRIDGE / "fd_transport_smoke.c"
GRAPHICS_HEADER = BRIDGE / "pocketpc_graphics_transport.h"
GRAPHICS_SOURCE = BRIDGE / "pocketpc_graphics_transport.c"
BINDING_HEADER = BRIDGE / "pocketpc_graphics_handle_binding.h"
BINDING_SOURCE = BRIDGE / "pocketpc_graphics_handle_binding.c"
BINDING_SMOKE = BRIDGE / "graphics_handle_binding_smoke.c"

FD_EXPECTED_OUTPUT = (
    "FD_TRANSPORT_SMOKE_OK",
    "fd_number_serialized=false",
    "single_scm_rights_descriptor=true",
    "seqpacket_required=true",
    "cloexec=true",
    "vulkan_resource_import_executed=false",
)

BINDING_EXPECTED_OUTPUT = (
    "GRAPHICS_HANDLE_BINDING_SMOKE_OK",
    "resource_identity_guard=true",
    "generation_guard=true",
    "sequence_guard=true",
    "offered_state_required=true",
    "vulkan_import_executed=false",
    "gpu_synchronization_executed=false",
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


def compile_and_run(
    *,
    compiler: str,
    binary: Path,
    sources: tuple[Path, ...],
    expected_output: tuple[str, ...],
    label: str,
) -> tuple[str, ...] | None:
    compile_command = [
        compiler,
        *COMPILE_FLAGS,
        "-I",
        str(BRIDGE),
        *(str(source) for source in sources),
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
        print(f"{label}_COMPILE_FAILED", file=sys.stderr)
        if compile_result.stdout:
            print(compile_result.stdout, file=sys.stderr, end="")
        if compile_result.stderr:
            print(compile_result.stderr, file=sys.stderr, end="")
        return None

    run_result = subprocess.run(
        [str(binary)],
        text=True,
        capture_output=True,
        check=False,
    )
    if run_result.returncode != 0:
        print(f"{label}_EXECUTION_FAILED", file=sys.stderr)
        if run_result.stdout:
            print(run_result.stdout, file=sys.stderr, end="")
        if run_result.stderr:
            print(run_result.stderr, file=sys.stderr, end="")
        return None

    output_lines = tuple(
        line.strip()
        for line in run_result.stdout.splitlines()
        if line.strip()
    )
    missing = [
        marker
        for marker in expected_output
        if marker not in output_lines
    ]
    if missing:
        print(f"{label}_OUTPUT_INVALID", file=sys.stderr)
        for marker in missing:
            print(f"missing={marker}", file=sys.stderr)
        return None
    return output_lines


def write_evidence(
    path: Path | None,
    *,
    compiler: str,
    fd_output: tuple[str, ...],
    binding_output: tuple[str, ...],
) -> None:
    if path is None:
        return

    evidence = {
        "schemaVersion": 2,
        "status": "GRAPHICS_FD_FOUNDATION_SOFTWARE_TEST_PASS",
        "classification": "SOFTWARE_TEST",
        "fdTransportProtocolVersion": 1,
        "graphicsTransportProtocolVersion": 1,
        "compiler": compiler,
        "compileFlags": list(COMPILE_FLAGS),
        "sources": {
            path.name: sha256(path)
            for path in (
                FD_HEADER,
                FD_SOURCE,
                FD_SMOKE,
                GRAPHICS_HEADER,
                GRAPHICS_SOURCE,
                BINDING_HEADER,
                BINDING_SOURCE,
                BINDING_SMOKE,
            )
        },
        "observed": {
            "singleScmRightsDescriptor": True,
            "seqpacketRequired": True,
            "closeOnExec": True,
            "fdNumberSerialized": False,
            "resourceIdentityGuard": True,
            "generationGuard": True,
            "sequenceGuard": True,
            "offeredStateRequired": True,
        },
        "stdout": {
            "fdTransport": list(fd_output),
            "graphicsHandleBinding": list(binding_output),
        },
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
    parser.add_argument("--work", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()

    compiler = os.environ.get("CC", "cc")
    resolved_compiler = shutil.which(compiler)
    if resolved_compiler is None:
        print("GRAPHICS_FD_FOUNDATION_NOT_EXECUTED", file=sys.stderr)
        print(f"compiler_not_found={compiler}", file=sys.stderr)
        return 2

    required_paths = (
        FD_HEADER,
        FD_SOURCE,
        FD_SMOKE,
        GRAPHICS_HEADER,
        GRAPHICS_SOURCE,
        BINDING_HEADER,
        BINDING_SOURCE,
        BINDING_SMOKE,
    )
    for path in required_paths:
        if not path.is_file():
            print("GRAPHICS_FD_FOUNDATION_NOT_EXECUTED", file=sys.stderr)
            print(f"source_missing={path}", file=sys.stderr)
            return 2

    temporary = None
    if args.work is None:
        temporary = tempfile.TemporaryDirectory(
            prefix="pocketpc-graphics-fd-foundation-",
        )
        work = Path(temporary.name)
    else:
        work = args.work.resolve()
        work.mkdir(parents=True, exist_ok=True)

    fd_output = compile_and_run(
        compiler=resolved_compiler,
        binary=work / "fd-transport-smoke",
        sources=(FD_SOURCE, FD_SMOKE),
        expected_output=FD_EXPECTED_OUTPUT,
        label="FD_TRANSPORT_SMOKE",
    )
    if fd_output is None:
        return 1

    binding_output = compile_and_run(
        compiler=resolved_compiler,
        binary=work / "graphics-handle-binding-smoke",
        sources=(
            GRAPHICS_SOURCE,
            BINDING_SOURCE,
            BINDING_SMOKE,
        ),
        expected_output=BINDING_EXPECTED_OUTPUT,
        label="GRAPHICS_HANDLE_BINDING_SMOKE",
    )
    if binding_output is None:
        return 1

    write_evidence(
        args.evidence,
        compiler=resolved_compiler,
        fd_output=fd_output,
        binding_output=binding_output,
    )

    print("GRAPHICS_FD_FOUNDATION_SOFTWARE_TEST_PASS")
    print(f"compiler={resolved_compiler}")
    for marker in FD_EXPECTED_OUTPUT:
        print(marker)
    for marker in BINDING_EXPECTED_OUTPUT:
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
