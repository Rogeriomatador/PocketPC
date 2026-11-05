#!/usr/bin/env python3
from __future__ import annotations
import hashlib, json, struct, subprocess, sys, tempfile
from pathlib import Path
from pocketpc_pe import PE_FILE_DLL, PE_MACHINE_AMD64, PE_OPTIONAL_MAGIC_PE32_PLUS

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts/verify-wine-x86_64-driver-package.py"
LOCK = ROOT / "third_party/wine/LOCK.json"

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def make_pe(path: Path, machine=PE_MACHINE_AMD64, dll=True):
    data = bytearray(512)
    data[:2]=b"MZ"; off=0x80; struct.pack_into("<I", data, 0x3c, off); data[off:off+4]=b"PE\0\0"
    characteristics = PE_FILE_DLL if dll else 0
    struct.pack_into("<HHIIIHH", data, off+4, machine, 3, 0, 0, 0, 0xF0, characteristics)
    struct.pack_into("<H", data, off+24, PE_OPTIONAL_MAGIC_PE32_PLUS)
    path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)

def make_elf(path: Path, machine=62):
    data=bytearray(64); data[:4]=b"\x7fELF"; data[4]=2; data[5]=1; struct.pack_into("<HH", data,16,3,machine)
    path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)

def run(work: Path):
    return subprocess.run([sys.executable,str(VERIFIER),"--build-evidence",str(work/"build.json"),"--wine-root",str(work/"wine"),"--evidence",str(work/"verified.json")],
        cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=False)

def main():
    lock=json.loads(LOCK.read_text())
    with tempfile.TemporaryDirectory(prefix="pocketpc-wine-package-test-") as temp:
        work=Path(temp); wine=work/"wine"
        pe_rel="lib/wine/x86_64-windows/winepocketpc.drv"; so_rel="lib/wine/x86_64-unix/winepocketpc.so"
        pe=wine/pe_rel; so=wine/so_rel; make_pe(pe); make_elf(so)
        build={"schemaVersion":1,"status":"WINE_X86_64_WITH_POCKETPC_DRIVER_COMPILED_PACKAGE_NOT_GUEST_TESTED_NOT_APPROVED",
               "version":lock["version"],"sourceCommit":lock["commit"],
               "pocketPcDriver":{"protocolVersion":4,"pePath":pe_rel,"peSha256":sha256(pe),"unixPath":so_rel,"unixSha256":sha256(so),"unixMachine":62,
                                 "surfaceCallbackImplemented":True,"inputInjectionImplemented":True,"loaded":False,"runtimeTested":False}}
        (work/"build.json").write_text(json.dumps(build))
        good=run(work)
        if good.returncode != 0 or "WINE_X86_64_DRIVER_PACKAGE_VERIFY_OK" not in good.stdout:
            raise AssertionError("valid package rejected:\n"+good.stdout)
        evidence=json.loads((work/"verified.json").read_text())
        assert evidence["claims"]["driver_loaded"] is False
        assert evidence["claims"]["graphics_registry_selection_proved"] is False
        assert evidence["artifacts"]["winepocketpc.drv"]["identity"]["dll"] is True

        tampered=json.loads(json.dumps(build)); tampered["pocketPcDriver"]["peSha256"]="00"*32; (work/"build.json").write_text(json.dumps(tampered))
        bad=run(work)
        if bad.returncode == 0 or "PE driver hash mismatch" not in bad.stdout:
            raise AssertionError("tampered hash accepted:\n"+bad.stdout)

        make_pe(pe, machine=0x14c)
        wrong_machine_build=json.loads(json.dumps(build))
        wrong_machine_build["pocketPcDriver"]["peSha256"]=sha256(pe)
        (work/"build.json").write_text(json.dumps(wrong_machine_build))
        wrong_machine=run(work)
        if wrong_machine.returncode == 0 or "unexpected PE machine" not in wrong_machine.stdout:
            raise AssertionError("x86 PE accepted:\n"+wrong_machine.stdout)

        make_pe(pe, dll=False)
        no_dll=json.loads(json.dumps(build)); no_dll["pocketPcDriver"]["peSha256"]=sha256(pe); (work/"build.json").write_text(json.dumps(no_dll))
        not_dll=run(work)
        if not_dll.returncode == 0 or "not marked DLL" not in not_dll.stdout:
            raise AssertionError("non-DLL PE accepted:\n"+not_dll.stdout)

        make_pe(pe)
        preclaim=json.loads(json.dumps(build)); preclaim["pocketPcDriver"]["peSha256"]=sha256(pe); preclaim["pocketPcDriver"]["loaded"]=True
        (work/"build.json").write_text(json.dumps(preclaim))
        false_claim=run(work)
        if false_claim.returncode == 0 or "must not pre-claim" not in false_claim.stdout:
            raise AssertionError("false load preclaim accepted:\n"+false_claim.stdout)
    print("WINE_X86_64_DRIVER_PACKAGE_TEST_OK")
    print("coherent_full_package=accepted")
    print("tampered_pe_hash=rejected")
    print("wrong_pe_machine=rejected")
    print("non_dll_pe=rejected")
    print("false_load_preclaim=rejected")
    print("driver_loaded=false")
    print("physical_validation=false")
    return 0
if __name__=="__main__": raise SystemExit(main())
