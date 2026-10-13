#!/usr/bin/env python3
"""Self-test the v52 integration evidence validator without executing Wine."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "scripts/validate-v52-continuous-present-integration.py"


def guest_log() -> str:
    lines = ["POCKETPC_V52_SMOKE_BEGIN frames=8 width=160 height=96 feature=0xb000"]
    for seq in range(1, 9):
        lines.append(
            "POCKETPC_V52_SMOKE_FRAME_BEFORE_PRESENT "
            f"seq={seq} guestReady={seq * 2 - 1} hostConsumed={seq * 2} color={seq - 1}"
        )
        lines.append(f"POCKETPC_V52_SMOKE_FRAME_PRESENT_RETURNED seq={seq}")
    lines.append("POCKETPC_V52_CONTINUOUS_PRESENT_SMOKE_OK frames=8")
    return "\n".join(lines) + "\n"


def run_case(work: Path, fingerprints: list[int], expect_success: bool) -> None:
    work.mkdir(parents=True, exist_ok=True)
    guest = work / "guest.log"
    host = work / "host.json"
    output = work / "evidence.json"
    guest.write_text(guest_log(), encoding="utf-8")
    host.write_text(
        json.dumps(
            {
                "graphicsV52FramesDelivered": 8,
                "graphicsV52FrameFingerprints": fingerprints,
            }
        ) + "\n",
        encoding="utf-8",
    )
    result = subprocess.run(
        [
            sys.executable,
            str(VALIDATOR),
            "--guest-log",
            str(guest),
            "--host-json",
            str(host),
            "--output",
            str(output),
        ],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if expect_success:
        if result.returncode != 0:
            raise SystemExit("VALID_V52_EVIDENCE_REJECTED:" + result.stdout)
        evidence = json.loads(output.read_text(encoding="utf-8"))
        assert evidence["integrationExecuted"] is True
        assert evidence["physicalVisibleFrame"] is False
        assert evidence["robloxExecuted"] is False
        assert len(set(evidence["hostFrameFingerprints"])) == 8
    else:
        if result.returncode == 0:
            raise SystemExit("FROZEN_V52_EVIDENCE_WAS_ACCEPTED")
        if "HOST_FRAME_CONTENT_NOT_DISTINCT" not in result.stdout:
            raise SystemExit("FROZEN_V52_REJECTION_REASON_MISSING:" + result.stdout)


def main() -> int:
    if not VALIDATOR.is_file():
        raise SystemExit("V52_VALIDATOR_MISSING")
    with tempfile.TemporaryDirectory(prefix="pocketpc-v52-validator-") as raw:
        work = Path(raw)
        run_case(work / "valid", [101, 202, 303, 404, 505, 606, 707, 808], True)
        run_case(work / "frozen", [999] * 8, False)
    print("PASS v52 integration validator self-test")
    print("VALID_DISTINCT_FRAMES_ACCEPTED=1")
    print("FROZEN_REPEATED_FRAMES_REJECTED=1")
    print("PHYSICAL=NOT_EXECUTED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
