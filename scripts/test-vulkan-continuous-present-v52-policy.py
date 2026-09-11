#!/usr/bin/env python3
"""Static policy guard for the proposed Vulkan continuous-present v52 contract.

This validates DESIGN/source policy only. It does not build Wine, execute Vulkan,
prove a visible Android frame, or run Roblox.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "third_party/wine/POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52.json"
LONG_MAX = (1 << 63) - 1
MAX_FRAME_SEQUENCE = LONG_MAX // 2


def fail(message: str) -> None:
    raise SystemExit(f"FAIL v52 continuous-present policy: {message}")


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

    if data.get("schemaVersion") != 1:
        fail("schemaVersion must remain 1")
    if data.get("targetPrivateWineVulkanAbi") != 52:
        fail("target private Wine Vulkan ABI must be 52")
    if data.get("officialBuildSelected") is not False:
        fail("v52 must not be selected as official before executed evidence")
    if data.get("runtimeIntegrated") is not False:
        fail("design contract must not claim runtime integration")

    for key in (
        "softwareTestExecuted",
        "integrationTestExecuted",
        "physicalTestExecuted",
        "robloxExecuted",
    ):
        if data.get(key) is not False:
            fail(f"{key} must remain false at design-only stage")

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

    gates = data.get("requiredImplementationGates") or {}
    if not gates or any(value is not False for value in gates.values()):
        fail("design-only implementation gates must remain false")

    evidence = data.get("evidenceClassification") or {}
    if evidence != {
        "contract": "DESIGN",
        "sourceRuntime": "NOT_IMPLEMENTED",
        "software": "NOT_EXECUTED",
        "integration": "NOT_EXECUTED",
        "physical": "NOT_EXECUTED",
        "roblox": "NOT_EXECUTED",
    }:
        fail("evidence classification was promoted without implementation/evidence")

    print("PASS static Vulkan v52 continuous-present ownership policy")
    print("CLASSIFICATION=DESIGN_ONLY")
    print("RUNTIME=NOT_IMPLEMENTED")
    print("PHYSICAL=NOT_EXECUTED")
    print("ROBLOX=NOT_EXECUTED")


if __name__ == "__main__":
    main()
