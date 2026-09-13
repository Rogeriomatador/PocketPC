#!/usr/bin/env python3
"""Run host Wine PocketPC driver window/surface/input integration smoke.

PASS proves the custom Wine USER driver registered on a host x86_64 Wine build,
registered the display bridge fd with the Wine message queue, created a bridged
top-level window, wrote non-zero BGRA bytes into the broker surface, completed
FRAME_READY/FRAME_PRESENTED ownership, and delivered the broker pointer/key
events back to the Win32 smoke application. It never claims Android, PRoot,
Box64, DXVK/Vulkan, or Roblox execution.
"""

from __future__ import annotations
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from pocketpc_wine_surface_broker import SurfaceSmokeBroker

ROOT=Path(__file__).resolve().parents[1]
LOAD_SCRIPT=ROOT/"scripts/run-wine-pocketpc-driver-load-smoke.py"
spec=importlib.util.spec_from_file_location("pocketpc_driver_load_helpers",LOAD_SCRIPT)
load_helpers=importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(load_helpers)

REQUIRED_LOG_MARKERS=(
    "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=4",
    "POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=4",
    "POCKETPC_DRIVER_LOAD stage=queue_fd_registered protocol=4",
    "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4",
    "POCKETPC_WINE_DRIVER_WINDOW_OK",
    "POCKETPC_WINE_DRIVER_PAINT_OK",
    "POCKETPC_WINE_DRIVER_POINTER_OK",
    "POCKETPC_WINE_DRIVER_KEY_OK",
    "POCKETPC_WINE_DRIVER_INPUT_OK",
    "POCKETPC_WINE_DRIVER_SMOKE_OK",
)

def load_json(path:Path)->dict[str,object]:
    value=json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value,dict):raise RuntimeError(f"JSON is not an object: {path}")
    return value

