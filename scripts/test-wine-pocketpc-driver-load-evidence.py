#!/usr/bin/env python3
from __future__ import annotations
import json, subprocess, sys, tempfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
VERIFIER=ROOT/"scripts/verify-wine-pocketpc-driver-load-evidence.py"

def run(work,expect="registered",package=False):
    cmd=[sys.executable,str(VERIFIER),"--log",str(work/"wine.log"),"--evidence",str(work/"load.json"),"--expect",expect]
    if package:
        cmd.extend(["--package-evidence",str(work/"package.json")])
    else:
        cmd.extend(["--artifact-set",str(work/"artifact-set.json")])
    return subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=False)

def write_preconditions(work):
    artifact={"schema":1,"status":"pass","scope":"static_artifact_set_coherence","claims":{
        "same_build_evidence_set":True,"static_binary_identity":True,"driver_loaded":False,
        "runtime_executed":False,"android_executed":False,"physical_validation":False}}
    package={"schema":1,"status":"pass","scope":"full_wine_package_driver_static_identity","protocolVersion":4,"claims":{
        "full_wine_build_static_identity":True,"driver_pair_hashes_match_build_evidence":True,
        "driver_loaded":False,"graphics_registry_selection_proved":False,"surface_presented":False,
        "input_round_trip":False,"android_executed":False,"dxvk_vulkan_executed":False,
        "roblox_executed":False,"physical_validation":False}}
    (work/"artifact-set.json").write_text(json.dumps(artifact),encoding="utf-8")
    (work/"package.json").write_text(json.dumps(package),encoding="utf-8")

def registered_log():
    return ("0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\n"
            "0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=4 capabilities=23\n"
            "0001:trace:pocketpcdrv: POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4 pid_namespace=41\n")

def main():
    with tempfile.TemporaryDirectory(prefix="pocketpc-driver-load-test-") as temp:
        work=Path(temp); write_preconditions(work)
        (work/"wine.log").write_text(registered_log(),encoding="utf-8")
        good=run(work)
        if good.returncode!=0 or "static_precondition=driver_artifact_set" not in good.stdout or "verified_level=registered" not in good.stdout:
            raise AssertionError(good.stdout)
        evidence=json.loads((work/"load.json").read_text()); assert evidence["claims"]["graphics_registry_selection_proved"] is False

        package_good=run(work,package=True)
        if package_good.returncode!=0 or "static_precondition=full_wine_package" not in package_good.stdout:
            raise AssertionError(package_good.stdout)

        (work/"wine.log").write_text("wine loaded something called winepocketpc.drv successfully\n",encoding="utf-8")
        generic=run(work)
        if generic.returncode==0 or "no PocketPC driver load markers found" not in generic.stdout: raise AssertionError(generic.stdout)

        (work/"wine.log").write_text("POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\nPOCKETPC_DRIVER_LOAD stage=bridge_connect_failed protocol=4 error=refused\n",encoding="utf-8")
        bridge_fail=run(work)
        if bridge_fail.returncode==0 or "display bridge connection marker was not observed" not in bridge_fail.stdout: raise AssertionError(bridge_fail.stdout)
        entry_only=run(work,"entry")
        if entry_only.returncode!=0 or "verified_level=entry" not in entry_only.stdout: raise AssertionError(entry_only.stdout)

        (work/"wine.log").write_text("POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=3 pid=41\nPOCKETPC_DRIVER_LOAD stage=bridge_connected protocol=3 capabilities=23\nPOCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=3 pid_namespace=41\n",encoding="utf-8")
        wrong=run(work)
        if wrong.returncode==0 or "protocol mismatch" not in wrong.stdout: raise AssertionError(wrong.stdout)

        (work/"wine.log").write_text("POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4 pid=41\nPOCKETPC_DRIVER_LOAD stage=bridge_connected protocol=4 capabilities=23\nPOCKETPC_DRIVER_LOAD stage=namespace_failed protocol=4 pid=41 namespace=41\nPOCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4 pid_namespace=41\n",encoding="utf-8")
        contradictory=run(work)
        if contradictory.returncode==0 or "namespace failure marker present" not in contradictory.stdout: raise AssertionError(contradictory.stdout)

        package=json.loads((work/"package.json").read_text()); package["claims"]["driver_loaded"]=True
        (work/"package.json").write_text(json.dumps(package),encoding="utf-8")
        (work/"wine.log").write_text(registered_log(),encoding="utf-8")
        preclaim=run(work,package=True)
        if preclaim.returncode==0 or "must remain false before load verification" not in preclaim.stdout:
            raise AssertionError(preclaim.stdout)

    print("WINE_POCKETPC_DRIVER_LOAD_EVIDENCE_TEST_OK")
    print("artifact_set_precondition=accepted")
    print("full_wine_package_precondition=accepted")
    print("registered_sequence=accepted")
    print("generic_log_false_positive=rejected")
    print("bridge_failure_not_promoted=rejected")
    print("wrong_protocol=rejected")
    print("contradictory_failure=rejected")
    print("false_package_preclaim=rejected")
    print("graphics_registry_selection_proved=false")
    print("physical_validation=false")
    return 0

if __name__=="__main__": raise SystemExit(main())
