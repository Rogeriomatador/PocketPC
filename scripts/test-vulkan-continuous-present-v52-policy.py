#!/usr/bin/env python3
"""Static policy guard for the Vulkan continuous-present v52 source contract.

This validates DESIGN/source policy only. It does not build Wine, execute Vulkan,
prove a visible Android frame, or run Roblox.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "third_party/wine/POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52.json"
COORDINATOR = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/VulkanContinuousPresentHostCoordinator.kt"
)
OWNERSHIP = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/VulkanContinuousPresentOwnershipState.kt"
)
TIMELINE = (
    ROOT
    / "app/src/main/java/dev/pocketpc/core/runtime/VulkanContinuousPresentTimeline.kt"
)
LONG_MAX = (1 << 63) - 1
MAX_FRAME_SEQUENCE = LONG_MAX // 2


def fail(message: str) -> None:
    raise SystemExit(f"FAIL v52 continuous-present policy: {message}")


def require(text: str, needle: str, label: str) -> None:
    if needle not in text:
        fail(f"{label} missing {needle!r}")


def forbid(text: str, needle: str, label: str) -> None:
    if needle in text:
        fail(f"{label} contains forbidden {needle!r}")


def guest_ready(frame_sequence: int) -> int:
    if frame_sequence < 1 or frame_sequence > MAX_FRAME_SEQUENCE:
        raise ValueError("frame sequence outside fail-closed signed JNI range")
    return 2 * frame_sequence - 1


def host_consumed(frame_sequence: int) -> int:
    if frame_sequence < 1 or frame_sequence > MAX_FRAME_SEQUENCE:
        raise ValueError("frame sequence outside fail-closed signed JNI range")
    return 2 * frame_sequence


def main() -> None:
    data = json.loads(CONTRACT.read_text(encoding="utf-8"))
    coordinator = COORDINATOR.read_text(encoding="utf-8")
    ownership = OWNERSHIP.read_text(encoding="utf-8")
    timeline_source = TIMELINE.read_text(encoding="utf-8")

    if data.get("schemaVersion") != 1:
        fail("schemaVersion must remain 1")
    if data.get("targetPrivateWineVulkanAbi") != 52:
        fail("target private Wine Vulkan ABI must be 52")
    if data.get("officialBuildSelected") is not False:
        fail("v52 must not be selected as official before executed evidence")
    if data.get("runtimeIntegrated") is not False:
        fail("partial host source must not claim active runtime integration")

    for key in (
        "softwareTestExecuted",
        "integrationTestExecuted",
        "physicalTestExecuted",
        "robloxExecuted",
    ):
        if data.get(key) is not False:
            fail(f"{key} must remain false before executed evidence exists")

    timeline = data.get("timelineOwnership") or {}
    if timeline.get("initialValue") != 0:
        fail("timeline must start at zero")
    if timeline.get("bridgeMaximumTimelineValue") != LONG_MAX:
        fail("bridge timeline ceiling must equal Long.MAX_VALUE")
    if timeline.get("maximumFrameSequence") != MAX_FRAME_SEQUENCE:
        fail("maximum frame sequence must stay inside signed JNI range")
    if timeline.get("guestReadyFormula") != "2 * frameSequence - 1":
        fail("guest-ready formula changed")
    if timeline.get("hostConsumedFormula") != "2 * frameSequence":
        fail("host-consumed formula changed")
    if timeline.get("guestReadyParity") != "odd":
        fail("guest-ready parity must be odd")
    if timeline.get("hostConsumedParity") != "even":
        fail("host-consumed parity must be even")
    if timeline.get("monotonic") is not True:
        fail("timeline must be monotonic")

    expected = {
        1: (1, 2),
        2: (3, 4),
        3: (5, 6),
        1024: (2047, 2048),
        MAX_FRAME_SEQUENCE: (LONG_MAX - 2, LONG_MAX - 1),
    }
    previous_consumed = 0
    for frame, pair in expected.items():
        ready = guest_ready(frame)
        consumed = host_consumed(frame)
        if (ready, consumed) != pair:
            fail(f"timeline formula mismatch for frame {frame}")
        if ready % 2 != 1 or consumed % 2 != 0:
            fail(f"ownership parity mismatch for frame {frame}")
        if consumed != ready + 1:
            fail(f"host-consumed value must immediately follow guest-ready for frame {frame}")
        if frame <= 3 and ready <= previous_consumed:
            fail(f"timeline not strictly increasing at frame {frame}")
        if frame <= 3:
            previous_consumed = consumed

    last = timeline.get("lastRepresentableFrame") or {}
    if last != {
        "frameSequence": MAX_FRAME_SEQUENCE,
        "guestReadyValue": LONG_MAX - 2,
        "hostConsumedValue": LONG_MAX - 1,
    }:
        fail("last representable frame contract mismatch")

    for invalid in (0, -1, MAX_FRAME_SEQUENCE + 1, LONG_MAX):
        for formula in (guest_ready, host_consumed):
            try:
                formula(invalid)
            except ValueError:
                pass
            else:
                fail(f"overflow/underflow was not rejected for {invalid}")

    identity = data.get("identity") or {}
    if identity.get("resourceIdentity") != [
        "resourceId",
        "generation",
        "offerSequence",
    ]:
        fail("resource identity contract changed")
    if identity.get("frameSequenceSource") != "Derived from the shared PVS1 timeline value.":
        fail("continuous frame identity must remain timeline-derived")

    source = data.get("sourceImplementation") or {}
    expected_true = {
        "timelineArithmetic",
        "hostOwnershipStateMachine",
        "hostCoordinator",
        "hostPendingConsumedSignalRecovery",
        "exactDesktopWindowDeliveryContract",
    }
    expected_false = {
        "activeRuntimeWiring",
        "nativeTimelineWait",
        "nativePerFrameReadback",
        "nativeHostConsumedSignal",
        "guestWineV52CopyLoop",
    }
    if set(source) != expected_true | expected_false:
        fail("sourceImplementation keys changed unexpectedly")
    if any(source.get(key) is not True for key in expected_true):
        fail("implemented source scaffolding was demoted")
    if any(source.get(key) is not False for key in expected_false):
        fail("non-integrated/native v52 source was promoted without evidence")

    # Full implementation gates remain false until the source is actually wired
    # to native Vulkan/Wine and the relevant test stages are executed.
    gates = data.get("requiredImplementationGates") or {}
    if not gates or any(value is not False for value in gates.values()):
        fail("full implementation gates were promoted prematurely")

    evidence = data.get("evidenceClassification") or {}
    if evidence != {
        "contract": "DESIGN",
        "sourceRuntime": "PARTIALLY_IMPLEMENTED_SOURCE_ONLY",
        "software": "NOT_EXECUTED",
        "integration": "NOT_EXECUTED",
        "physical": "NOT_EXECUTED",
        "roblox": "NOT_EXECUTED",
    }:
        fail("evidence classification does not match partial source-only state")

    # Lock the host-side ordering and retry semantics in source without claiming
    # that the port has a native implementation yet.
    require(coordinator, "interface VulkanContinuousPresentHostPort", "host port boundary")
    require(coordinator, "awaitGuestReady(", "guest-ready wait")
    require(coordinator, "ownership.beginHostConsume(", "ownership begin")
    require(coordinator, "port.readbackFrame(", "per-frame readback boundary")
    require(coordinator, "desktopFrameSender(modelFrame)", "exact desktop delivery")
    require(coordinator, "ownership.complete(", "ownership completion")
    require(coordinator, "pendingSignal = pending", "pending even signal retention")
    require(coordinator, "retryPendingSignal(pending)", "even signal retry")
    require(coordinator, "pendingSignal?.let", "pending signal blocks next frame")
    require(coordinator, "sequence = frameSequence", "continuous frame identity")
    require(coordinator, "hostVisibleFrameValidated", "physical fail-closed flag")
    require(coordinator, "get() = false", "fail-closed evidence getters")

    require(ownership, "VULKAN_CONTINUOUS_PRESENT_STALE_FRAME", "stale frame rejection")
    require(ownership, "VULKAN_CONTINUOUS_PRESENT_SKIPPED_FRAME", "skipped frame rejection")
    require(ownership, "replaceGeneration(", "generation replacement")
    require(timeline_source, "Long.MAX_VALUE / 2L", "signed timeline ceiling")

    for text, label in (
        (coordinator, "coordinator"),
        (ownership, "ownership"),
        (timeline_source, "timeline"),
    ):
        forbid(text, "hostVisiblePresentValidated = true", label)
        forbid(text, "robloxExecuted = true", label)
        forbid(text, "robloxRendered = true", label)
        forbid(text, "robloxPlayable = true", label)

    print("PASS static Vulkan v52 partial host-source policy")
    print("CLASSIFICATION=PARTIALLY_IMPLEMENTED_SOURCE_ONLY")
    print("RUNTIME_INTEGRATED=0")
    print("SOFTWARE=NOT_EXECUTED")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
