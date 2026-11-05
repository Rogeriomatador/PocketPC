#!/usr/bin/env python3
"""Prepare the complete PocketPC Wine driver through private Vulkan ABI v51.

Stages:
  1. v49 base driver/lifecycle overlay
  2. v50 exact presented-image + external ownership overlay
  3. v51 one-shot pre-Present GPU copy overlay
  4. ordered PGA1 stage-6 copy-completed acknowledgement
  5. compile-safe source normalization against the canonical PGT1 contract

The combined evidence remains fail-closed: source integration never implies
compilation, runtime execution, Android-visible presentation or Roblox support.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
V50_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v50.py"
V51_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-pre-present-copy.py"
COPY_ACK_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-present-copy-ack.py"
COMPILE_FIX_PREPARER = ROOT / "scripts/prepare-wine-pocketpc-v51-compile-fix.py"


def run_stage(script: Path, wine_source: Path, evidence: Path) -> None:
    if not script.is_file():
        raise SystemExit(f"WINE_V51_PREPARER_MISSING:{script}")
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
            f"WINE_V51_PREPARER_STAGE_FAILED:{script.name}:{result.returncode}"
        )


def require_v50(data: dict[str, object]) -> None:
    if data.get("privateWineVulkanAbi") != 50:
        raise SystemExit("WINE_V51_INPUT_ABI_NOT_50")
    if data.get("pixelCopyImplemented") is not False:
        raise SystemExit("WINE_V51_INPUT_ALREADY_CLAIMS_PIXEL_COPY")
    if data.get("hostVisiblePresentImplemented") is not False:
        raise SystemExit("WINE_V51_INPUT_VISIBLE_PRESENT_NOT_FAIL_CLOSED")
    if data.get("robloxExecuted") is not False:
        raise SystemExit("WINE_V51_INPUT_ROBLOX_NOT_FAIL_CLOSED")


def require_v51(data: dict[str, object]) -> None:
    if data.get("schemaVersion") != 1:
        raise SystemExit("WINE_V51_COPY_EVIDENCE_SCHEMA_INVALID")
    if data.get("privateWineVulkanAbi") != 51:
        raise SystemExit("WINE_V51_PRIVATE_ABI_NOT_51")
    contract = data.get("copyContract")
    if not isinstance(contract, dict):
        raise SystemExit("WINE_V51_COPY_CONTRACT_MISSING")

    required_true = (
        "exactPresentedImageRequired",
        "swapchainTransferSrcRequired",
        "destinationTransferDstRequired",
        "exactFormatRequired",
        "exactExtentRequired",
        "originalPresentWaitsConsumedByCopy",
        "replacementBinarySemaphoreFeedsPresent",
        "sourceRestoredToPresentLayout",
        "destinationReleasedToExternalGeneral",
        "samePresentQueue",
        "oneShotDiagnostic",
    )
    if any(contract.get(key) is not True for key in required_true):
        raise SystemExit("WINE_V51_COPY_CONTRACT_INCOMPLETE")
    if contract.get("activationEnvironment") != "POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC=1":
        raise SystemExit("WINE_V51_COPY_ACTIVATION_INVALID")

    if data.get("pixelCopyImplemented") is not True:
        raise SystemExit("WINE_V51_PIXEL_COPY_SOURCE_NOT_IMPLEMENTED")
    if data.get("pixelCopyExecuted") is not False:
        raise SystemExit("WINE_V51_SOURCE_MUST_NOT_CLAIM_COPY_EXECUTION")
    if data.get("formatConversionImplemented") is not False:
        raise SystemExit("WINE_V51_FORMAT_CONVERSION_MUST_REMAIN_FALSE")
    if data.get("scalingImplemented") is not False:
        raise SystemExit("WINE_V51_SCALING_MUST_REMAIN_FALSE")
    if data.get("androidVisibleConsumerImplemented") is not False:
        raise SystemExit("WINE_V51_ANDROID_CONSUMER_MUST_REMAIN_FALSE")
    if data.get("hostVisiblePresentImplemented") is not False:
        raise SystemExit("WINE_V51_VISIBLE_PRESENT_MUST_REMAIN_FALSE")
    if data.get("robloxExecuted") is not False:
        raise SystemExit("WINE_V51_ROBLOX_MUST_REMAIN_FALSE")


def require_copy_ack(data: dict[str, object]) -> None:
    if data.get("schemaVersion") != 1:
        raise SystemExit("WINE_V51_COPY_ACK_EVIDENCE_SCHEMA_INVALID")
    if data.get("privateWineVulkanAbi") != 51:
        raise SystemExit("WINE_V51_COPY_ACK_ABI_INVALID")
    if data.get("pgaProtocolVersion") != 1:
        raise SystemExit("WINE_V51_COPY_ACK_PROTOCOL_INVALID")
    if data.get("presentCopyCompletedStage") != 6:
        raise SystemExit("WINE_V51_COPY_ACK_STAGE_INVALID")
    source = data.get("sourceIntegration")
    if not isinstance(source, dict):
        raise SystemExit("WINE_V51_COPY_ACK_SOURCE_MISSING")
    required_true = (
        "stage5MustSucceedBeforeStage6",
        "stage6UsesSameResourceIdentity",
        "stage6DetailCarriesQueueFamily",
    )
    if any(source.get(key) is not True for key in required_true):
        raise SystemExit("WINE_V51_COPY_ACK_ORDERING_INCOMPLETE")
    if data.get("copyCompletedAckImplemented") is not True:
        raise SystemExit("WINE_V51_COPY_ACK_NOT_IMPLEMENTED")
    if data.get("copyCompletedAckExecuted") is not False:
        raise SystemExit("WINE_V51_COPY_ACK_MUST_REMAIN_NOT_EXECUTED")
    if data.get("androidVisibleFrameImplemented") is not False:
        raise SystemExit("WINE_V51_COPY_ACK_VISIBLE_FRAME_MUST_REMAIN_FALSE")
    if data.get("robloxExecuted") is not False:
        raise SystemExit("WINE_V51_COPY_ACK_ROBLOX_MUST_REMAIN_FALSE")


def require_compile_fix(data: dict[str, object]) -> None:
    if data.get("schemaVersion") != 1:
        raise SystemExit("WINE_V51_COMPILE_FIX_EVIDENCE_SCHEMA_INVALID")
    if data.get("privateWineVulkanAbi") != 51:
        raise SystemExit("WINE_V51_COMPILE_FIX_ABI_INVALID")
    source = data.get("sourceIntegration")
    if not isinstance(source, dict):
        raise SystemExit("WINE_V51_COMPILE_FIX_SOURCE_MISSING")
    required_true = (
        "copyCompletedDeclarationMovedBeforeQueueCallback",
        "copyAckDeclarationMovedBeforeQueueCallback",
        "descriptorPixelFormatContractUsed",
    )
    if any(source.get(key) is not True for key in required_true):
        raise SystemExit("WINE_V51_COMPILE_FIX_INCOMPLETE")
    if source.get("descriptorFormatReferencesRewritten") != 2:
        raise SystemExit("WINE_V51_COMPILE_FIX_FORMAT_REWRITE_COUNT_INVALID")
    if data.get("compiled") is not False:
        raise SystemExit("WINE_V51_COMPILE_FIX_MUST_NOT_PRECLAIM_BUILD")
    if data.get("runtimeExecuted") is not False:
        raise SystemExit("WINE_V51_COMPILE_FIX_MUST_NOT_PRECLAIM_RUNTIME")
    if data.get("androidVisibleFrame") is not False:
        raise SystemExit("WINE_V51_COMPILE_FIX_MUST_NOT_PRECLAIM_VISIBLE_FRAME")
    if data.get("robloxExecuted") is not False:
        raise SystemExit("WINE_V51_COMPILE_FIX_MUST_NOT_PRECLAIM_ROBLOX")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    wine_source = args.wine_source.resolve()
    if not wine_source.is_dir():
        raise SystemExit(f"WINE_V51_SOURCE_MISSING:{wine_source}")

    with tempfile.TemporaryDirectory(prefix="pocketpc-wine-v51-") as temp:
        temp_root = Path(temp)
        v50_path = temp_root / "v50.json"
        v51_path = temp_root / "v51.json"
        copy_ack_path = temp_root / "copy-ack.json"
        compile_fix_path = temp_root / "compile-fix.json"

        run_stage(V50_PREPARER, wine_source, v50_path)
        v50 = json.loads(v50_path.read_text(encoding="utf-8"))
        require_v50(v50)

        run_stage(V51_PREPARER, wine_source, v51_path)
        v51 = json.loads(v51_path.read_text(encoding="utf-8"))
        require_v51(v51)

        run_stage(COPY_ACK_PREPARER, wine_source, copy_ack_path)
        copy_ack = json.loads(copy_ack_path.read_text(encoding="utf-8"))
        require_copy_ack(copy_ack)

        run_stage(COMPILE_FIX_PREPARER, wine_source, compile_fix_path)
        compile_fix = json.loads(compile_fix_path.read_text(encoding="utf-8"))
        require_compile_fix(compile_fix)

    combined = dict(v50)
    combined.update(
        {
            "status": "POCKETPC_WINE_DRIVER_V51_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
            "privateWineVulkanAbi": 51,
            "prePresentCopy": v51["copyContract"],
            "presentCopyCompletedAck": {
                "pgaProtocolVersion": copy_ack["pgaProtocolVersion"],
                "stage": copy_ack["presentCopyCompletedStage"],
                "stage5MustSucceedBeforeStage6": copy_ack["sourceIntegration"]["stage5MustSucceedBeforeStage6"],
                "sameResourceIdentity": copy_ack["sourceIntegration"]["stage6UsesSameResourceIdentity"],
                "queueFamilyInDetail": copy_ack["sourceIntegration"]["stage6DetailCarriesQueueFamily"],
                "implemented": True,
                "executed": False,
            },
            "compileSourceNormalization": {
                "copyCompletedDeclarationMovedBeforeQueueCallback": True,
                "copyAckDeclarationMovedBeforeQueueCallback": True,
                "descriptorPixelFormatContractUsed": True,
                "descriptorFormatReferencesRewritten": 2,
                "compiled": False,
                "executed": False,
            },
            "pixelCopyImplemented": True,
            "pixelCopyExecuted": False,
            "formatConversionImplemented": False,
            "scalingImplemented": False,
            "androidVisibleConsumerImplemented": False,
            "hostVisiblePresentImplemented": False,
            "robloxExecuted": False,
            "v51SourceEvidence": {
                "schemaVersion": v51.get("schemaVersion"),
                "status": v51.get("status"),
                "helperFiles": v51.get("helperFiles"),
                "files": v51.get("files"),
                "notExecuted": v51.get("notExecuted"),
                "copyAckStatus": copy_ack.get("status"),
                "copyAckFiles": copy_ack.get("files"),
                "copyAckNotExecuted": copy_ack.get("notExecuted"),
                "compileFixStatus": compile_fix.get("status"),
                "compileFixFiles": compile_fix.get("files"),
                "compileFixNotExecuted": compile_fix.get("notExecuted"),
            },
        }
    )

    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(
        json.dumps(combined, indent=2) + "\n",
        encoding="utf-8",
    )

    print("POCKETPC_WINE_DRIVER_V51_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=51")
    print("pixel_copy_source_integrated=true")
    print("pga_present_copy_completed_stage=6")
    print("compile_source_normalized=true")
    print("descriptor_pixel_format_contract=true")
    print("pixel_copy_executed=false")
    print("android_visible_consumer=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
