#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)

    transport = BRIDGE / "pocketpc_graphics_transport.c"
    smoke = BRIDGE / "graphics_session_handshake_smoke.c"
    binary = work / "graphics-session-handshake-smoke"
    evidence_path = work / "graphics-session-handshake-evidence.json"
    flags = ["-std=c11", "-Wall", "-Wextra", "-Werror", "-Wpedantic", "-O2"]

    compile_cmd = [
        "cc",
        *flags,
        "-I",
        str(BRIDGE),
        str(transport),
        str(smoke),
        "-o",
        str(binary),
    ]
    subprocess.run(compile_cmd, check=True)
    completed = subprocess.run(
        [str(binary)],
        check=True,
        text=True,
        capture_output=True,
    )
    if "POCKETPC_GRAPHICS_SESSION_HANDSHAKE_SMOKE_OK" not in completed.stdout:
        raise SystemExit("GRAPHICS_SESSION_HANDSHAKE_SENTINEL_MISSING")

    evidence = {
        "schemaVersion": 1,
        "status": "SOFTWARE_TEST_PASS_GRAPHICS_SESSION_HANDSHAKE_ONLY",
        "platform": platform.platform(),
        "compiler": subprocess.check_output(["cc", "--version"], text=True).splitlines()[0],
        "flags": flags,
        "sources": {
            str(transport.relative_to(ROOT)): sha256(transport),
            str(smoke.relative_to(ROOT)): sha256(smoke),
        },
        "stdout": completed.stdout.splitlines(),
        "proved": {
            "guestAbstractSeqpacketConnect": True,
            "fixedHandshakeLayout": True,
            "tokenBytesMatch": True,
            "claimedPidMatchesPeerCredential": True,
        },
        "notProved": {
            "androidNativeHostExecution": True,
            "wineRuntimeSessionIntegration": True,
            "vulkanImageImport": True,
            "gpuQueueSynchronization": True,
            "visiblePresent": True,
            "roblox": True,
        },
    }
    evidence_path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("POCKETPC_GRAPHICS_SESSION_HANDSHAKE_EVIDENCE_OK")
    print(evidence_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
