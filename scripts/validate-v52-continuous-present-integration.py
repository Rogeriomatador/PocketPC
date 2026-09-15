#!/usr/bin/env python3
"""Validate one PocketPC v52 continuous-present integration capture.

This validator is deliberately stricter than checking whether IDXGISwapChain::Present
returned success. It requires the deterministic 8-frame guest smoke sequence and
8 host-delivered frame fingerprints, all distinct. Passing this validator proves
software integration evidence only; it never proves Android physical visibility
or Roblox execution.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

FRAME_COUNT = 8
EXPECTED_SEQUENCES = list(range(1, FRAME_COUNT + 1))
EXPECTED_GUEST_READY = [2 * n - 1 for n in EXPECTED_SEQUENCES]
EXPECTED_HOST_CONSUMED = [2 * n for n in EXPECTED_SEQUENCES]

BEFORE_RE = re.compile(
    r"^POCKETPC_V52_SMOKE_FRAME_BEFORE_PRESENT "
    r"seq=(\d+) guestReady=(\d+) hostConsumed=(\d+) color=(\d+)$"
)
RETURNED_RE = re.compile(
    r"^POCKETPC_V52_SMOKE_FRAME_PRESENT_RETURNED seq=(\d+)$"
)
OK_RE = re.compile(
    r"^POCKETPC_V52_CONTINUOUS_PRESENT_SMOKE_OK frames=(\d+)$"
)


def fail(message: str) -> None:
    raise SystemExit(f"V52_INTEGRATION_VALIDATION_FAILED:{message}")


def load_guest_log(path: Path) -> tuple[list[dict[str, int]], list[int], bool]:
    before: list[dict[str, int]] = []
    returned: list[int] = []
    ok = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        match = BEFORE_RE.match(line)
        if match:
            before.append(
                {
                    "seq": int(match.group(1)),
                    "guestReady": int(match.group(2)),
                    "hostConsumed": int(match.group(3)),
                    "color": int(match.group(4)),
                }
            )
            continue
        match = RETURNED_RE.match(line)
        if match:
            returned.append(int(match.group(1)))
            continue
        match = OK_RE.match(line)
        if match:
            ok = int(match.group(1)) == FRAME_COUNT
    return before, returned, ok


def validate_guest(before: list[dict[str, int]], returned: list[int], ok: bool) -> None:
    if len(before) != FRAME_COUNT:
        fail(f"GUEST_BEFORE_COUNT:{len(before)}")
    if len(returned) != FRAME_COUNT:
        fail(f"GUEST_RETURNED_COUNT:{len(returned)}")
    if not ok:
        fail("GUEST_OK_MARKER_MISSING")

    if [item["seq"] for item in before] != EXPECTED_SEQUENCES:
        fail("GUEST_SEQUENCE_MISMATCH")
    if returned != EXPECTED_SEQUENCES:
        fail("GUEST_RETURNED_SEQUENCE_MISMATCH")
    if [item["guestReady"] for item in before] != EXPECTED_GUEST_READY:
        fail("GUEST_READY_SEQUENCE_MISMATCH")
    if [item["hostConsumed"] for item in before] != EXPECTED_HOST_CONSUMED:
        fail("HOST_CONSUMED_EXPECTATION_MISMATCH")
    if [item["color"] for item in before] != list(range(FRAME_COUNT)):
        fail("GUEST_COLOR_SEQUENCE_MISMATCH")


def validate_host(host: dict[str, object]) -> list[int]:
    delivered = host.get("graphicsV52FramesDelivered")
    fingerprints = host.get("graphicsV52FrameFingerprints")
    if delivered != FRAME_COUNT:
        fail(f"HOST_DELIVERED_COUNT:{delivered!r}")
    if not isinstance(fingerprints, list) or len(fingerprints) != FRAME_COUNT:
        fail("HOST_FINGERPRINT_COUNT")
    if any(isinstance(value, bool) or not isinstance(value, int) for value in fingerprints):
        fail("HOST_FINGERPRINT_TYPE")
    typed = [int(value) for value in fingerprints]
    if len(set(typed)) != FRAME_COUNT:
        fail("HOST_FRAME_CONTENT_NOT_DISTINCT")
    return typed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--guest-log", type=Path, required=True)
    parser.add_argument("--host-json", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if not args.guest_log.is_file():
        fail("GUEST_LOG_MISSING")
    if not args.host_json.is_file():
        fail("HOST_JSON_MISSING")

    before, returned, ok = load_guest_log(args.guest_log)
    validate_guest(before, returned, ok)

    host = json.loads(args.host_json.read_text(encoding="utf-8"))
    fingerprints = validate_host(host)

    evidence = {
        "schemaVersion": 1,
        "status": "V52_CONTINUOUS_PRESENT_SOFTWARE_INTEGRATION_VALIDATED_NOT_PHYSICAL",
        "frameCount": FRAME_COUNT,
        "frameSequences": EXPECTED_SEQUENCES,
        "guestReadyValues": EXPECTED_GUEST_READY,
        "hostConsumedValues": EXPECTED_HOST_CONSUMED,
        "hostFrameFingerprints": fingerprints,
        "guestPresentReturned": True,
        "hostDistinctFramesDelivered": True,
        "integrationExecuted": True,
        "physicalVisibleFrame": False,
        "robloxExecuted": False,
        "robloxRendered": False,
        "robloxPlayable": False,
        "classification": {
            "softwareIntegration": "INTEGRATION_TEST",
            "physical": "NOT_EXECUTED",
            "roblox": "NOT_EXECUTED",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print("PASS v52 continuous-present software integration evidence")
    print("FRAMES=8")
    print("DISTINCT_HOST_FINGERPRINTS=8")
    print("PHYSICAL_VISIBLE_FRAME=0")
    print("ROBLOX_EXECUTED=0")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
