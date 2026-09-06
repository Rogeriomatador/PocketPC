#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, pathlib, re, sys

HEX64=re.compile(r"^[0-9a-f]{64}$")
GIT40=re.compile(r"^[0-9a-f]{40}$")

def sha256_file(p:pathlib.Path)->str:
    h=hashlib.sha256()
    with p.open("rb") as f:
        while True:
            b=f.read(1024*1024)
            if not b: break
            h.update(b)
    return h.hexdigest()

def main()->int:
    ap=argparse.ArgumentParser()
    ap.add_argument("triage_dir",type=pathlib.Path)
    ns=ap.parse_args()
    root=ns.triage_dir.resolve()
    rec=root/"triage-record.json"
    side=root/"triage-record.json.sha256"
    failures=[]
    if not rec.is_file():
        print("TRIAGE_RECORD_FAILED\n- triage-record.json missing",file=sys.stderr); return 1
    try:
        data=json.loads(rec.read_text(encoding="utf-8-sig"))
    except Exception as e:
        print(f"TRIAGE_RECORD_FAILED\n- invalid JSON: {e}",file=sys.stderr); return 1
    if data.get("schemaVersion")!=1: failures.append("unsupported schemaVersion")
    if data.get("classification")!="FAILURE_TRIAGE_CAPTURED": failures.append("unexpected classification")
    repo=data.get("repository",{})
    commit=str(repo.get("commit","")).lower()
    if commit and not GIT40.fullmatch(commit): failures.append("repository commit invalid")
    hashes=data.get("fileSha256",{})
    if not isinstance(hashes,dict): failures.append("fileSha256 must be object")
    else:
        for name,expected in hashes.items():
            if pathlib.PurePath(name).name!=name: failures.append(f"unsafe file name: {name}"); continue
            p=root/name
            exp=str(expected).lower()
            if not HEX64.fullmatch(exp): failures.append(f"invalid SHA-256: {name}"); continue
            if not p.is_file(): failures.append(f"captured file missing: {name}"); continue
            if sha256_file(p)!=exp: failures.append(f"SHA-256 mismatch: {name}")
    device=data.get("device",{})
    serial=str(device.get("serialSha256","")).lower()
    if serial and not HEX64.fullmatch(serial): failures.append("device serialSha256 invalid")
    if not side.is_file(): failures.append("triage sidecar missing")
    else:
        toks=side.read_text(encoding="ascii",errors="strict").strip().split()
        if not toks or toks[0].lower()!=sha256_file(rec): failures.append("triage sidecar mismatch")
    if failures:
        print("TRIAGE_RECORD_FAILED",file=sys.stderr)
        for f in failures: print(f"- {f}",file=sys.stderr)
        return 1
    print("TRIAGE_RECORD_OK")
    print(f"failure_stage={data.get('failureStage')}")
    print(f"captured_files={len(hashes) if isinstance(hashes,dict) else 0}")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
