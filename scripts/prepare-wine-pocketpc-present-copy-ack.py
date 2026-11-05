#!/usr/bin/env python3
"""Add ordered PGA1 stage-6 acknowledgement to the prepared Wine v51 overlay.

Stage 6 is emitted from the ordinary post-Present queue callback only after:
  * the v51 pre-Present copy reported queue completion;
  * PGA1 stage 5 (GPU_SIGNAL_SUBMITTED) was successfully sent.

This ordering lets the Android host await stages 5 then 6 deterministically on
the same authenticated SOCK_SEQPACKET channel. Stage 6 is not visible-frame or
Roblox proof.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ABI_51 = "#define WINE_VULKAN_DRIVER_VERSION 51"
COPY_COMPLETED_GLOBAL = "static LONG pocketpc_pre_present_copy_completed;"
COPY_ACK_GLOBAL = "static LONG pocketpc_pre_present_copy_ack_sent;"

STAGE5_SUCCESS_ANCHOR = """    pocketpc_guest_resource.present_queue_signal_ack_sent = TRUE;
    TRACE("POCKETPC_VULKAN_GUEST stage=present_queue_signal_submitted execution_evidence=0 same_queue_as_present=1 visible_present=0 queue_family=%u value=%llu\\n",
          queue_family,
          (unsigned long long)pocketpc_guest_resource.timeline.queue_signal_value);"""

STAGE5_AND_STAGE6 = """    pocketpc_guest_resource.present_queue_signal_ack_sent = TRUE;

    if (pocketpc_pre_present_copy_completed && !pocketpc_pre_present_copy_ack_sent)
    {
        result = pocketpc_send_guest_ack(
            pocketpc_guest_resource.session.fd,
            PGA_STAGE_PRESENT_COPY_COMPLETED,
            PGA_STATUS_OK,
            &pocketpc_guest_resource.descriptor,
            &pocketpc_guest_resource.ownership,
            queue_family);
        if (result != 0)
        {
            ERR("POCKETPC_VULKAN_PRESENT_COPY stage=pga_copy_completed_ack_failed result=%d\\n", result);
        }
        else
        {
            InterlockedExchange(&pocketpc_pre_present_copy_ack_sent, 1);
            TRACE("POCKETPC_VULKAN_PRESENT_COPY stage=pga_copy_completed_ack_sent exact_image=1 pixels_copied=1 returned_to_external=1 visible_present=0 queue_family=%u\\n",
                  queue_family);
        }
    }

    TRACE("POCKETPC_VULKAN_GUEST stage=present_queue_signal_submitted execution_evidence=0 same_queue_as_present=1 visible_present=0 queue_family=%u value=%llu\\n",
          queue_family,
          (unsigned long long)pocketpc_guest_resource.timeline.queue_signal_value);"""


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def after_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise SystemExit(
            f"PRESENT_COPY_ACK_AFTER_ANCHOR_INVALID:{path}:{text.count(anchor)}"
        )
    path.write_text(text.replace(anchor, anchor + "\n" + insertion, 1), encoding="utf-8")
    return True


def replace_once(path: Path, old: str, new: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        return False
    if text.count(old) != 1:
        raise SystemExit(
            f"PRESENT_COPY_ACK_REPLACE_ANCHOR_INVALID:{path}:{text.count(old)}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    source = args.wine_source.resolve()
    header = source / "include/wine/vulkan_driver.h"
    driver = source / "dlls/winepocketpc.drv/vulkan.c"
    ack_header = source / "dlls/winepocketpc.drv/pocketpc_graphics_ack.h"
    ack_source = source / "dlls/winepocketpc.drv/pocketpc_graphics_ack.c"
    for required in (header, driver, ack_header, ack_source):
        if not required.is_file():
            raise SystemExit(f"PRESENT_COPY_ACK_REQUIRED_FILE_MISSING:{required}")

    if ABI_51 not in header.read_text(encoding="utf-8"):
        raise SystemExit("PRESENT_COPY_ACK_REQUIRES_PRIVATE_ABI_51")
    if "PGA_STAGE_PRESENT_COPY_COMPLETED 6u" not in ack_header.read_text(encoding="utf-8"):
        raise SystemExit("PRESENT_COPY_ACK_STAGE6_HEADER_MISSING")
    if "stage <= PGA_STAGE_PRESENT_COPY_COMPLETED" not in ack_source.read_text(encoding="utf-8"):
        raise SystemExit("PRESENT_COPY_ACK_STAGE6_SOURCE_MISSING")

    global_changed = after_once(driver, COPY_COMPLETED_GLOBAL, COPY_ACK_GLOBAL)
    dispatch_changed = replace_once(driver, STAGE5_SUCCESS_ANCHOR, STAGE5_AND_STAGE6)

    evidence = {
        "schemaVersion": 1,
        "status": "POCKETPC_PGA1_STAGE6_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 51,
        "pgaProtocolVersion": 1,
        "presentCopyCompletedStage": 6,
        "sourceIntegration": {
            "copyAckStateAdded": global_changed,
            "stage6DispatchAdded": dispatch_changed,
            "stage5MustSucceedBeforeStage6": True,
            "stage6UsesSameResourceIdentity": True,
            "stage6DetailCarriesQueueFamily": True,
        },
        "copyCompletedAckImplemented": True,
        "copyCompletedAckExecuted": False,
        "androidVisibleFrameImplemented": False,
        "robloxExecuted": False,
        "files": {
            "driverSha256": digest(driver),
            "ackHeaderSha256": digest(ack_header),
            "ackSourceSha256": digest(ack_source),
        },
        "notExecuted": [
            "Wine compilation after PGA1 stage 6 patch",
            "PGA1 stage 5 send",
            "PGA1 stage 6 send",
            "Android stage 6 receive",
            "Android-visible frame",
            "Roblox Desktop",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_PGA1_STAGE6_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("pga_stage5_before_stage6=true")
    print("copy_completed_ack_executed=false")
    print("android_visible_frame=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
