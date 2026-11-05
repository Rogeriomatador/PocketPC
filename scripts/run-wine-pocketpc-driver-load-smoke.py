#!/usr/bin/env python3
"""Run a host-x86_64 Wine smoke that proves winepocketpc.drv registration only.

The smoke intentionally stops immediately after the driver registers with
win32u. It does not claim window surface presentation, input round-trip,
Android execution, DXVK/Vulkan, Box64, or Roblox.
"""

from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import struct
import subprocess
import sys
import threading
import time

MAGIC=0x31424450
VERSION=4
CAPABILITIES=23
HELLO_BYTES=100
REGISTERED="POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4"
REGISTRY_RE=re.compile(r"\bGraphics\b.*\bREG_SZ\b.*\bpocketpc\b", re.IGNORECASE)

def sha256(path: Path) -> str:
    h=hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda:f.read(1024*1024), b""):
            h.update(block)
    return h.hexdigest()

def load_json(path: Path) -> dict[str, object]:
    value=json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value,dict):
        raise RuntimeError(f"JSON is not an object: {path}")
    return value

def read_exact(conn: socket.socket, size: int) -> bytes:
    out=bytearray()
    while len(out)<size:
        block=conn.recv(size-len(out))
        if not block:
            raise RuntimeError("bridge peer closed during read")
        out.extend(block)
    return bytes(out)

def read_frame(conn: socket.socket) -> tuple[int,int,bytes]:
    header=read_exact(conn,20)
    magic,version,msg_type,size,sequence=struct.unpack("<IHHIQ",header)
    if magic!=MAGIC or version!=VERSION or size>1024*1024:
        raise RuntimeError("invalid display bridge frame header")
    return msg_type,sequence,read_exact(conn,size)

def write_frame(conn: socket.socket, msg_type: int, sequence: int, payload: bytes=b"") -> None:
    conn.sendall(struct.pack("<IHHIQ",MAGIC,VERSION,msg_type,len(payload),sequence)+payload)

class RegistrationBroker:
    def __init__(self, token: bytes, identity: bytes):
        if len(token)!=32 or len(identity)!=64:
            raise ValueError("broker token/identity size invalid")
        self.token=token
        self.identity=identity
        self.socket_name="pocketpc.wine.load."+os.urandom(8).hex()
        self.server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
        self.server.bind("\0"+self.socket_name)
        self.server.listen(1)
        self.server.settimeout(0.25)
        self.stop=threading.Event()
        self.handshake=threading.Event()
        self.error: str|None=None
        self.thread=threading.Thread(target=self._run,daemon=True)

    def start(self)->None:
        self.thread.start()

    def close(self)->None:
        self.stop.set()
        try: self.server.close()
        except OSError: pass
        self.thread.join(timeout=2)

    def _run(self)->None:
        conn=None
        try:
            while not self.stop.is_set():
                try:
                    conn,_=self.server.accept()
                    break
                except TimeoutError:
                    continue
                except OSError:
                    if self.stop.is_set(): return
                    raise
            if conn is None: return
            with conn:
                conn.settimeout(0.25)
                msg_type,sequence,payload=read_frame(conn)
                if (msg_type,sequence)!=(1,0):
                    raise RuntimeError(f"unexpected HELLO frame type={msg_type} seq={sequence}")
                if len(payload)!=HELLO_BYTES:
                    raise RuntimeError(f"HELLO payload size invalid: {len(payload)}")
                if payload[:32]!=self.token:
                    raise RuntimeError("HELLO token mismatch")
                if payload[32:96]!=self.identity:
                    raise RuntimeError("HELLO runtime identity mismatch")
                guest_caps=struct.unpack("<I",payload[96:])[0]
                if guest_caps & CAPABILITIES != CAPABILITIES:
                    raise RuntimeError(f"guest capabilities incomplete: {guest_caps}")
                write_frame(conn,2,0,struct.pack("<I",CAPABILITIES))
                self.handshake.set()
                while not self.stop.is_set():
                    try:
                        read_frame(conn)
                    except TimeoutError:
                        continue
                    except (OSError,RuntimeError):
                        if self.stop.is_set(): return
                        return
        except Exception as exc:
            self.error=f"{exc.__class__.__name__}:{exc}"
            self.handshake.set()

