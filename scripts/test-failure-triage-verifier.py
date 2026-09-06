#!/usr/bin/env python3
from __future__ import annotations
import hashlib,json,pathlib,subprocess,sys,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
V=ROOT/"scripts"/"verify-failure-triage.py"
def sha(b:bytes)->str:return hashlib.sha256(b).hexdigest()
def main()->int:
    with tempfile.TemporaryDirectory(prefix="ppc-triage-") as t:
        r=pathlib.Path(t); f=r/"logcat-pocketpc.txt"; f.write_text("synthetic\n",encoding="utf-8")
        d={"schemaVersion":1,"classification":"FAILURE_TRIAGE_CAPTURED","failureStage":"TEST","failureMessage":"x","repository":{"commit":"a"*40,"dirty":False},"build":{},"device":{"serialSha256":"b"*64},"capturedFiles":["logcat-pocketpc.txt"],"fileSha256":{"logcat-pocketpc.txt":sha(f.read_bytes())}}
        rec=r/"triage-record.json"; rec.write_text(json.dumps(d,indent=2),encoding="utf-8")
        (r/"triage-record.json.sha256").write_text(f"{sha(rec.read_bytes())}  triage-record.json\n",encoding="ascii")
        good=subprocess.run([sys.executable,str(V),str(r)],capture_output=True,text=True)
        if good.returncode!=0: print(good.stderr,file=sys.stderr); raise SystemExit("valid triage rejected")
        f.write_text("tampered\n",encoding="utf-8")
        bad=subprocess.run([sys.executable,str(V),str(r)],capture_output=True,text=True)
        if bad.returncode==0 or "SHA-256 mismatch" not in bad.stderr: print(bad.stderr,file=sys.stderr); raise SystemExit("tamper not detected")
    print("TRIAGE_RECORD_SELFTEST_OK"); return 0
if __name__=="__main__": raise SystemExit(main())
