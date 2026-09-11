#!/usr/bin/env python3
"""Static source-integration lock for experimental PocketPC Wine Vulkan ABI v52.

This does not prepare or compile Wine. It only verifies that the checked-in v52
source overlay preserves the v51 fallback, keeps the official build on v51, and
keeps all runtime/physical/Roblox evidence fail-closed.
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
OFFICIAL_WORKFLOW = ROOT / ".github/workflows/wine-x86_64-build.yml"


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
    workflow = OFFICIAL_WORKFLOW.read_text(encoding="utf-8")

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
        fail("active runtime wiring must remain false")

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
    require(preparer, "officialBuildSelected\": False", "v52 experimental evidence")
    require(preparer, "compiled\": False", "compile fail-closed")
    require(preparer, "runtimeExecuted\": False", "runtime fail-closed")
    require(preparer, "integrationExecuted\": False", "integration fail-closed")
    require(preparer, "physicalVisibleFrame\": False", "physical fail-closed")
    require(preparer, "robloxExecuted\": False", "Roblox fail-closed")

    require(workflow, "python3 scripts/build-wine-x86_64-v51.py", "official v51 builder")
    require(workflow, "Build pinned Wine 11 x86_64 package with PocketPC v51 driver", "official v51 label")
    forbid(workflow, "build-wine-x86_64-v52.py", "official workflow")
    forbid(workflow, "prepare-wine-pocketpc-driver-v52.py", "official workflow")
    forbid(workflow, "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1", "official workflow")

    evidence = contract.get("evidenceClassification") or {}
    for key in ("software", "integration", "physical", "roblox"):
        if evidence.get(key) != "NOT_EXECUTED":
            fail(f"{key} evidence must remain NOT_EXECUTED")

    print("PASS static Wine v52 source integration lock")
    print("CLASSIFICATION=IMPLEMENTED_SOURCE_NOT_EXECUTED")
    print("OFFICIAL_WINE_BUILD=v51")
    print("V52_COMPILED=0")
    print("V52_RUNTIME_EXECUTED=0")
    print("V52_INTEGRATION_EXECUTED=0")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
