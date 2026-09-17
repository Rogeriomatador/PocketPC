#!/usr/bin/env python3
"""Static guard for the host Wine window/surface/input integration smoke."""

from __future__ import annotations
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]
BROKER=ROOT/"scripts/pocketpc_wine_surface_broker.py"
BROKER_TEST=ROOT/"scripts/test-wine-pocketpc-surface-broker.py"
RUNNER=ROOT/"scripts/run-wine-pocketpc-window-surface-smoke.py"
WORKFLOW=ROOT/".github/workflows/wine-x86_64-build.yml"
WINDOW_SMOKE_SOURCE=ROOT/"scripts/build-wine-x86_64.py"

def require(failures:list[str],label:str,text:str,values:tuple[str,...])->None:
    for value in values:
        if value not in text:failures.append(f"{label} missing: {value}")

def main()->int:
    failures=[]
    for path in (BROKER,BROKER_TEST,RUNNER,WORKFLOW,WINDOW_SMOKE_SOURCE):
        if not path.is_file():failures.append(f"missing required file: {path.relative_to(ROOT)}")
    if failures:
        print("WINE_POCKETPC_WINDOW_SURFACE_POLICY_FAILED",file=sys.stderr)
        for failure in failures:print("- "+failure,file=sys.stderr)
        return 1

    broker=BROKER.read_text(encoding="utf-8")
    test=BROKER_TEST.read_text(encoding="utf-8")
    runner=RUNNER.read_text(encoding="utf-8")
    workflow=WORKFLOW.read_text(encoding="utf-8")
    smoke=WINDOW_SMOKE_SOURCE.read_text(encoding="utf-8")

    require(failures,"surface broker",broker,(
        "REQUIRED_CAPS=23",
        "MSG_WINDOW_CREATE=10",
        "MSG_SURFACE_REQUEST=19",
        "MSG_SURFACE_AVAILABLE=20",
        "MSG_FRAME_READY=21",
        "MSG_POINTER_EVENT=30",
        "MSG_KEY_EVENT=31",
        "MSG_FRAME_PRESENTED=40",
        "FRAME_READY arrived but shared surface remained all zero",
        "guest sequence mismatch",
        "HELLO token mismatch",
        "HELLO runtime identity mismatch",
        "input_sent_count",
        "pixels_observed",
    ))
    require(failures,"surface broker tests",test,(
        "protocol_v4_handshake=accepted",
        "window_create=accepted",
        "surface_available=emitted",
        "nonzero_shared_pixels=observed",
        "pointer_key_and_frame_ack=emitted",
        "window_destroy=accepted",
        "wrong_token=rejected",
        "wine_execution=false",
        "physical_validation=false",
    ))
    require(failures,"Win32 window smoke",smoke,(
        "POCKETPC_WINE_DRIVER_WINDOW_OK",
        "POCKETPC_WINE_DRIVER_PAINT_OK",
        "POCKETPC_WINE_DRIVER_POINTER_OK",
        "POCKETPC_WINE_DRIVER_KEY_OK",
        "POCKETPC_WINE_DRIVER_INPUT_OK",
        "POCKETPC_WINE_DRIVER_SMOKE_OK",
    ))
    require(failures,"surface integration runner",runner,(
        "REQUIRED_LOG_MARKERS",
        "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=4",
        "POCKETPC_WINE_DRIVER_SMOKE_OK",
        'snapshot["pixelsObserved"] is not True',
        "no FRAME_READY observed",
        "broker did not send pointer/key/frame ACK",
        'claims["host_surface_input_integration"]=True',
        '"android_executed":False',
        '"proot_executed":False',
        '"box64_executed":False',
        '"dxvk_vulkan_executed":False',
        '"roblox_executed":False',
        '"physical_validation":False',
    ))
    require(failures,"full Wine workflow",workflow,(
        "scripts/test-wine-pocketpc-surface-broker.py",
        "scripts/test-wine-pocketpc-window-surface-policy.py",
        "scripts/run-wine-pocketpc-window-surface-smoke.py",
        "Prove host Wine loads and registers winepocketpc.drv",
        "Prove host Wine window surface and input round-trip",
        "pocketpc-wine-window-surface-smoke/*.json",
        "pocketpc-wine-window-surface-smoke/*.log",
    ))

    registration=workflow.find("Prove host Wine loads and registers winepocketpc.drv")
    surface=workflow.find("Prove host Wine window surface and input round-trip")
    if registration<0 or surface<=registration:
        failures.append("surface/input integration must run after driver registration smoke")

    if failures:
        print("WINE_POCKETPC_WINDOW_SURFACE_POLICY_FAILED",file=sys.stderr)
        for failure in failures:print("- "+failure,file=sys.stderr)
        return 1
    print("WINE_POCKETPC_WINDOW_SURFACE_POLICY_OK")
    print("nonzero_framebuffer_required=true")
    print("frame_ack_required=true")
    print("win32_pointer_key_round_trip_required=true")
    print("runs_after_driver_registration=true")
    print("android_execution=false")
    print("physical_validation=false")
    return 0

if __name__=="__main__":raise SystemExit(main())
