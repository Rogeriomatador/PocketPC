#!/usr/bin/env python3
"""Static source-integration lock for experimental PocketPC Wine Vulkan ABI v52.

This does not prepare or compile Wine. It verifies that the checked-in v52
source overlay preserves the v51 fallback, keeps the official build on v51,
and keeps the v52 build path explicitly manual/experimental and fail-closed.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "third_party/wine/POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52.json"
GUEST_HEADER = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_continuous_present.h"
GUEST_SOURCE = ROOT / "third_party/wine/pocketpc-display-bridge/pocketpc_guest_continuous_present.c"
OVERLAY = ROOT / "scripts/prepare-wine-pocketpc-continuous-present-v52.py"
PREPARER = ROOT / "scripts/prepare-wine-pocketpc-driver-v52.py"
EXPERIMENTAL_BUILDER = ROOT / "scripts/build-wine-x86_64-v52-experimental.py"
OFFICIAL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-build.yml"
EXPERIMENTAL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-v52-experimental.yml"


def fail(message: str) -> None:
    raise SystemExit(f"FAIL Wine v52 source integration: {message}")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label} missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label} contains forbidden {needle!r}")


def main() -> None:
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    header = GUEST_HEADER.read_text(encoding="utf-8")
    guest = GUEST_SOURCE.read_text(encoding="utf-8")
    overlay = OVERLAY.read_text(encoding="utf-8")
    preparer = PREPARER.read_text(encoding="utf-8")
    experimental_builder = EXPERIMENTAL_BUILDER.read_text(encoding="utf-8")
    workflow = OFFICIAL_WORKFLOW.read_text(encoding="utf-8")
    experimental_workflow = EXPERIMENTAL_WORKFLOW.read_text(encoding="utf-8")

    if contract.get("targetPrivateWineVulkanAbi") != 52:
        fail("contract ABI must remain 52")
    if contract.get("officialBuildSelected") is not False:
        fail("v52 cannot become official from source evidence")
    if contract.get("runtimeIntegrated") is not False:
        fail("v52 runtime integration is not executed/proven")

    source = contract.get("sourceImplementation") or {}
    for key in (
        "guestWineV52CopyLoop",
        "guestExactPreviousEvenWait",
        "guestOddReadySignalSameCopySubmit",
        "guestPostPresentEvenSignalSuppressed",
        "guestV52Preparer",
        "v51FallbackRetained",
    ):
        if source.get(key) is not True:
            fail(f"guest source flag not implemented: {key}")
    if source.get("activeRuntimeWiring") is not False:
        fail("active runtime activation must remain false")

    if any((contract.get("requiredImplementationGates") or {}).values()):
        fail("full implementation gate was promoted without executed evidence")

    require(header, "POCKETPC_GUEST_CONTINUOUS_PRESENT_MAX_FRAME 4611686018427387903ull", "guest header")
    require(header, "The even hostConsumed(N) value is never signalled here", "guest header")
    require(guest, "return frame_sequence * 2u - 1u;", "guest odd formula")
    require(guest, "return (frame_sequence - 1u) * 2u;", "guest previous-even formula")
    require(guest, "if (observed != expected)", "guest exact previous-even check")
    require(guest, "after[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;", "PVI1 external release")
    require(guest, "signal_values[1] = guest_ready;", "odd timeline signal")
    require(guest, "device->p_vkQueueSubmit(", "same copy/signal submit")
    forbid(guest, "signal_values[1] = frame_sequence * 2u", "guest source")
    forbid(guest, "signal_values[1] = previous_host_consumed", "guest source")

    require(overlay, "ABI_51 = \"#define WINE_VULKAN_DRIVER_VERSION 51\"", "v52 overlay base")
    require(overlay, "ABI_52 = \"#define WINE_VULKAN_DRIVER_VERSION 52\"", "v52 overlay target")
    require(overlay, "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1", "v52 environment gate")
    require(overlay, "pocketpc_guest_continuous_present_submit(", "continuous dispatch")
    require(overlay, "guest_signalled_host_consumed=0", "guest even-signal suppression")
    require(overlay, "post_present_changed = before_once(", "post-Present interception")
    require(overlay, "v51OneShotPathRetainedWhenV52GateDisabled\": True", "v51 fallback")
    require(overlay, "MAKEFILE_CONTINUOUS_LINE = \"\\tpocketpc_guest_continuous_present.c \" + \"\\\\\"", "deterministic Makefile injection")

    require(preparer, "V51_PREPARER", "complete v51 baseline")
    require(preparer, "V52_OVERLAY", "v52 overlay stage")
    require(preparer, 'data.get("protocolVersion") != 4', "v51 compatibility protocol guard")
    require(preparer, 'data.get("driverName") != "winepocketpc.drv"', "v51 compatibility driver guard")
    require(preparer, 'data.get("surfaceCallbackImplemented") is not True', "v51 compatibility surface guard")
    require(preparer, 'data.get("inputInjectionImplemented") is not True', "v51 compatibility input guard")
    require(preparer, '"protocolVersion": v51["protocolVersion"]', "v52 builder protocol metadata")
    require(preparer, '"driverName": v51["driverName"]', "v52 builder driver metadata")
    require(preparer, '"surfaceCallbackImplemented": v51["surfaceCallbackImplemented"]', "v52 builder surface metadata")
    require(preparer, '"inputInjectionImplemented": v51["inputInjectionImplemented"]', "v52 builder input metadata")
    require(preparer, "officialBuildSelected\": False", "v52 experimental evidence")
    require(preparer, "compiled\": False", "overlay compile fail-closed")
    require(preparer, "runtimeExecuted\": False", "runtime fail-closed")
    require(preparer, "integrationExecuted\": False", "integration fail-closed")
    require(preparer, "physicalVisibleFrame\": False", "physical fail-closed")
    require(preparer, "robloxExecuted\": False", "Roblox fail-closed")

    require(experimental_builder, "BASE_BUILD", "experimental builder base")
    require(experimental_builder, "V52_PREPARER", "experimental builder preparer")
    require(experimental_builder, "module.DRIVER_PREPARER = V52_PREPARER", "experimental builder isolation")
    require(experimental_builder, "CAPABILITY_RELATIVE", "verified v52 capability sidecar path")
    require(experimental_builder, "attach_verified_v52_capability(work)", "capability sidecar package injection")
    require(experimental_builder, '"wineVulkanAbi": 52', "capability sidecar ABI")
    require(experimental_builder, 'CAPABILITY_ID = "pocketpc.vulkan.continuous-present.v52"', "capability sidecar id")
    require(experimental_builder, '"officialBuildSelected": False', "capability sidecar official fail-closed")
    require(experimental_builder, '"physicalVisibleFrame": False', "capability sidecar physical fail-closed")
    require(experimental_builder, '"robloxExecuted": False', "capability sidecar Roblox fail-closed")
    require(experimental_builder, '"verifiedRuntimeGraphicsCapability"', "post-build capability evidence")
    require(experimental_builder, "wine-v52-experimental-build-evidence.json", "post-build evidence filename")
    require(experimental_builder, '"buildExecuted": True', "post-build executed evidence")
    require(experimental_builder, '"packageCompiled": True', "post-build package evidence")
    require(experimental_builder, '"runtimeExecuted": False', "post-build runtime fail-closed")
    require(experimental_builder, '"integrationExecuted": False', "post-build integration fail-closed")
    require(experimental_builder, '"physicalVisibleFrame": False', "post-build physical fail-closed")
    require(experimental_builder, '"robloxExecuted": False', "post-build Roblox fail-closed")
    require(experimental_builder, '"build": "SOFTWARE_BUILD_EXECUTED"', "post-build classification")
    require(experimental_builder, '"runtime": "NOT_EXECUTED"', "post-build runtime classification")
    require(experimental_builder, "emit_post_build_evidence(work)", "post-build evidence after base success")

    require(workflow, "python3 scripts/build-wine-x86_64-v51.py", "official v51 builder")
    require(workflow, "Build pinned Wine 11 x86_64 package with PocketPC v51 driver", "official v51 label")
    forbid(workflow, "build-wine-x86_64-v52.py", "official workflow")
    forbid(workflow, "build-wine-x86_64-v52-experimental.py", "official workflow")
    forbid(workflow, "prepare-wine-pocketpc-driver-v52.py", "official workflow")
    forbid(workflow, "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1", "official workflow")

    require(experimental_workflow, "workflow_dispatch:", "experimental workflow manual trigger")
    forbid(experimental_workflow, "pull_request:", "experimental workflow")
    forbid(experimental_workflow, "push:", "experimental workflow")
    require(experimental_workflow, "build-wine-x86_64-v52-experimental.py", "experimental workflow builder")
    require(experimental_workflow, "wine-v52-experimental-build-evidence.json", "experimental build evidence artifact")
    require(experimental_workflow, 'build["buildExecuted"] is True', "executed build guard")
    require(experimental_workflow, 'build["packageCompiled"] is True', "compiled package guard")
    require(experimental_workflow, 'build["officialBuildSelected"] is False', "official selection guard")
    require(experimental_workflow, 'build["runtimeExecuted"] is False', "runtime evidence guard")
    require(experimental_workflow, 'build["integrationExecuted"] is False', "integration evidence guard")
    require(experimental_workflow, 'build["physicalVisibleFrame"] is False', "physical evidence guard")
    require(experimental_workflow, 'build["robloxExecuted"] is False', "Roblox evidence guard")
    require(experimental_workflow, "PocketPC-Wine11-x86_64-v52-experimental-review", "experimental artifact label")

    evidence = contract.get("evidenceClassification") or {}
    for key in ("software", "integration", "physical", "roblox"):
        if evidence.get(key) != "NOT_EXECUTED":
            fail(f"{key} evidence must remain NOT_EXECUTED")

    print("PASS static Wine v52 source integration lock")
    print("CLASSIFICATION=IMPLEMENTED_SOURCE_NOT_EXECUTED")
    print("OFFICIAL_WINE_BUILD=v51")
    print("EXPERIMENTAL_V52_TRIGGER=workflow_dispatch")
    print("V52_BUILD_EVIDENCE=POST_SUCCESS_ONLY")
    print("V52_RUNTIME_EXECUTED=0")
    print("V52_INTEGRATION_EXECUTED=0")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