def run_logged(argv:list[str], cwd:Path, env:dict[str,str], log:Path, timeout:float=60.0)->int:
    with log.open("w",encoding="utf-8") as out:
        try:
            result=subprocess.run(argv,cwd=cwd,env=env,stdout=out,stderr=subprocess.STDOUT,text=True,timeout=timeout,check=False)
            return result.returncode
        except subprocess.TimeoutExpired:
            return 124

def helper(wine_root:Path,name:str,wine:Path)->list[str]:
    direct=wine_root/"bin"/name
    if direct.is_file():
        return [str(direct)]
    return [str(wine),name+".exe"]

def relocated_wine_env(
    wine_root: Path,
    wine: Path,
    package: dict[str, object],
) -> tuple[dict[str,str], dict[str,object]]:
    artifacts=package.get("artifacts")
    if not isinstance(artifacts,dict):
        raise RuntimeError("full Wine package artifact map is missing")
    pe_info=artifacts.get("winepocketpc.drv")
    unix_info=artifacts.get("winepocketpc.so")
    if not isinstance(pe_info,dict) or not isinstance(unix_info,dict):
        raise RuntimeError("full Wine package driver paths are missing")
    pe_rel=pe_info.get("path")
    unix_rel=unix_info.get("path")
    if not isinstance(pe_rel,str) or not isinstance(unix_rel,str):
        raise RuntimeError("full Wine package driver paths are invalid")

    pe_dir=(wine_root/pe_rel).resolve().parent
    unix_dir=(wine_root/unix_rel).resolve().parent
    for candidate in (pe_dir,unix_dir):
        try:
            candidate.relative_to(wine_root)
        except ValueError as error:
            raise RuntimeError("Wine DLL search path escapes verified Wine root") from error

    dll_dirs=[]
    for candidate in (
        unix_dir,
        pe_dir,
        wine_root/"lib/wine",
    ):
        candidate=candidate.resolve()
        if candidate.is_dir() and candidate not in dll_dirs:
            dll_dirs.append(candidate)
    if not dll_dirs:
        raise RuntimeError("no relocatable Wine DLL directories found")

    native_dirs=[]
    for candidate in (
        wine_root/"lib",
        wine_root/"lib64",
        unix_dir.parent.parent,
    ):
        candidate=candidate.resolve()
        if candidate.is_dir() and candidate not in native_dirs:
            native_dirs.append(candidate)

    env=os.environ.copy()
    old_path=env.get("PATH","")
    env["PATH"]=str(wine_root/"bin")+(os.pathsep+old_path if old_path else "")
    env["WINELOADER"]=str(wine)
    wineserver=wine_root/"bin/wineserver"
    if wineserver.is_file():
        env["WINESERVER"]=str(wineserver)
    old_dll=env.get("WINEDLLPATH","")
    env["WINEDLLPATH"]=os.pathsep.join(str(item) for item in dll_dirs)+(os.pathsep+old_dll if old_dll else "")
    if native_dirs:
        old_ld=env.get("LD_LIBRARY_PATH","")
        env["LD_LIBRARY_PATH"]=os.pathsep.join(str(item) for item in native_dirs)+(os.pathsep+old_ld if old_ld else "")

    details={
        "wineRoot":str(wine_root),
        "wineLoader":str(wine),
        "wineServer":env.get("WINESERVER"),
        "wineDllPath":[str(item) for item in dll_dirs],
        "nativeLibraryPath":[str(item) for item in native_dirs],
        "verifiedPeDirectory":str(pe_dir),
        "verifiedUnixDirectory":str(unix_dir),
    }
    return env,details

