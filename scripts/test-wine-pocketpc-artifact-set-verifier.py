#!/usr/bin/env python3
"""Synthetic software tests for Wine PocketPC artifact-set coherence checks."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

from pocketpc_pe import (
    PE_FILE_DLL,
    PE_MACHINE_AMD64,
    PE_OPTIONAL_MAGIC_PE32_PLUS,
    parse_pe_identity,
)

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify-wine-pocketpc-driver-artifact-set.py"
LOCK = ROOT / "third_party/wine/LOCK.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def synthetic_pe(path: Path) -> None:
    data = bytearray(512)
    data[:2] = b"MZ"
    pe_offset = 0x80
    struct.pack_into("<I", data, 0x3C, pe_offset)
    data[pe_offset : pe_offset + 4] = b"PE\0\0"
    struct.pack_into(
        "<HHIIIHH",
        data,
        pe_offset + 4,
        PE_MACHINE_AMD64,
        3,
        0,
        0,
        0,
        0xF0,
        PE_FILE_DLL,
    )
    struct.pack_into("<H", data, pe_offset + 24, PE_OPTIONAL_MAGIC_PE32_PLUS)
    path.write_bytes(data)


def synthetic_elf(path: Path) -> None:
    data = bytearray(64)
    data[:4] = b"\x7fELF"
    data[4] = 2  # ELF64
    data[5] = 1  # little-endian
    struct.pack_into("<HH", data, 16, 3, 62)  # ET_DYN, EM_X86_64
    path.write_bytes(data)


def run_verifier(work: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(VERIFIER),
            "--build-evidence",
            str(work / "build.json"),
            "--pe-evidence",
            str(work / "pe.json"),
            "--driver",
            str(work / "winepocketpc.drv"),
            "--unixlib",
            str(work / "winepocketpc.so"),
            "--evidence",
            str(work / "coherence.json"),
        ],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )


def main() -> int:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))

    with tempfile.TemporaryDirectory(prefix="pocketpc-artifact-set-test-") as temp:
        work = Path(temp)
        driver = work / "winepocketpc.drv"
        unixlib = work / "winepocketpc.so"
        synthetic_pe(driver)
        synthetic_elf(unixlib)
        pe_identity = parse_pe_identity(driver)

        build = {
            "schemaVersion": 1,
            "wineVersion": lock["version"],
            "wineCommit": lock["commit"],
            "driverName": "winepocketpc.drv",
            "unixLibrary": "winepocketpc.so",
            "protocolVersion": 4,
            "driverLoaded": False,
            "runtimeExecuted": False,
            "androidExecuted": False,
            "robloxExecuted": False,
            "status": "COMPILED_X86_64_NOT_LOADED_NOT_RUNTIME_TESTED",
            "artifacts": {
                "peDriver": {
                    "path": str(driver),
                    "bytes": driver.stat().st_size,
                    "sha256": sha256(driver),
                    "header": "MZ",
                },
                "unixLibrary": {
                    "path": str(unixlib),
                    "bytes": unixlib.stat().st_size,
                    "sha256": sha256(unixlib),
                    "elfClass": 2,
                    "elfType": 3,
                    "machine": 62,
                },
            },
            "notExecuted": [
                "winepocketpc.drv LoadLibrary",
                "Graphics=pocketpc driver selection",
                "HWND lifecycle through driver",
                "surface presentation",
                "input injection",
                "Box64 execution",
                "Android execution",
                "DXVK/Vulkan",
                "Roblox",
            ],
        }
        pe = {
            "schema": 1,
            "status": "pass",
            "scope": "static_binary_identity",
            "driver": str(driver),
            "sha256": sha256(driver),
            "pe": pe_identity.to_json(),
            "claims": {
                "dos_signature": True,
                "pe_signature": True,
                "amd64_machine": True,
                "pe32_plus": True,
                "compiled_or_loadable_in_wine": False,
                "physical_android_validation": False,
            },
        }

        (work / "build.json").write_text(
            json.dumps(build, indent=2) + "\n",
            encoding="utf-8",
        )
        (work / "pe.json").write_text(
            json.dumps(pe, indent=2) + "\n",
            encoding="utf-8",
        )

        good = run_verifier(work)
        if good.returncode != 0 or "WINE_POCKETPC_ARTIFACT_SET_VERIFY_OK" not in good.stdout:
            raise AssertionError("valid artifact set was rejected:\n" + good.stdout)

        coherence = json.loads((work / "coherence.json").read_text(encoding="utf-8"))
        assert coherence["claims"]["same_build_evidence_set"] is True
        assert coherence["claims"]["driver_loaded"] is False
        assert coherence["claims"]["runtime_executed"] is False
        assert coherence["claims"]["physical_validation"] is False

        tampered_hash = json.loads(json.dumps(build))
        tampered_hash["artifacts"]["peDriver"]["sha256"] = "00" * 32
        (work / "build.json").write_text(json.dumps(tampered_hash), encoding="utf-8")
        bad_hash = run_verifier(work)
        if bad_hash.returncode == 0 or "PE build hash mismatch" not in bad_hash.stdout:
            raise AssertionError("tampered PE hash was not rejected:\n" + bad_hash.stdout)

        executed_claim = json.loads(json.dumps(build))
        executed_claim["driverLoaded"] = True
        (work / "build.json").write_text(json.dumps(executed_claim), encoding="utf-8")
        bad_claim = run_verifier(work)
        if bad_claim.returncode == 0 or "driverLoaded must remain false" not in bad_claim.stdout:
            raise AssertionError("false runtime promotion was not rejected:\n" + bad_claim.stdout)

        (work / "build.json").write_text(json.dumps(build), encoding="utf-8")
        pe_tampered = json.loads(json.dumps(pe))
        pe_tampered["sha256"] = "11" * 32
        (work / "pe.json").write_text(json.dumps(pe_tampered), encoding="utf-8")
        bad_pe_evidence = run_verifier(work)
        if bad_pe_evidence.returncode == 0 or "PE audit hash mismatch" not in bad_pe_evidence.stdout:
            raise AssertionError("mixed PE evidence was not rejected:\n" + bad_pe_evidence.stdout)

    print("WINE_POCKETPC_ARTIFACT_SET_TEST_OK")
    print("mixed_pe_hash=rejected")
    print("false_driver_loaded_claim=rejected")
    print("coherent_pe_elf_pair=accepted")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
