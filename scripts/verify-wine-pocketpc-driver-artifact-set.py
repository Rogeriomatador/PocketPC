#!/usr/bin/env python3
"""Verify that Wine PocketPC driver evidence describes one coherent artifact pair.

This verifier proves only static artifact/evidence coherence. It never upgrades
build artifacts into Wine load, runtime, Android, Vulkan, or physical evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct

from pocketpc_pe import PeAuditError, parse_pe_identity

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/LOCK.json"
EXPECTED_BUILD_STATUS = "COMPILED_X86_64_NOT_LOADED_NOT_RUNTIME_TESTED"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict[str, object]:
    if not path.is_file():
        raise RuntimeError(f"missing JSON evidence: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON evidence is not an object: {path}")
    return value


def audit_elf_x86_64(path: Path) -> dict[str, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise RuntimeError("winepocketpc.so is not ELF")
    elf_class = raw[4]
    endian = raw[5]
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    if elf_class != 2:
        raise RuntimeError(f"winepocketpc.so is not ELF64: class={elf_class}")
    if endian != 1:
        raise RuntimeError(f"winepocketpc.so is not little-endian: endian={endian}")
    if machine != 62:
        raise RuntimeError(f"winepocketpc.so is not x86_64: machine={machine}")
    if elf_type not in (2, 3):
        raise RuntimeError(f"winepocketpc.so has unsupported ELF type={elf_type}")
    return {
        "elfClass": elf_class,
        "elfType": elf_type,
        "machine": machine,
    }


def require_equal(label: str, actual: object, expected: object) -> None:
    if actual != expected:
        raise RuntimeError(f"{label} mismatch: actual={actual!r} expected={expected!r}")


def require_false(label: str, value: object) -> None:
    if value is not False:
        raise RuntimeError(f"{label} must remain false, got {value!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--pe-evidence", type=Path, required=True)
    parser.add_argument("--driver", type=Path, required=True)
    parser.add_argument("--unixlib", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    try:
        lock = load_json(LOCK)
        build = load_json(args.build_evidence)
        pe_evidence = load_json(args.pe_evidence)

        if not args.driver.is_file():
            raise RuntimeError(f"missing PE driver artifact: {args.driver}")
        if not args.unixlib.is_file():
            raise RuntimeError(f"missing Unix library artifact: {args.unixlib}")

        require_equal("build status", build.get("status"), EXPECTED_BUILD_STATUS)
        require_equal("Wine version", build.get("wineVersion"), lock.get("version"))
        require_equal("Wine commit", build.get("wineCommit"), lock.get("commit"))
        require_equal("driver name", build.get("driverName"), "winepocketpc.drv")
        require_equal("Unix library name", build.get("unixLibrary"), "winepocketpc.so")
        require_equal("display protocol", build.get("protocolVersion"), 4)
        for key in ("driverLoaded", "runtimeExecuted", "androidExecuted", "robloxExecuted"):
            require_false(f"build evidence {key}", build.get(key))

        artifacts = build.get("artifacts")
        if not isinstance(artifacts, dict):
            raise RuntimeError("build evidence artifacts object is missing")
        pe_build = artifacts.get("peDriver")
        unix_build = artifacts.get("unixLibrary")
        if not isinstance(pe_build, dict) or not isinstance(unix_build, dict):
            raise RuntimeError("build evidence does not contain both driver artifacts")

        pe_hash = sha256(args.driver)
        unix_hash = sha256(args.unixlib)
        require_equal("PE build hash", pe_build.get("sha256"), pe_hash)
        require_equal("Unix build hash", unix_build.get("sha256"), unix_hash)
        require_equal("PE build size", pe_build.get("bytes"), args.driver.stat().st_size)
        require_equal("Unix build size", unix_build.get("bytes"), args.unixlib.stat().st_size)

        pe_identity = parse_pe_identity(args.driver)
        unix_identity = audit_elf_x86_64(args.unixlib)
        require_equal("Unix ELF class", unix_build.get("elfClass"), unix_identity["elfClass"])
        require_equal("Unix ELF type", unix_build.get("elfType"), unix_identity["elfType"])
        require_equal("Unix ELF machine", unix_build.get("machine"), unix_identity["machine"])

        require_equal("PE audit status", pe_evidence.get("status"), "pass")
        require_equal("PE audit scope", pe_evidence.get("scope"), "static_binary_identity")
        require_equal("PE audit hash", pe_evidence.get("sha256"), pe_hash)
        require_equal("PE audit identity", pe_evidence.get("pe"), pe_identity.to_json())
        pe_claims = pe_evidence.get("claims")
        if not isinstance(pe_claims, dict):
            raise RuntimeError("PE audit claims object is missing")
        require_false(
            "PE audit compiled_or_loadable_in_wine",
            pe_claims.get("compiled_or_loadable_in_wine"),
        )
        require_false(
            "PE audit physical_android_validation",
            pe_claims.get("physical_android_validation"),
        )

        not_executed = build.get("notExecuted")
        if not isinstance(not_executed, list):
            raise RuntimeError("build evidence notExecuted list is missing")
        for required in (
            "winepocketpc.drv LoadLibrary",
            "Graphics=pocketpc driver selection",
            "surface presentation",
            "input injection",
            "Android execution",
            "DXVK/Vulkan",
            "Roblox",
        ):
            if required not in not_executed:
                raise RuntimeError(f"build evidence lost NOT_EXECUTED gate: {required}")

        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        output = {
            "schema": 1,
            "status": "pass",
            "scope": "static_artifact_set_coherence",
            "wineVersion": lock.get("version"),
            "wineCommit": lock.get("commit"),
            "protocolVersion": 4,
            "artifacts": {
                "winepocketpc.drv": {
                    "sha256": pe_hash,
                    "bytes": args.driver.stat().st_size,
                    "identity": pe_identity.to_json(),
                },
                "winepocketpc.so": {
                    "sha256": unix_hash,
                    "bytes": args.unixlib.stat().st_size,
                    "identity": unix_identity,
                },
            },
            "claims": {
                "same_build_evidence_set": True,
                "static_binary_identity": True,
                "driver_loaded": False,
                "runtime_executed": False,
                "android_executed": False,
                "physical_validation": False,
            },
        }
        args.evidence.write_text(
            json.dumps(output, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, KeyError, RuntimeError, PeAuditError) as exc:
        print(f"WINE_POCKETPC_ARTIFACT_SET_VERIFY_FAIL={exc}")
        return 1

    print("WINE_POCKETPC_ARTIFACT_SET_VERIFY_OK")
    print(f"wine_commit={lock.get('commit')}")
    print("pe_machine=x86_64")
    print("unix_machine=x86_64")
    print("same_build_evidence_set=true")
    print("driver_loaded=false")
    print("runtime_execution_evidence=false")
    print("physical_validation=false")
    print(f"evidence={args.evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