def main()->int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-root",type=Path,required=True)
    parser.add_argument("--smoke-exe",type=Path,required=True)
    parser.add_argument("--package-evidence",type=Path,required=True)
    parser.add_argument("--work",type=Path,required=True)
    parser.add_argument("--timeout-seconds",type=float,default=35.0)
    args=parser.parse_args()

    work=args.work.resolve()
    if work.exists():raise SystemExit("WINE_WINDOW_SURFACE_SMOKE_WORK_ALREADY_EXISTS")
    work.mkdir(parents=True)
    evidence_path=work/"wine-window-surface-smoke-evidence.json"
    log_path=work/"wine-window-surface.log"
    load_marker_evidence=work/"wine-window-surface-load-marker-evidence.json"
    base={
        "schema":1,
        "status":"NOT_EXECUTED",
        "scope":"host_x86_64_wine_window_surface_input",
        "claims":{
            "prefix_created":False,
            "graphics_registry_configured_pocketpc":False,
            "wine_user_driver_registered":False,
            "driver_queue_fd_registered":False,
            "window_create_observed":False,
            "shared_surface_allocated":False,
            "nonzero_shared_pixels_observed":False,
            "frame_ready_observed":False,
            "frame_presented_ack_sent":False,
            "win32_pointer_event_observed":False,
            "win32_key_event_observed":False,
            "host_surface_input_integration":False,
            "android_executed":False,
            "proot_executed":False,
            "box64_executed":False,
            "dxvk_vulkan_executed":False,
            "roblox_executed":False,
            "physical_validation":False,
        },
    }
    runtime_env:dict[str,str]|None=None
    wine_root:Path|None=None
    wine:Path|None=None
    broker:SurfaceSmokeBroker|None=None

    try:
        package=load_json(args.package_evidence)
        if package.get("status")!="pass" or package.get("scope")!="full_wine_package_driver_static_identity":
            raise RuntimeError("full Wine package static evidence is invalid")
        package_claims=package.get("claims")
        if not isinstance(package_claims,dict):raise RuntimeError("full Wine package claims missing")
        for key in ("driver_loaded","surface_presented","input_round_trip","android_executed","physical_validation"):
            if package_claims.get(key) is not False:
                raise RuntimeError(f"full Wine package pre-claimed {key}")

        wine_root=args.wine_root.resolve()
        wine=wine_root/"bin/wine"
        smoke=args.smoke_exe.resolve()
        if not wine.is_file():raise RuntimeError(f"Wine entrypoint missing: {wine}")
        if not smoke.is_file():raise RuntimeError(f"window smoke executable missing: {smoke}")

        runtime_env,relocation=load_helpers.relocated_wine_env(wine_root,wine,package)
        base["relocation"]=relocation
        prefix=work/"prefix"
        runtime_env.update({"WINEPREFIX":str(prefix),"WINEARCH":"win64","WINEDEBUG":"-all"})
        runtime_env.pop("DISPLAY",None);runtime_env.pop("WAYLAND_DISPLAY",None)

        wineboot=load_helpers.helper(wine_root,"wineboot",wine)
        boot_rc=load_helpers.run_logged(wineboot+["-u"],work,runtime_env,work/"wineboot.log",timeout=90)
        if boot_rc!=0:raise RuntimeError(f"wineboot failed rc={boot_rc}")
        base["claims"]["prefix_created"]=True

        reg=load_helpers.helper(wine_root,"reg",wine)
        add_rc=load_helpers.run_logged(
            reg+["add",r"HKCU\Software\Wine\Drivers","/v","Graphics","/t","REG_SZ","/d","pocketpc","/f"],
            work,runtime_env,work/"registry-add.log",timeout=30)
        if add_rc!=0:raise RuntimeError(f"Wine registry add failed rc={add_rc}")
        query_log=work/"registry-query.log"
        query_rc=load_helpers.run_logged(
            reg+["query",r"HKCU\Software\Wine\Drivers","/v","Graphics"],
            work,runtime_env,query_log,timeout=30)
        if query_rc!=0:raise RuntimeError(f"Wine registry query failed rc={query_rc}")
        if not load_helpers.REGISTRY_RE.search(query_log.read_text(encoding="utf-8",errors="replace")):
            raise RuntimeError("Wine Graphics registry query did not prove pocketpc")
        base["claims"]["graphics_registry_configured_pocketpc"]=True
        load_helpers.stop_wineserver(wine_root,wine,runtime_env,work,"before-surface-smoke")

        token=os.urandom(32)
        identity=load_helpers.sha256(wine).encode("ascii")
        broker=SurfaceSmokeBroker(token,identity)
        broker.start()
        run_env=runtime_env.copy()
        run_env.update({
            "WINEDEBUG":"+pocketpcdrv",
            "POCKETPC_DISPLAY_PROTOCOL":"4",
            "POCKETPC_DISPLAY_SOCKET":broker.socket_name,
            "POCKETPC_DISPLAY_TOKEN":token.hex(),
            "POCKETPC_DISPLAY_RUNTIME_SHA256":identity.decode("ascii"),
            "POCKETPC_DISPLAY_HOST_CAPS":"23",
        })

        with log_path.open("w",encoding="utf-8") as out:
            process=subprocess.Popen(
                [str(wine),str(smoke)],cwd=work,env=run_env,
                stdout=out,stderr=subprocess.STDOUT,text=True)
        deadline=time.monotonic()+max(5.0,args.timeout_seconds)
        timed_out=False
        while process.poll() is None and time.monotonic()<deadline:
            snapshot=broker.snapshot()
            if snapshot["errors"]:
                break
            time.sleep(0.1)
        if process.poll() is None:
            timed_out=True
            process.terminate()
            try:process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill();process.wait(timeout=5)
        return_code=process.returncode
        broker.close()
        snapshot=broker.snapshot()
        base["broker"]=snapshot
        if snapshot["errors"]:raise RuntimeError("surface broker failed: "+" | ".join(str(x) for x in snapshot["errors"]))
        if timed_out:raise RuntimeError("Win32 surface/input smoke timed out")
        if return_code!=0:raise RuntimeError(f"Win32 surface/input smoke exited rc={return_code}")

        text=log_path.read_text(encoding="utf-8",errors="replace")
        missing=[marker for marker in REQUIRED_LOG_MARKERS if marker not in text]
        if missing:raise RuntimeError("required Wine/smoke log markers missing: "+", ".join(missing))
        if int(snapshot["handshakeCount"])<1:raise RuntimeError("no protocol-v4 bridge handshake observed")
        if int(snapshot["windowCreateCount"])<1:raise RuntimeError("no WINDOW_CREATE observed")
        if int(snapshot["surfaceCount"])<1:raise RuntimeError("no SURFACE_REQUEST/SURFACE_AVAILABLE observed")
        if int(snapshot["frameCount"])<1:raise RuntimeError("no FRAME_READY observed")
        if int(snapshot["inputSentCount"])<1:raise RuntimeError("broker did not send pointer/key/frame ACK")
        if snapshot["pixelsObserved"] is not True:raise RuntimeError("no non-zero shared surface pixels observed")

        verifier=ROOT/"scripts/verify-wine-pocketpc-driver-load-evidence.py"
        verify=subprocess.run(
            [sys.executable,str(verifier),"--log",str(log_path),"--package-evidence",str(args.package_evidence),
             "--evidence",str(load_marker_evidence),"--expect","registered"],
            cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=False)
        (work/"load-verifier.log").write_text(verify.stdout,encoding="utf-8")
        if verify.returncode!=0:raise RuntimeError("driver load-evidence verifier rejected surface smoke log")

        base["status"]="PASS_HOST_WINE_WINDOW_SURFACE_INPUT_NOT_ANDROID"
        claims=base["claims"]
        claims["wine_user_driver_registered"]=True
        claims["driver_queue_fd_registered"]=True
        claims["window_create_observed"]=True
        claims["shared_surface_allocated"]=True
        claims["nonzero_shared_pixels_observed"]=True
        claims["frame_ready_observed"]=True
        claims["frame_presented_ack_sent"]=True
        claims["win32_pointer_event_observed"]=True
        claims["win32_key_event_observed"]=True
        claims["host_surface_input_integration"]=True
        base["wine"]={"path":str(wine),"sha256":load_helpers.sha256(wine)}
        base["smokeExe"]={"path":str(smoke),"sha256":load_helpers.sha256(smoke)}
        base["markerEvidence"]=str(load_marker_evidence)
        evidence_path.write_text(json.dumps(base,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    except Exception as exc:
        if broker is not None:
            try:broker.close()
            except Exception:pass
        base["status"]="FAILED"
        base["failure"]=f"{exc.__class__.__name__}:{exc}"
        evidence_path.write_text(json.dumps(base,indent=2,sort_keys=True)+"\n",encoding="utf-8")
        print("WINE_POCKETPC_WINDOW_SURFACE_SMOKE_FAILED="+str(base["failure"]))
        print(f"evidence={evidence_path}")
        return 1
    finally:
        if runtime_env is not None and wine_root is not None and wine is not None:
            load_helpers.stop_wineserver(wine_root,wine,runtime_env,work,"final")

    print("WINE_POCKETPC_WINDOW_SURFACE_SMOKE_OK")
    print("wine_user_driver_registered=true")
    print("driver_queue_fd_registered=true")
    print("window_create_observed=true")
    print("nonzero_shared_pixels_observed=true")
    print("frame_ready_and_presented_ack=true")
    print("win32_pointer_round_trip=true")
    print("win32_key_round_trip=true")
    print("host_surface_input_integration=true")
    print("android_executed=false")
    print("proot_executed=false")
    print("box64_executed=false")
    print("dxvk_vulkan_executed=false")
    print("roblox_executed=false")
    print("physical_validation=false")
    print(f"evidence={evidence_path}")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
