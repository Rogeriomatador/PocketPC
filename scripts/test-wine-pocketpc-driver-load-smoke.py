#!/usr/bin/env python3
"""Pure tests for the host Wine driver-load smoke setup; does not execute Wine."""

from __future__ import annotations
import importlib.util
import os
from pathlib import Path
import tempfile

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/"scripts/run-wine-pocketpc-driver-load-smoke.py"
spec=importlib.util.spec_from_file_location("pocketpc_load_smoke_setup",SCRIPT)
module=importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

def package(pe_path:str,unix_path:str)->dict[str,object]:
    return {
        "artifacts":{
            "winepocketpc.drv":{"path":pe_path},
            "winepocketpc.so":{"path":unix_path},
        }
    }

def main()->int:
    with tempfile.TemporaryDirectory(prefix="pocketpc-wine-relocation-test-") as temp:
        root=Path(temp)/"wine"
        wine=root/"bin/wine"
        server=root/"bin/wineserver"
        pe=root/"lib/wine/x86_64-windows/winepocketpc.drv"
        unix=root/"lib/wine/x86_64-unix/winepocketpc.so"
        for path in (wine,server,pe,unix):
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(b"x")

        env,details=module.relocated_wine_env(
            root.resolve(),
            wine.resolve(),
            package(
                "lib/wine/x86_64-windows/winepocketpc.drv",
                "lib/wine/x86_64-unix/winepocketpc.so",
            ),
        )
        assert env["WINELOADER"]==str(wine.resolve())
        assert env["WINESERVER"]==str(server.resolve())
        dlls=env["WINEDLLPATH"].split(os.pathsep)
        assert str(pe.parent.resolve()) in dlls
        assert str(unix.parent.resolve()) in dlls
        assert env["PATH"].split(os.pathsep)[0]==str((root/"bin").resolve())
        assert details["verifiedPeDirectory"]==str(pe.parent.resolve())
        assert details["verifiedUnixDirectory"]==str(unix.parent.resolve())

        escaped=False
        try:
            module.relocated_wine_env(
                root.resolve(),
                wine.resolve(),
                package(
                    "lib/wine/x86_64-windows/winepocketpc.drv",
                    "../../outside/winepocketpc.so",
                ),
            )
        except RuntimeError as error:
            escaped="escapes verified Wine root" in str(error)
        assert escaped

        assert module.REGISTRY_RE.search("    Graphics    REG_SZ    pocketpc")
        assert module.REGISTRY_RE.search("Graphics REG_SZ pocketpc")
        assert module.REGISTRY_RE.search("Graphics REG_SZ x11") is None

    print("WINE_POCKETPC_DRIVER_LOAD_SMOKE_SETUP_TEST_OK")
    print("relocated_winedllpath=guarded")
    print("wine_loader_and_server=guarded")
    print("driver_path_escape=rejected")
    print("graphics_registry_match=guarded")
    print("wine_execution=false")
    print("physical_validation=false")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
