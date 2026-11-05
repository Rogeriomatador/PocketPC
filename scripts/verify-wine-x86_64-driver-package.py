#!/usr/bin/env python3
"""Verify the installed winepocketpc driver pair inside a full Wine x86_64 build."""

from __future__ import annotations
import argparse, hashlib, json, struct
from pathlib import Path
from pocketpc_pe import PeAuditError, parse_pe_identity

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/wine/LOCK.json"
EXPECTED_STATUS = "WINE_X86_64_WITH_POCKETPC_DRIVER_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED"

def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()

def load_json(path: Path) -> dict[str, object]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON is not an object: {path}")
    return value

def elf_identity(path: Path) -> dict[str, int]:
    raw = path.read_bytes()[:20]
    if len(raw) < 20 or raw[:4] != b"\x7fELF":
        raise RuntimeError("winepocketpc.so is not ELF")
    if raw[5] != 1:
        raise RuntimeError("winepocketpc.so is not little-endian")
    elf_type, machine = struct.unpack_from("<HH", raw, 16)
    if raw[4] != 2 or machine != 62 or elf_type not in (2, 3):
        raise RuntimeError(
            f"winepocketpc.so target invalid: class={raw[4]} type={elf_type} machine={machine}"
        )
    return {"elfClass": raw[4], "elfType": elf_type, "machine": machine}

def inside(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError as error:
        raise RuntimeError(f"driver path escapes Wine root: {relative}") from error
    return candidate

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-evidence", type=Path, required=True)
    parser.add_argument("--wine-root", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    try:
        lock = load_json(LOCK)
        build = load_json(args.build_evidence)
        if build.get("status") != EXPECTED_STATUS:
            raise RuntimeError("full Wine build evidence status is invalid")
        if build.get("version") != lock.get("version"):
            raise RuntimeError("Wine version mismatch")
        if build.get("sourceCommit") != lock.get("commit"):
            raise RuntimeError("Wine source commit mismatch")
        driver = build.get("pocketPcDriver")
        if not isinstance(driver, dict):
            raise RuntimeError("pocketPcDriver evidence is missing")
        if driver.get("protocolVersion") != 4:
            raise RuntimeError("PocketPC driver protocol is not v4")
        if driver.get("loaded") is not False or driver.get("runtimeTested") is not False:
            raise RuntimeError("build evidence must not pre-claim driver load/runtime")
        if driver.get("surfaceCallbackImplemented") is not True:
            raise RuntimeError("surface callback implementation gate missing")
        if driver.get("inputInjectionImplemented") is not True:
            raise RuntimeError("input implementation gate missing")

        wine_root = args.wine_root.resolve()
        if not wine_root.is_dir():
            raise RuntimeError(f"Wine root missing: {wine_root}")
        pe_rel = driver.get("pePath")
        unix_rel = driver.get("unixPath")
        if not isinstance(pe_rel, str) or not isinstance(unix_rel, str):
            raise RuntimeError("driver artifact paths are invalid")
        pe = inside(wine_root, pe_rel)
        unixlib = inside(wine_root, unix_rel)
        if not pe.is_file() or not unixlib.is_file():
            raise RuntimeError("installed PocketPC driver pair is missing")

        pe_hash = sha256(pe)
        unix_hash = sha256(unixlib)
        if driver.get("peSha256") != pe_hash:
            raise RuntimeError("installed PE driver hash mismatch")
        if driver.get("unixSha256") != unix_hash:
            raise RuntimeError("installed Unix library hash mismatch")
        pe_id = parse_pe_identity(pe)
        if not pe_id.dll:
            raise RuntimeError("winepocketpc.drv PE image is not marked DLL")
        unix_id = elf_identity(unixlib)
        if driver.get("unixMachine") != unix_id["machine"]:
            raise RuntimeError("installed Unix library machine evidence mismatch")

        output = {
            "schema": 1,
            "status": "pass",
            "scope": "full_wine_package_driver_static_identity",
            "wineVersion": lock.get("version"),
            "wineCommit": lock.get("commit"),
            "protocolVersion": 4,
            "artifacts": {
                "winepocketpc.drv": {
                    "path": pe_rel,
                    "sha256": pe_hash,
                    "bytes": pe.stat().st_size,
                    "identity": pe_id.to_json(),
                },
                "winepocketpc.so": {
                    "path": unix_rel,
                    "sha256": unix_hash,
                    "bytes": unixlib.stat().st_size,
                    "identity": unix_id,
                },
            },
            "claims": {
                "full_wine_build_static_identity": True,
                "driver_pair_hashes_match_build_evidence": True,
                "driver_loaded": False,
                "graphics_registry_selection_proved": False,
                "surface_presented": False,
                "input_round_trip": False,
                "android_executed": False,
                "dxvk_vulkan_executed": False,
                "roblox_executed": False,
                "physical_validation": False,
            },
        }
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(output, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except (OSError, ValueError, RuntimeError, PeAuditError) as exc:
        print(f"WINE_X86_64_DRIVER_PACKAGE_VERIFY_FAIL={exc}")
        return 1

    print("WINE_X86_64_DRIVER_PACKAGE_VERIFY_OK")
    print("driver_pe=amd64_pe32plus_dll")
    print("driver_unixlib=elf64_x86_64")
    print("driver_loaded=false")
    print("graphics_registry_selection_proved=false")
    print("physical_validation=false")
    print(f"evidence={args.evidence}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
