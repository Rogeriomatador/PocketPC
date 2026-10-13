#!/usr/bin/env python3
"""Derive an experimental continuous-Present v52 Wine overlay from prepared v51.

The input Wine tree must already contain the full PocketPC v51 source overlay,
including the one-shot exact-image copy and ordered PGA1 stage-6 acknowledgement.
This patch leaves that v51 path available as a fallback and activates continuous
ownership only when POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1.

v52 separates immutable PVI1 offer identity from continuous frame sequence:
  previous host-consumed = 2 * (N - 1)
  guest-ready            = 2 * N - 1
  host-consumed          = 2 * N

The guest waits for the exact previous even value, copies the exact Present image,
returns PVI1 to VK_QUEUE_FAMILY_EXTERNAL and signals the odd value from the same
queue submission. It never signals the even host-consumed value.

Source integration is NOT build, runtime, visible-frame, or Roblox evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
HELPER_FILES = (
    "pocketpc_guest_continuous_present.h",
    "pocketpc_guest_continuous_present.c",
)
UNIX_MAKEDEP_PREAMBLE = """#if 0
#pragma makedep unix
#endif

"""

ABI_51 = "#define WINE_VULKAN_DRIVER_VERSION 51"
ABI_52 = "#define WINE_VULKAN_DRIVER_VERSION 52"

COPY_INCLUDE = '#include "pocketpc_guest_present_copy.h"'
CONTINUOUS_INCLUDE = '#include "pocketpc_guest_continuous_present.h"'

RESOURCE_STRUCT_TAIL = """    BOOL image_imported;
    BOOL timeline_imported;
};"""
RESOURCE_STRUCT_V52_TAIL = """    BOOL image_imported;
    BOOL timeline_imported;

    /* PocketPC v52 source-only continuous Present state. */
    uint64_t continuous_present_frame_sequence;
    BOOL continuous_present_poisoned;
    struct pocketpc_guest_continuous_present_submission continuous_present_submission;
};"""

RESET_ANCHOR = "    pocketpc_guest_resource.present_queue_signal_ack_sent = FALSE;"
RESET_V52 = """    pocketpc_guest_resource.continuous_present_frame_sequence =
        POCKETPC_GUEST_CONTINUOUS_PRESENT_FIRST_FRAME;
    pocketpc_guest_resource.continuous_present_poisoned = FALSE;
    memset(&pocketpc_guest_resource.continuous_present_submission, 0,
           sizeof(pocketpc_guest_resource.continuous_present_submission));
    pocketpc_guest_resource.continuous_present_submission.command_pool = VK_NULL_HANDLE;
    pocketpc_guest_resource.continuous_present_submission.present_wait_semaphore = VK_NULL_HANDLE;"""

DIAGNOSTIC_FUNCTION_ANCHOR = "static BOOL pocketpc_present_copy_diagnostic_enabled(void)"
V52_GATE_FUNCTION = r'''static BOOL pocketpc_continuous_present_v52_enabled(void)
{
    const char *value = getenv("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52");
    return value && !strcmp(value, "1");
}

'''

OLD_PRE_PRESENT_GATE = r'''    if (replacement_wait_semaphore)
        *replacement_wait_semaphore = VK_NULL_HANDLE;
    if (!pocketpc_present_copy_diagnostic_enabled() ||
        !queue || !queue->device || image == VK_NULL_HANDLE ||
        !replacement_wait_semaphore || !extent.width || !extent.height)
        return VK_FALSE;
    if (pocketpc_pre_present_copy_completed || pocketpc_pre_present_copy_armed)
        return VK_FALSE;'''
NEW_PRE_PRESENT_GATE = r'''    if (replacement_wait_semaphore)
        *replacement_wait_semaphore = VK_NULL_HANDLE;
    if ((!pocketpc_continuous_present_v52_enabled() &&
         !pocketpc_present_copy_diagnostic_enabled()) ||
        !queue || !queue->device || image == VK_NULL_HANDLE ||
        !replacement_wait_semaphore || !extent.width || !extent.height)
        return VK_FALSE;
    if (pocketpc_pre_present_copy_armed)
        return VK_FALSE;
    if (pocketpc_continuous_present_v52_enabled())
    {
        if (pocketpc_guest_resource.continuous_present_poisoned)
            return VK_FALSE;
    }
    else if (pocketpc_pre_present_copy_completed)
    {
        return VK_FALSE;
    }'''

RESOURCE_READY_ANCHOR = r'''    if (pocketpc_guest_resource.device != device ||
        pocketpc_guest_resource.stopping ||
        !pocketpc_guest_resource.image_imported ||
        !pocketpc_guest_resource.timeline_imported)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return VK_FALSE;
    }'''
CONTINUOUS_SUBMIT_BRANCH = r'''
    if (pocketpc_continuous_present_v52_enabled())
    {
        uint64_t frame_sequence =
            pocketpc_guest_resource.continuous_present_frame_sequence;

        result = pocketpc_guest_continuous_present_submit(
            device,
            queue,
            image,
            format,
            extent,
            &pocketpc_guest_resource.image,
            (VkFormat)pocketpc_guest_resource.descriptor.pixel_format,
            pocketpc_guest_resource.descriptor.width,
            pocketpc_guest_resource.descriptor.height,
            (VkImageUsageFlags)pocketpc_guest_resource.descriptor.usage,
            &pocketpc_guest_resource.timeline,
            frame_sequence,
            wait_semaphore_count,
            wait_semaphores,
            &pocketpc_guest_resource.continuous_present_submission);
        if (result != POCKETPC_GUEST_CONTINUOUS_PRESENT_OK)
        {
            pocketpc_guest_resource.continuous_present_poisoned = TRUE;
            ERR("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=submit_rejected result=%d frame=%llu visible_present=0 roblox=0\n",
                result, (unsigned long long)frame_sequence);
            pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
            return VK_FALSE;
        }

        *replacement_wait_semaphore =
            pocketpc_guest_resource.continuous_present_submission.present_wait_semaphore;
        InterlockedExchange(&pocketpc_pre_present_copy_armed, 1);
        TRACE("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=frame_copy_submitted frame=%llu previous_host_consumed=%llu guest_ready=%llu exact_image=1 returned_to_external_queued=1 visible_present=0 roblox=0 queue_family=%u\n",
              (unsigned long long)frame_sequence,
              (unsigned long long)pocketpc_guest_resource.continuous_present_submission.previous_host_consumed_value,
              (unsigned long long)pocketpc_guest_resource.continuous_present_submission.guest_ready_value,
              queue->info.queueFamilyIndex);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return VK_TRUE;
    }
'''

OLD_CLEANUP_CHECK = r'''    if (pocketpc_guest_resource.device != device ||
        !pocketpc_pre_present_copy_submission.active)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    result = pocketpc_guest_present_copy_cleanup('''
NEW_CLEANUP_PREFIX = r'''    if (pocketpc_guest_resource.continuous_present_submission.active)
    {
        uint64_t completed_frame =
            pocketpc_guest_resource.continuous_present_submission.frame_sequence;
        uint64_t guest_ready =
            pocketpc_guest_resource.continuous_present_submission.guest_ready_value;

        if (pocketpc_guest_resource.device != device)
        {
            pocketpc_guest_resource.continuous_present_poisoned = TRUE;
            InterlockedExchange(&pocketpc_pre_present_copy_armed, 0);
            pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
            return;
        }

        result = pocketpc_guest_continuous_present_cleanup(
            device,
            queue,
            &pocketpc_guest_resource.continuous_present_submission);
        if (result != POCKETPC_GUEST_CONTINUOUS_PRESENT_OK)
        {
            pocketpc_guest_resource.continuous_present_poisoned = TRUE;
            InterlockedExchange(&pocketpc_pre_present_copy_armed, 0);
            ERR("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=cleanup_failed result=%d frame=%llu present_result=%d visible_present=0 roblox=0\n",
                result, (unsigned long long)completed_frame, present_result);
            pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
            return;
        }

        InterlockedExchange(&pocketpc_pre_present_copy_armed, 0);
        InterlockedExchange(&pocketpc_pre_present_copy_completed, 1);
        if (completed_frame >= POCKETPC_GUEST_CONTINUOUS_PRESENT_MAX_FRAME)
            pocketpc_guest_resource.continuous_present_poisoned = TRUE;
        else
            pocketpc_guest_resource.continuous_present_frame_sequence =
                completed_frame + 1u;

        TRACE("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=frame_queue_completed frame=%llu guest_ready=%llu returned_to_external=1 next_frame=%llu poisoned=%u visible_present=0 roblox=0 present_result=%d queue_family=%u\n",
              (unsigned long long)completed_frame,
              (unsigned long long)guest_ready,
              (unsigned long long)pocketpc_guest_resource.continuous_present_frame_sequence,
              pocketpc_guest_resource.continuous_present_poisoned,
              present_result,
              queue->info.queueFamilyIndex);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    if (pocketpc_guest_resource.device != device ||
        !pocketpc_pre_present_copy_submission.active)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    result = pocketpc_guest_present_copy_cleanup('''

V52_POST_PRESENT_BRANCH = r'''    if (pocketpc_continuous_present_v52_enabled())
    {
        queue_family = queue->info.queueFamilyIndex;
        result = pocketpc_send_guest_ack(
            pocketpc_guest_resource.session.fd,
            PGA_STAGE_GPU_SIGNAL_SUBMITTED,
            PGA_STATUS_OK,
            &pocketpc_guest_resource.descriptor,
            &pocketpc_guest_resource.ownership,
            queue_family);
        if (result != 0)
        {
            ERR("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=pga_stage5_failed result=%d visible_present=0 roblox=0\n",
                result);
            pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
            return;
        }
        pocketpc_guest_resource.present_queue_signal_ack_sent = TRUE;

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
                ERR("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=pga_stage6_failed result=%d visible_present=0 roblox=0\n",
                    result);
            }
            else
            {
                InterlockedExchange(&pocketpc_pre_present_copy_ack_sent, 1);
            }
        }

        TRACE("POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52 stage=lifecycle_ack_only guest_ready=%llu guest_signalled_host_consumed=0 visible_present=0 roblox=0 queue_family=%u\n",
              (unsigned long long)pocketpc_guest_resource.timeline.queue_signal_value,
              queue_family);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

'''
QUEUE_COUNTER_ANCHOR = r'''    result = pocketpc_guest_vulkan_timeline_get_counter(
        device,
        &pocketpc_guest_resource.timeline,
        &current_value);'''

# Build these strings without a trailing escape in the Python source itself.
MAKEFILE_COPY_LINE = "\tpocketpc_guest_present_copy.c " + "\\"
MAKEFILE_CONTINUOUS_LINE = "\tpocketpc_guest_continuous_present.c " + "\\"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def replace_once(path: Path, old: str, new: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        return False
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"V52_REPLACE_ANCHOR_INVALID:{path}:{count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def before_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"V52_BEFORE_ANCHOR_INVALID:{path}:{count}")
    path.write_text(text.replace(anchor, insertion + anchor, 1), encoding="utf-8")
    return True


def after_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    count = text.count(anchor)
    if count != 1:
        raise SystemExit(f"V52_AFTER_ANCHOR_INVALID:{path}:{count}")
    path.write_text(text.replace(anchor, anchor + "\n" + insertion, 1), encoding="utf-8")
    return True


def copy_helpers(destination: Path) -> list[dict[str, object]]:
    copied: list[dict[str, object]] = []
    for name in HELPER_FILES:
        source = BRIDGE / name
        if not source.is_file():
            raise SystemExit(f"V52_HELPER_SOURCE_MISSING:{name}")
        target = destination / name
        expected = source.read_text(encoding="utf-8")
        if name.endswith(".c"):
            expected = UNIX_MAKEDEP_PREAMBLE + expected
        if target.exists() and target.read_text(encoding="utf-8") != expected:
            raise SystemExit(f"V52_HELPER_DESTINATION_MISMATCH:{name}")
        if not target.exists():
            target.write_text(expected, encoding="utf-8")
        copied.append(
            {
                "path": f"dlls/winepocketpc.drv/{name}",
                "sha256": digest(target),
            }
        )
    return copied


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wine-source", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()

    source = args.wine_source.resolve()
    header = source / "include/wine/vulkan_driver.h"
    driver_dir = source / "dlls/winepocketpc.drv"
    driver = driver_dir / "vulkan.c"
    makefile = driver_dir / "Makefile.in"
    ack_header = driver_dir / "pocketpc_graphics_ack.h"
    for required in (header, driver, makefile, ack_header):
        if not required.is_file():
            raise SystemExit(f"V52_REQUIRED_FILE_MISSING:{required}")

    header_text = header.read_text(encoding="utf-8")
    driver_text = driver.read_text(encoding="utf-8")
    if ABI_51 not in header_text:
        raise SystemExit("V52_REQUIRES_PRIVATE_ABI_51")
    if "pocketpc_guest_present_copy_submit(" not in driver_text:
        raise SystemExit("V52_REQUIRES_V51_PRESENT_COPY")
    if "static LONG pocketpc_pre_present_copy_ack_sent;" not in driver_text:
        raise SystemExit("V52_REQUIRES_V51_STAGE6_ACK_PATCH")
    if "PGA_STAGE_PRESENT_COPY_COMPLETED 6u" not in ack_header.read_text(encoding="utf-8"):
        raise SystemExit("V52_REQUIRES_PGA1_STAGE6")

    helper_files = copy_helpers(driver_dir)

    version_changed = replace_once(header, ABI_51, ABI_52)
    include_changed = after_once(driver, COPY_INCLUDE, CONTINUOUS_INCLUDE)
    resource_state_changed = replace_once(
        driver, RESOURCE_STRUCT_TAIL, RESOURCE_STRUCT_V52_TAIL
    )
    reset_changed = after_once(driver, RESET_ANCHOR, RESET_V52)
    gate_function_changed = before_once(
        driver, DIAGNOSTIC_FUNCTION_ANCHOR, V52_GATE_FUNCTION
    )
    pre_present_gate_changed = replace_once(
        driver, OLD_PRE_PRESENT_GATE, NEW_PRE_PRESENT_GATE
    )
    continuous_submit_changed = after_once(
        driver, RESOURCE_READY_ANCHOR, CONTINUOUS_SUBMIT_BRANCH
    )
    continuous_cleanup_changed = replace_once(
        driver, OLD_CLEANUP_CHECK, NEW_CLEANUP_PREFIX
    )
    post_present_changed = before_once(
        driver, QUEUE_COUNTER_ANCHOR, V52_POST_PRESENT_BRANCH
    )
    makefile_changed = after_once(
        makefile,
        MAKEFILE_COPY_LINE,
        MAKEFILE_CONTINUOUS_LINE,
    )

    evidence = {
        "schemaVersion": 1,
        "status": "POCKETPC_V52_CONTINUOUS_PRESENT_GUEST_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 52,
        "derivedFromPrivateAbi": 51,
        "environmentGate": "POCKETPC_VULKAN_CONTINUOUS_PRESENT_V52=1",
        "officialBuildSelected": False,
        "sourceIntegration": {
            "abiPatchChanged": version_changed,
            "helperIncludeChanged": include_changed,
            "resourceStateChanged": resource_state_changed,
            "resourceResetChanged": reset_changed,
            "environmentGateChanged": gate_function_changed,
            "prePresentGateChanged": pre_present_gate_changed,
            "continuousSubmitDispatchChanged": continuous_submit_changed,
            "continuousCleanupDispatchChanged": continuous_cleanup_changed,
            "postPresentTimelineOwnershipChanged": post_present_changed,
            "makefileChanged": makefile_changed,
            "helperFilesCarried": True,
        },
        "timelineOwnership": {
            "frameSequenceSeparateFromOfferSequence": True,
            "firstFrameSequence": 1,
            "maxFrameSequence": 4611686018427387903,
            "guestWaitsPreviousHostConsumedEven": True,
            "guestSignalsReadyOddFromCopyQueueSubmit": True,
            "guestSignalsHostConsumedEven": False,
            "postPresentCallbackMutatesTimelineInV52": False,
        },
        "presentOrdering": {
            "usesExactSelectedSwapchainImage": True,
            "consumesOriginalPresentWaitsOnce": True,
            "replacementBinaryPresentWait": True,
            "copyAndGuestReadySignalSameQueueSubmit": True,
            "pvi1ReleasedToExternalBeforeGuestReadySignalCompletes": True,
            "nextFrameBlockedUntilPreviousHostConsumed": True,
        },
        "fallback": {
            "v51OneShotPathRetainedWhenV52GateDisabled": True,
        },
        "compiled": False,
        "runtimeExecuted": False,
        "hostVisiblePresentValidated": False,
        "robloxExecuted": False,
        "robloxRendered": False,
        "robloxPlayable": False,
        "helperFiles": helper_files,
        "files": {
            "vulkanDriverHeaderSha256": digest(header),
            "pocketPcVulkanSha256": digest(driver),
            "makefileSha256": digest(makefile),
        },
        "notExecuted": [
            "Wine compilation after v52 guest overlay",
            "v52 helper compilation",
            "v52 exact previous-even wait",
            "v52 repeated exact-image copy",
            "v52 odd guest-ready queue signal",
            "Android even host-consumed signal paired with Wine guest",
            "continuous multi-frame integration",
            "Android-visible frame",
            "Roblox Desktop",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_V52_CONTINUOUS_PRESENT_GUEST_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=52")
    print("official_build_selected=false")
    print("compiled=false")
    print("runtime_executed=false")
    print("host_visible_present_validated=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())