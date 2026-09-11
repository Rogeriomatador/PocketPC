#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREPARE = ROOT / "scripts/prepare-update-feed.py"
CAPABILITY_PATH = "share/pocketpc/runtime-graphics-capabilities.json"
CAPABILITY_ID = "pocketpc.vulkan.continuous-present.v52"
REVISION = "a" * 40
OTHER_REVISION = "b" * 40


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def make_runtime(
    path: Path,
    pocketpc_revision: str,
    *,
    tamper_wine_hash: bool = False,
) -> None:
    capability = {
        "schemaVersion": 1,
        "wineVulkanAbi": 52,
        "capabilities": [CAPABILITY_ID],
        "pocketPcSourceRevision": pocketpc_revision,
        "experimental": True,
        "officialBuildSelected": False,
        "runtimeExecuted": False,
        "integrationExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
    }
    capability_payload = (
        json.dumps(capability, indent=2) + "\n"
    ).encode("utf-8")
    wine_payload = b"synthetic-wine-x86_64-entrypoint\n"
    wine_sha = (
        "0" * 64
        if tamper_wine_hash
        else sha256(wine_payload)
    )
    manifest = {
        "schemaVersion": 1,
        "id": "wine",
        "version": "11-v52-test",
        "architecture": "x86_64",
        "guestRoot": "/opt/pocketpc/wine",
        "entrypoint": "bin/wine",
        "sourceCommit": "c" * 40,
        "license": "LGPL-2.1-or-later",
        "executionMode": "box64-x86_64",
        "files": [
            {
                "path": "bin/wine",
                "sha256": wine_sha,
                "bytes": len(wine_payload),
                "executable": True,
            },
            {
                "path": CAPABILITY_PATH,
                "sha256": sha256(capability_payload),
                "bytes": len(capability_payload),
                "executable": False,
            },
        ],
    }
    with zipfile.ZipFile(
        path,
        "w",
        compression=zipfile.ZIP_DEFLATED,
    ) as archive:
        archive.writestr(
            "guest-tool-manifest.json",
            json.dumps(manifest, indent=2) + "\n",
        )
        archive.writestr("bin/wine", wine_payload)
        archive.writestr(CAPABILITY_PATH, capability_payload)


def run_prepare(
    apk: Path,
    output: Path,
    runtime: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    command = [
        sys.executable,
        str(PREPARE),
        "--apk",
        str(apk),
        "--apk-url",
        "https://updates.example/PocketPC.apk",
        "--source-revision",
        REVISION,
        "--output",
        str(output),
        "--publish",
    ]
    if runtime is not None:
        command.extend(
            [
                "--experimental-runtime-zip",
                str(runtime),
                "--experimental-runtime-url",
                "https://updates.example/PocketPC-Wine-v52.zip",
            ]
        )
    return subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> int:
    with tempfile.TemporaryDirectory(
        prefix="pocketpc-update-feed-v52-"
    ) as raw:
        work = Path(raw)
        apk = work / "PocketPC.apk"
        apk.write_bytes(b"synthetic-apk-for-feed-policy\n")

        apk_only = work / "apk-only.json"
        result = run_prepare(apk, apk_only)
        assert result.returncode == 0, result.stderr
        feed = json.loads(apk_only.read_text(encoding="utf-8"))
        assert feed["schemaVersion"] == 1
        assert feed["sourceRevision"] == REVISION
        assert "experimentalRuntime" not in feed

        runtime = work / "wine-v52.zip"
        make_runtime(runtime, REVISION)
        paired = work / "paired.json"
        result = run_prepare(apk, paired, runtime)
        assert result.returncode == 0, result.stderr
        feed = json.loads(paired.read_text(encoding="utf-8"))
        offer = feed["experimentalRuntime"]
        assert feed["schemaVersion"] == 1
        assert offer["kind"] == "wine"
        assert offer["experimental"] is True
        assert offer["guestToolVersion"] == "11-v52-test"
        assert offer["pocketPcSourceRevision"] == REVISION
        assert offer["pocketPcSourceRevision"] == feed["sourceRevision"]
        assert offer["wineVulkanAbi"] == 52
        assert offer["capabilities"] == [CAPABILITY_ID]
        assert offer["url"].startswith("https://")
        assert len(offer["sha256"]) == 64
        assert offer["bytes"] == runtime.stat().st_size

        mismatched = work / "wine-v52-mismatched.zip"
        make_runtime(mismatched, OTHER_REVISION)
        rejected = work / "rejected.json"
        result = run_prepare(apk, rejected, mismatched)
        assert result.returncode != 0
        assert "source revision does not match APK" in result.stderr
        assert not rejected.exists()

        tampered = work / "wine-v52-tampered.zip"
        make_runtime(
            tampered,
            REVISION,
            tamper_wine_hash=True,
        )
        rejected_tampered = work / "rejected-tampered.json"
        result = run_prepare(apk, rejected_tampered, tampered)
        assert result.returncode != 0
        assert "file SHA-256 mismatch: bin/wine" in result.stderr
        assert not rejected_tampered.exists()

    print("POCKETPC_UPDATE_FEED_V52_RUNTIME_BINDING_OK")
    print("SCHEMA_V1_APK_ONLY_COMPATIBLE=1")
    print("V52_RUNTIME_PAIRING_FAIL_CLOSED=1")
    print("FULL_GUEST_PACKAGE_ATTESTATION=1")
    print("TAMPERED_GUEST_FILE_REJECTED=1")
    print("RUNTIME_EXECUTED=0")
    print("PHYSICAL_VISIBLE_FRAME=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
