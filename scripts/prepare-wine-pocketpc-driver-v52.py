#!/usr/bin/env python3
"""Prepare the experimental PocketPC Wine driver through private Vulkan ABI v52.

Stages:
  1. complete v51 source overlay (official one-shot baseline)
  2. v52 continuous-Present source overlay gated by
     POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1
  3. compile-safe v52 gate forward-declaration normalization

This preparer is deliberately NOT selected by the official Wine build workflow.
Its output is source evidence only until Wine compiles and the odd/even ownership
protocol is exercised with the Android host on real Vulkan hardware.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V51_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v51.py"
V52_OVERLAY = ROOT / "scripts/prepare-wine-pocketpc-continuous-present-v52.py"
V52_COMPILE_FIX = ROOT / "scripts/prepare-wine-pocketpc-v52-compile-fix.py"


def run_stage(script: Path, wine_source: Path, evidence: Path) -> None:
    if not script.is_file():
        raise SystemExit(f"WINE_V52_PREPARER_MISSING:{script}")
    result = subprocess.run(
        [
            sys.executable,
            str(script),
            "--wine-source",
            str(wine_source),
            "--evidence",
            str(evidence),
        ],
        cwd=ROOT,
        text=True,
    )
    if result.returncode:
        raise SystemExit(
            f"WINE_V52_PREPARER_STAGE_FAILED:{script.name}:{result.returncode}"
        )


def require_v51(data: dict[str, object]) -> None:
    if data.get("privateWineVulkanAbi") != 51:
        raise SystemExit("WINE_V52_INPUT_ABI_NOT_51")
    if data.get("protocolVersion") != 4:
        raise SystemExit("WINE_V52_INPUT_DRIVER_PROTOCOL_INVALID")
    if data.get("driverName") != "winepocketpc.drv":
        raise SystemExit("WINE_V52_INPUT_DRIVER_NAME_INVALID")
    if data.get("surfaceCallbackImplemented") is not True:
        raise SystemExit("WINE_V52_INPUT_SURFACE_CALLBACK_MISSING")
    if data.get("inputInjectionImplemented") is not True:
        raise SystemExit("WINE_V52_INPUT_INPUT_INJECTION_MISSING")
    if data.get("pixelCopyImplemented") is not True:
        raise SystemExit("WINE_V52_INPUT_PIXEL_COPY_SOURCE_MISSING")
    if data.get("pixelCopyExecuted") is not False:
        raise SystemExit("WINE_V52_INPUT_COPY_EXECUTION_MUST_REMAIN_FALSE")
    ack = data.get("presentCopyCompletedAck")
    if not isinstance(ack, dict) or ack.get("stage") != 6:
        raise SystemExit("WINE_V52_INPUT_STAGE6_ACK_MISSING")
    if ack.get("implemented") is not True or ack.get("executed") is not False:
        raise SystemExit("WINE_V52_INPUT_STAGE6_CLASSIFICATION_INVALID")
    for key in (
        "hostVisiblePresentImplemented",
        "robloxExecuted",
    ):
        if data.get(key) is not False:
            raise SystemExit(f"WINE_V52_INPUT_FAIL_CLOSED_VIOLATION:{key}")


def require_v52(data: dict[str, object]) -> None:
    if data.get("schemaVersion") != 1:
        raise SystemExit("WINE_V52_OVERLAY_SCHEMA_INVALID")
    if data.get("privateWineVulkanAbi") != 52:
        raise SystemExit("WINE_V52_OVERLAY_ABI_INVALID")
    if data.get("derivedFromPrivateAbi") != 51:
        raise SystemExit("WINE_V52_OVERLAY_BASE_ABI_INVALID")
    if data.get("environmentGate") != "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1":
        raise SystemExit("WINE_V52_OVERLAY_ENV_GATE_INVALID")
    if data.get("officialBuildSelected") is not False:
        raise SystemExit("WINE_V52_OVERLAY_MUST_NOT_BE_OFFICIAL")

    source = data.get("sourceIntegration")
    if not isinstance(source, dict):
        raise SystemExit("WINE_V52_OVERLAY_SOURCE_EVIDENCE_MISSING")
    required_source = (
        "helperFilesCarried",
    )
    if any(source.get(key) is not True for key in required_source):
        raise SystemExit("WINE_V52_OVERLAY_HELPER_SOURCE_MISSING")

    ownership = data.get("timelineOwnership")
    if not isinstance(ownership, dict):
        raise SystemExit("WINE_V52_TIMELINE_OWNERSHIP_MISSING")
    required_true = (
        "frameSequenceSeparateFromOfferSequence",
        "guestWaitsPreviousHostConsumedEven",
        "guestSignalsReadyOddFromCopyQueueSubmit",
    )
    if any(ownership.get(key) is not True for key in required_true):
        raise SystemExit("WINE_V52_TIMELINE_OWNERSHIP_INCOMPLETE")
    if ownership.get("guestSignalsHostConsumedEven") is not False:
        raise SystemExit("WINE_V52_GUEST_MUST_NOT_SIGNAL_HOST_CONSUMED")
    if ownership.get("postPresentCallbackMutatesTimelineInV52") is not False:
        raise SystemExit("WINE_V52_POST_PRESENT_TIMELINE_MUTATION_FORBIDDEN")

    ordering = data.get("presentOrdering")
    if not isinstance(ordering, dict) or any(
        ordering.get(key) is not True
        for key in (
            "usesExactSelectedSwapchainImage",
            "consumesOriginalPresentWaitsOnce",
            "replacementBinaryPresentWait",
            "copyAndGuestReadySignalSameQueueSubmit",
            "pvi1ReleasedToExternalBeforeGuestReadySignalCompletes",
            "nextFrameBlockedUntilPreviousHostConsumed",
        )
    ):
        raise SystemExit("WINE_V52_PRESENT_ORDERING_INCOMPLETE")

    for key in (
        "compiled",
        "runtimeExecuted",
        "hostVisiblePresentValidated",
        "robloxExecuted",
        "robloxRendered",
        "robloxPlayable",
    ):
        if data.get(key) is not False:
            raise SystemExit(f"WINE_V52_PREMATURE_EXECUTION_CLAIM:{key}")


def require_compile_fix(data: dict[str, object]) -> None:
    if data.get("schemaVersion") != 1:
        raise SystemExit("WINE_V52_COMPILE_FIX_SCHEMA_INVALID")
    if data.get("privateWineVulkanAbi") != 52:
        raise SystemExit("WINE_V52_COMPILE_FIX_ABI_INVALID")
    source = data.get("sourceIntegration")
    if not isinstance(source, dict):
        raise SystemExit("WINE_V52_COMPILE_FIX_SOURCE_MISSING")
    if source.get("continuousPresentGateForwardDeclaredBeforeQueueCallback") is not True:
        raise SystemExit("WINE_V52_COMPILE_FIX_FORWARD_DECLARATION_MISSING")
    if source.get("continuousPresentGateStaticDefinitionRetained") is not True:
        raise SystemExit("WINE_V52_COMPILE_FIX_STATIC_DEFINITION_MISSING")
    if source.get("declarationCount") != 1 or source.get("definitionCount") != 1:
        raise SystemExit("WINE_V52_COMPILE_FIX_GATE_CARDINALITY_INVALID")
    for key in ("compiled", "runtimeExecuted", "physicalVisibleFrame", "robloxExecuted"):
        if data.get(key) is not False:
            raise SystemExit(f"WINE_V52_COMPILE_FIX_PREMATURE_CLAIM:{key}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    wine_source = args.wine_source.resolve()
    if not wine_source.is_dir():
        raise SystemExit(f"WINE_V52_SOURCE_MISSING:{wine_source}")

    with tempfile.TemporaryDirectory(prefix="pocketpc-wine-v52-") as temp:
        temp_root = Path(temp)
        v51_path = temp_root / "v51.json"
        v52_path = temp_root / "v52-overlay.json"
        compile_fix_path = temp_root / "v52-compile-fix.json"

        run_stage(V51_PREPARER, wine_source, v51_path)
        v51 = json.loads(v51_path.read_text(encoding="utf-8"))
        require_v51(v51)

        run_stage(V52_OVERLAY, wine_source, v52_path)
        v52 = json.loads(v52_path.read_text(encoding="utf-8"))
        require_v52(v52)

        run_stage(V52_COMPILE_FIX, wine_source, compile_fix_path)
        compile_fix = json.loads(compile_fix_path.read_text(encoding="utf-8"))
        require_compile_fix(compile_fix)

    combined = {
        "schemaVersion": 1,
        "status": "POCKETPC_WINE_DRIVER_V52_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 52,
        "derivedFromPrivateAbi": 51,
        "officialBuildSelected": False,
        "activationEnvironment": "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1",
        "v51FallbackRetained": True,
        "continuousPresentSourceImplemented": True,
        "protocolVersion": v51["protocolVersion"],
        "driverName": v51["driverName"],
        "surfaceCallbackImplemented": v51["surfaceCallbackImplemented"],
        "inputInjectionImplemented": v51["inputInjectionImplemented"],
        "timelineOwnership": v52["timelineOwnership"],
        "presentOrdering": v52["presentOrdering"],
        "sourceIntegration": {
            **v52["sourceIntegration"],
            "continuousPresentGateForwardDeclaredBeforeQueueCallback": True,
        },
        "compileSourceNormalization": {
            "continuousPresentGateForwardDeclaredBeforeQueueCallback": True,
            "continuousPresentGateStaticDefinitionRetained": True,
            "declarationCount": 1,
            "definitionCount": 1,
            "compiled": False,
            "executed": False,
        },
        "helperFiles": v52["helperFiles"],
        "files": {
            **v52["files"],
            "compileFixDriverAfterSha256": compile_fix["files"]["driverAfterSha256"],
        },
        "compiled": False,
        "runtimeExecuted": False,
        "integrationExecuted": False,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "robloxRendered": False,
        "robloxPlayable": False,
        "baselineEvidence": {
            "v51Status": v51.get("status"),
            "v51PixelCopyImplemented": v51.get("pixelCopyImplemented"),
            "v51PixelCopyExecuted": v51.get("pixelCopyExecuted"),
            "v51PresentCopyCompletedAck": v51.get("presentCopyCompletedAck"),
        },
        "v52OverlayEvidence": {
            "status": v52.get("status"),
            "environmentGate": v52.get("environmentGate"),
            "notExecuted": v52.get("notExecuted"),
            "compileFixStatus": compile_fix.get("status"),
            "compileFixNotExecuted": compile_fix.get("notExecuted"),
        },
        "notExecuted": [
            "Wine v52 compilation after source normalization",
            "Wine v52 link/package",
            "continuous odd/even PVS1 runtime exchange",
            "multi-frame Android host readback loop",
            "Compose physical visible frame validation",
            "Roblox Desktop execution",
        ],
    }

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(
        json.dumps(combined, indent=2) + "\n",
        encoding="utf-8",
    )

    print("POCKETPC_WINE_DRIVER_V52_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=52")
    print("official_build_selected=false")
    print("continuous_present_source_implemented=true")
    print("continuous_present_gate_forward_declared=true")
    print("compiled=false")
    print("runtime_executed=false")
    print("physical_visible_frame=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