def stop_wineserver(wine_root:Path,wine:Path,env:dict[str,str],work:Path,label:str)->None:
    command=helper(wine_root,"wineserver",wine)
    try:
        run_logged(command+["-k"],work,env,work/f"wineserver-{label}.log",timeout=15)
    except Exception:
        pass

def main()->int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-root",type=Path,required=True)
    parser.add_argument("--smoke-exe",type=Path,required=True)
    parser.add_argument("--package-evidence",type=Path,required=True)
    parser.add_argument("--work",type=Path,required=True)
    parser.add_argument("--timeout-seconds",type=float,default=20.0)
    args=parser.parse_args()

    work=args.work.resolve()
    if work.exists():
        raise SystemExit("WINE_DRIVER_LOAD_SMOKE_WORK_ALREADY_EXISTS")
    work.mkdir(parents=True)
    evidence_path=work/"wine-driver-load-smoke-evidence.json"
    log_path=work/"wine-driver-load.log"
    load_evidence=work/"wine-driver-load-marker-evidence.json"

    base={
        "schema":2,
        "status":"NOT_EXECUTED",
        "scope":"host_x86_64_wine_driver_registration",
        "claims":{
            "prefix_created":False,
            "graphics_registry_configured_pocketpc":False,
            "display_bridge_handshake":False,
            "wine_user_driver_registered":False,
            "surface_presented":False,
            "input_round_trip":False,
            "android_executed":False,
            "box64_executed":False,
            "dxvk_vulkan_executed":False,
            "roblox_executed":False,
            "physical_validation":False,
        },
    }
    runtime_env: dict[str,str]|None=None
    wine_root: Path|None=None
    wine: Path|None=None

    try:
        package=load_json(args.package_evidence)
        if package.get("status")!="pass" or package.get("scope")!="full_wine_package_driver_static_identity":
            raise RuntimeError("full Wine package static evidence is not valid")
        claims=package.get("claims")
        if not isinstance(claims,dict) or claims.get("driver_loaded") is not False:
            raise RuntimeError("full Wine package evidence pre-claimed driver load")

        wine_root=args.wine_root.resolve()
        wine=wine_root/"bin/wine"
        smoke_exe=args.smoke_exe.resolve()
        if not wine.is_file():
            raise RuntimeError(f"Wine entrypoint missing: {wine}")
        if not smoke_exe.is_file():
            raise RuntimeError(f"Win64 smoke executable missing: {smoke_exe}")

        runtime_env,relocation=relocated_wine_env(wine_root,wine,package)
        base["relocation"]=relocation

        prefix=work/"prefix"
        runtime_env.update({"WINEPREFIX":str(prefix),"WINEARCH":"win64","WINEDEBUG":"-all"})
        runtime_env.pop("DISPLAY",None)
        runtime_env.pop("WAYLAND_DISPLAY",None)

        wineboot=helper(wine_root,"wineboot",wine)
        boot_rc=run_logged(wineboot+["-u"],work,runtime_env,work/"wineboot.log",timeout=90)
        if boot_rc!=0:
            raise RuntimeError(f"wineboot failed rc={boot_rc}")
        base["claims"]["prefix_created"]=True

        reg=helper(wine_root,"reg",wine)
        add_rc=run_logged(
            reg+["add",r"HKCU\Software\Wine\Drivers","/v","Graphics","/t","REG_SZ","/d","pocketpc","/f"],
            work,runtime_env,work/"registry-add.log",timeout=30)
        if add_rc!=0:
            raise RuntimeError(f"Wine registry add failed rc={add_rc}")

        query_log=work/"registry-query.log"
        query_rc=run_logged(
            reg+["query",r"HKCU\Software\Wine\Drivers","/v","Graphics"],
            work,runtime_env,query_log,timeout=30)
        if query_rc!=0:
            raise RuntimeError(f"Wine registry query failed rc={query_rc}")
        query=query_log.read_text(encoding="utf-8",errors="replace")
        if not REGISTRY_RE.search(query):
            raise RuntimeError("Wine Graphics registry query did not prove pocketpc")
        base["claims"]["graphics_registry_configured_pocketpc"]=True

        stop_wineserver(wine_root,wine,runtime_env,work,"before-smoke")

        token=os.urandom(32)
        identity=sha256(wine).encode("ascii")
        broker=RegistrationBroker(token,identity)
        broker.start()

        run_env=runtime_env.copy()
        run_env.update({
            "WINEDEBUG":"+pocketpcdrv",
            "POCKETPC_DISPLAY_PROTOCOL":"4",
            "POCKETPC_DISPLAY_SOCKET":broker.socket_name,
            "POCKETPC_DISPLAY_TOKEN":token.hex(),
            "POCKETPC_DISPLAY_RUNTIME_SHA256":identity.decode("ascii"),
            "POCKETPC_DISPLAY_HOST_CAPS":str(CAPABILITIES),
        })

        with log_path.open("w",encoding="utf-8") as out:
            process=subprocess.Popen(
                [str(wine),str(smoke_exe)],
                cwd=work,env=run_env,stdout=out,stderr=subprocess.STDOUT,text=True)
        registered=False
        deadline=time.monotonic()+max(1.0,args.timeout_seconds)
        while time.monotonic()<deadline:
            if broker.error:
                break
            if log_path.exists() and REGISTERED in log_path.read_text(encoding="utf-8",errors="replace"):
                registered=True
                break
            if process.poll() is not None:
                break
            time.sleep(0.1)

        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)

        broker.close()
        base["claims"]["display_bridge_handshake"]=broker.handshake.is_set() and broker.error is None
        if broker.error:
            raise RuntimeError("display bridge broker failed: "+broker.error)
        if not registered:
            raise RuntimeError("Wine did not emit user_driver_registered before timeout/exit")

        verifier=Path(__file__).resolve().parent/"verify-wine-pocketpc-driver-load-evidence.py"
        verify=subprocess.run(
            [sys.executable,str(verifier),"--log",str(log_path),
             "--package-evidence",str(args.package_evidence),"--evidence",str(load_evidence),"--expect","registered"],
            cwd=Path(__file__).resolve().parents[1],
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=False)
        (work/"load-verifier.log").write_text(verify.stdout,encoding="utf-8")
        if verify.returncode!=0:
            raise RuntimeError("load marker verifier rejected Wine log")

        base["status"]="PASS_HOST_WINE_DRIVER_REGISTERED_NOT_SURFACE_TESTED"
        base["claims"]["wine_user_driver_registered"]=True
        base["wine"]={"path":str(wine),"sha256":sha256(wine)}
        base["smokeExe"]={"path":str(smoke_exe),"sha256":sha256(smoke_exe)}
        base["markerEvidence"]=str(load_evidence)
        evidence_path.write_text(json.dumps(base,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    except Exception as exc:
        base["status"]="FAILED"
        base["failure"]=f"{exc.__class__.__name__}:{exc}"
        evidence_path.write_text(json.dumps(base,indent=2,sort_keys=True)+"\n",encoding="utf-8")
        print("WINE_POCKETPC_DRIVER_LOAD_SMOKE_FAILED="+str(base["failure"]))
        print(f"evidence={evidence_path}")
        return 1
    finally:
        if runtime_env is not None and wine_root is not None and wine is not None:
            stop_wineserver(wine_root,wine,runtime_env,work,"final")

    print("WINE_POCKETPC_DRIVER_LOAD_SMOKE_OK")
    print("wine_user_driver_registered=true")
    print("graphics_registry_configured_pocketpc=true")
    print("surface_presented=false")
    print("input_round_trip=false")
    print("android_executed=false")
    print("box64_executed=false")
    print("dxvk_vulkan_executed=false")
    print("roblox_executed=false")
    print("physical_validation=false")
    print(f"evidence={evidence_path}")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
