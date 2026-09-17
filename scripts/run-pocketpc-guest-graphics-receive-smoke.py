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
SOURCES = (
    BRIDGE / "pocketpc_graphics_transport.c",
    BRIDGE / "pocketpc_fd_transport.c",
    BRIDGE / "pocketpc_graphics_handle_binding.c",
    BRIDGE / "pocketpc_guest_graphics_receive.c",
    BRIDGE / "guest_graphics_receive_smoke.c",
)
EXPECTED_OUTPUT = (
    "GUEST_GRAPHICS_RECEIVE_SMOKE_OK",
    "fd_transport=SCM_RIGHTS",
    "identity_binding=resource_generation_sequence",
    "rejected_handle_exposed=false",
    "vulkan_import_executed=false",
    "gpu_synchronization_executed=false",
    "roblox_executed=false",
)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", type=Path)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()

    compiler = os.environ.get("CC", "cc")
    resolved_compiler = shutil.which(compiler)
    if resolved_compiler is None:
        print("GUEST_GRAPHICS_RECEIVE_SMOKE_NOT_EXECUTED", file=sys.stderr)
        print(f"compiler_not_found={compiler}", file=sys.stderr)
        return 2

    missing_sources = [path for path in SOURCES if not path.is_file()]
    if missing_sources:
        print("GUEST_GRAPHICS_RECEIVE_SMOKE_NOT_EXECUTED", file=sys.stderr)
        for path in missing_sources:
            print(f"source_missing={path}", file=sys.stderr)
        return 2

    temporary = None
    if args.work is None:
        temporary = tempfile.TemporaryDirectory(
            prefix="pocketpc-guest-graphics-receive-",
        )
        work = Path(temporary.name)
    else:
        work = args.work.resolve()
        work.mkdir(parents=True, exist_ok=True)

    binary = work / "guest-graphics-receive-smoke"
    compile_flags = (
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wpedantic",
    )
    compile_command = [
        resolved_compiler,
        *compile_flags,
        "-I",
        str(BRIDGE),
        *(str(path) for path in SOURCES),
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
        print("GUEST_GRAPHICS_RECEIVE_SMOKE_COMPILE_FAILED", file=sys.stderr)
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
        print("GUEST_GRAPHICS_RECEIVE_SMOKE_EXECUTION_FAILED", file=sys.stderr)
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
    missing = [marker for marker in EXPECTED_OUTPUT if marker not in output_lines]
    if missing:
        print("GUEST_GRAPHICS_RECEIVE_SMOKE_OUTPUT_INVALID", file=sys.stderr)
        for marker in missing:
            print(f"missing={marker}", file=sys.stderr)
        return 1

    evidence = {
        "schemaVersion": 1,
        "classification": "SOFTWARE_TEST",
        "status": "PASS",
        "scope": "isolated-linux-guest-graphics-receive-primitive",
        "compiler": resolved_compiler,
        "compileFlags": list(compile_flags),
        "sources": [
            {
                "path": str(path.relative_to(ROOT)),
                "sha256": digest(path),
            }
            for path in SOURCES
        ],
        "observed": {
            "scmRightsFdReceived": True,
            "identityBindingChecked": True,
            "rejectedHandleExposedToCaller": False,
            "vulkanImportExecuted": False,
            "gpuSynchronizationExecuted": False,
            "authenticatedRuntimeSessionExecuted": False,
            "box64Executed": False,
            "androidPhysicalTestExecuted": False,
            "robloxExecuted": False,
        },
        "stdout": list(output_lines),
    }

    if args.evidence is not None:
        evidence_path = args.evidence.resolve()
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        evidence_path.write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

    print("GUEST_GRAPHICS_RECEIVE_SOFTWARE_TEST_PASS")
    for marker in EXPECTED_OUTPUT:
        print(marker)
    print("authenticated_runtime_session_executed=false")
    print("android_physical_test_executed=false")

    if temporary is not None:
        temporary.cleanup()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
