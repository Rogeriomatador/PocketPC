#!/usr/bin/env python3
"""Extend the PocketPC Wine v50 overlay with a one-shot pre-Present GPU copy.

Input must already be prepared by prepare-wine-pocketpc-present-image.py. This
bumps the private PocketPC Wine Vulkan ABI to v51 and inserts a correctness-first
copy submit between the application's original Present waits and the real host
vkQueuePresentKHR. The copy is disabled unless
POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC=1.

Source integration is not runtime evidence. This stage does not implement an
Android-visible consumer and does not claim Roblox execution.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
HELPER_FILES = (
    "pocketpc_guest_present_copy.h",
    "pocketpc_guest_present_copy.c",
)
UNIX_MAKEDEP_PREAMBLE = """#if 0
#pragma makedep unix
#endif

"""

V50 = "#define WINE_VULKAN_DRIVER_VERSION 50"
V51 = "#define WINE_VULKAN_DRIVER_VERSION 51"

IMAGE_CALLBACK = (
    "    void (*p_vulkan_image_presented)( struct vulkan_queue *queue, VkImage image, "
    "VkFormat format, VkExtent2D extent, uint32_t image_index, VkResult result );"
)
PRE_PRESENT_CALLBACKS = """    /* PocketPC v51: one-shot exact-image copy before host Present. */
    VkBool32 (*p_vulkan_image_pre_present)( struct vulkan_queue *queue, VkImage image,
                                            VkFormat format, VkExtent2D extent, uint32_t image_index,
                                            uint32_t wait_semaphore_count, const VkSemaphore *wait_semaphores,
                                            VkSemaphore *replacement_wait_semaphore );
    void (*p_vulkan_pre_present_cleanup)( struct vulkan_queue *queue, VkResult result );"""

SWAPCHAIN_EXTENT_FIELD = "    VkExtent2D pocketpc_host_extents;"
SWAPCHAIN_TRANSFER_FIELD = "    VkBool32 pocketpc_transfer_src_enabled;"
SWAPCHAIN_USAGE_ANCHOR = (
    "    create_info_host.imageExtent.height = max( create_info_host.imageExtent.height, capabilities.minImageExtent.height );"
)
SWAPCHAIN_USAGE_PATCH = """    /* PocketPC v51: request transfer-source usage only when the host surface advertises it. */
    if (capabilities.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
        create_info_host.imageUsage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;"""
SWAPCHAIN_METADATA_ANCHOR = "    swapchain->pocketpc_host_extents = create_info_host.imageExtent;"
SWAPCHAIN_TRANSFER_RECORD = (
    "    swapchain->pocketpc_transfer_src_enabled = "
    "!!(create_info_host.imageUsage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT);"
)

PRESENT_LOCAL_ANCHOR = """    const VkSwapchainKHR *client_swapchains;
    VkResult res;"""
PRESENT_LOCALS = """    const VkSwapchainKHR *client_swapchains;
    VkSemaphore pocketpc_present_wait = VK_NULL_HANDLE;
    BOOL pocketpc_pre_present_copy_armed = FALSE;
    VkResult res;"""
PRESENT_CALL = "    res = device->p_vkQueuePresentKHR( queue->host.queue, present_info );"
PRE_PRESENT_DISPATCH = r'''    if (driver_funcs->p_vulkan_image_pre_present &&
        present_info->swapchainCount == 1 && present_info->pImageIndices)
    {
        struct swapchain *pocketpc_swapchain = swapchain_from_handle( client_swapchains[0] );
        uint32_t pocketpc_image_index = present_info->pImageIndices[0];

        if (pocketpc_swapchain->pocketpc_transfer_src_enabled &&
            pocketpc_swapchain->pocketpc_host_images &&
            pocketpc_image_index < pocketpc_swapchain->pocketpc_host_image_count &&
            driver_funcs->p_vulkan_image_pre_present(
                queue,
                pocketpc_swapchain->pocketpc_host_images[pocketpc_image_index],
                pocketpc_swapchain->pocketpc_host_image_format,
                pocketpc_swapchain->pocketpc_host_extents,
                pocketpc_image_index,
                present_info->waitSemaphoreCount,
                present_info->pWaitSemaphores,
                &pocketpc_present_wait ))
        {
            /* The copy submit consumed the original waits. Present waits once on its replacement. */
            present_info->waitSemaphoreCount = 1;
            present_info->pWaitSemaphores = &pocketpc_present_wait;
            pocketpc_pre_present_copy_armed = TRUE;
        }
    }
'''
POST_PRESENT_CLEANUP = """    if (pocketpc_pre_present_copy_armed && driver_funcs->p_vulkan_pre_present_cleanup)
        driver_funcs->p_vulkan_pre_present_cleanup( queue, res );"""

DRIVER_OWNERSHIP_INCLUDE = '#include "pocketpc_guest_external_image_ownership.h"'
DRIVER_COPY_INCLUDE = '#include "pocketpc_guest_present_copy.h"'
DRIVER_ROUNDTRIP_GLOBAL = "static LONG pocketpc_external_ownership_roundtrip_done;"
DRIVER_COPY_GLOBALS = """static LONG pocketpc_pre_present_copy_armed;
static LONG pocketpc_pre_present_copy_completed;
static struct pocketpc_guest_present_copy_submission pocketpc_pre_present_copy_submission;"""
DRIVER_ROUNDTRIP_IF = (
    "        if (InterlockedCompareExchange(&pocketpc_external_ownership_roundtrip_done, 1, 0) == 0)"
)
DRIVER_ROUNDTRIP_IF_V51 = (
    "        if (!pocketpc_pre_present_copy_completed && "
    "InterlockedCompareExchange(&pocketpc_external_ownership_roundtrip_done, 1, 0) == 0)"
)
DRIVER_FUNCTION_ANCHOR = "static void pocketpc_headless_client_surface_destroy("
DRIVER_COPY_FUNCTIONS = r'''static BOOL pocketpc_present_copy_diagnostic_enabled(void)
{
    const char *value = getenv("POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC");
    return value && !strcmp(value, "1");
}

static VkBool32 pocketpc_vulkan_image_pre_present(
    struct vulkan_queue *queue,
    VkImage image,
    VkFormat format,
    VkExtent2D extent,
    uint32_t image_index,
    uint32_t wait_semaphore_count,
    const VkSemaphore *wait_semaphores,
    VkSemaphore *replacement_wait_semaphore)
{
    struct vulkan_device *device;
    int result;

    if (replacement_wait_semaphore)
        *replacement_wait_semaphore = VK_NULL_HANDLE;
    if (!pocketpc_present_copy_diagnostic_enabled() ||
        !queue || !queue->device || image == VK_NULL_HANDLE ||
        !replacement_wait_semaphore || !extent.width || !extent.height)
        return VK_FALSE;
    if (pocketpc_pre_present_copy_completed || pocketpc_pre_present_copy_armed)
        return VK_FALSE;

    device = queue->device;
    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device != device ||
        pocketpc_guest_resource.stopping ||
        !pocketpc_guest_resource.image_imported ||
        !pocketpc_guest_resource.timeline_imported)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return VK_FALSE;
    }

    result = pocketpc_guest_present_copy_submit(
        device,
        queue,
        image,
        format,
        extent,
        &pocketpc_guest_resource.image,
        (VkFormat)pocketpc_guest_resource.descriptor.format,
        pocketpc_guest_resource.descriptor.width,
        pocketpc_guest_resource.descriptor.height,
        (VkImageUsageFlags)pocketpc_guest_resource.descriptor.usage,
        wait_semaphore_count,
        wait_semaphores,
        &pocketpc_pre_present_copy_submission);
    if (result != POCKETPC_GUEST_PRESENT_COPY_OK)
    {
        WARN("POCKETPC_VULKAN_PRESENT_COPY stage=pre_present_copy_rejected result=%d format=%u dst_format=%u width=%u height=%u dst_width=%u dst_height=%u image_index=%u execution_evidence=0 visible_present=0\n",
             result,
             (unsigned int)format,
             pocketpc_guest_resource.descriptor.format,
             extent.width,
             extent.height,
             pocketpc_guest_resource.descriptor.width,
             pocketpc_guest_resource.descriptor.height,
             image_index);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return VK_FALSE;
    }

    *replacement_wait_semaphore = pocketpc_pre_present_copy_submission.present_wait_semaphore;
    InterlockedExchange(&pocketpc_pre_present_copy_armed, 1);
    TRACE("POCKETPC_VULKAN_PRESENT_COPY stage=copy_submitted exact_image=1 original_waits_consumed=1 replacement_present_wait=1 pixels_copy_queued=1 visible_present=0 image_index=%u queue_family=%u\n",
          image_index,
          queue->info.queueFamilyIndex);
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
    return VK_TRUE;
}

static void pocketpc_vulkan_pre_present_cleanup(
    struct vulkan_queue *queue,
    VkResult present_result)
{
    struct vulkan_device *device;
    int result;

    if (!queue || !queue->device || !pocketpc_pre_present_copy_armed)
        return;

    device = queue->device;
    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device != device ||
        !pocketpc_pre_present_copy_submission.active)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    result = pocketpc_guest_present_copy_cleanup(
        device,
        queue,
        &pocketpc_pre_present_copy_submission);
    if (result != POCKETPC_GUEST_PRESENT_COPY_OK)
    {
        ERR("POCKETPC_VULKAN_PRESENT_COPY stage=copy_cleanup_failed result=%d present_result=%d pixels_copied_unproven=1 visible_present=0\n",
            result, present_result);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    InterlockedExchange(&pocketpc_pre_present_copy_armed, 0);
    InterlockedExchange(&pocketpc_pre_present_copy_completed, 1);
    TRACE("POCKETPC_VULKAN_PRESENT_COPY stage=copy_queue_completed exact_image=1 pixels_copied=1 returned_to_external=1 visible_present=0 present_result=%d queue_family=%u\n",
          present_result,
          queue->info.queueFamilyIndex);
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
}

'''

TABLE_IMAGE_ENTRY = "    .p_vulkan_image_presented = pocketpc_vulkan_image_presented,"
TABLE_V51_ENTRIES = """    .p_vulkan_image_pre_present = pocketpc_vulkan_image_pre_present,
    .p_vulkan_pre_present_cleanup = pocketpc_vulkan_pre_present_cleanup,"""

MAKEFILE_OWNERSHIP = "\tpocketpc_guest_external_image_ownership.c \\\n"
MAKEFILE_COPY = "\tpocketpc_guest_present_copy.c \\\n"


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def replace_once(path: Path, old: str, new: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        return False
    if text.count(old) != 1:
        raise RuntimeError(f"PRE_PRESENT_REPLACE_ANCHOR_INVALID:{path}:{text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def after_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PRE_PRESENT_AFTER_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    path.write_text(text.replace(anchor, anchor + "\n" + insertion, 1), encoding="utf-8")
    return True


def before_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PRE_PRESENT_BEFORE_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    path.write_text(text.replace(anchor, insertion + "\n" + anchor, 1), encoding="utf-8")
    return True


def copy_helpers(destination: Path) -> list[dict[str, object]]:
    copied: list[dict[str, object]] = []
    for name in HELPER_FILES:
        source = BRIDGE / name
        if not source.is_file():
            raise SystemExit(f"PRE_PRESENT_HELPER_SOURCE_MISSING:{name}")
        target = destination / name
        expected = source.read_text(encoding="utf-8")
        if name.endswith(".c"):
            expected = UNIX_MAKEDEP_PREAMBLE + expected
        if target.exists() and target.read_text(encoding="utf-8") != expected:
            raise SystemExit(f"PRE_PRESENT_HELPER_DESTINATION_MISMATCH:{name}")
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
    win32u = source / "dlls/win32u/vulkan.c"
    driver_dir = source / "dlls/winepocketpc.drv"
    driver = driver_dir / "vulkan.c"
    makefile = driver_dir / "Makefile.in"
    for required in (header, win32u, driver, makefile):
        if not required.is_file():
            raise SystemExit(f"PRE_PRESENT_REQUIRED_FILE_MISSING:{required}")

    helper_files = copy_helpers(driver_dir)

    version_changed = replace_once(header, V50, V51)
    callback_changed = after_once(header, IMAGE_CALLBACK, PRE_PRESENT_CALLBACKS)
    swapchain_state_changed = after_once(win32u, SWAPCHAIN_EXTENT_FIELD, SWAPCHAIN_TRANSFER_FIELD)
    swapchain_usage_changed = after_once(win32u, SWAPCHAIN_USAGE_ANCHOR, SWAPCHAIN_USAGE_PATCH)
    swapchain_transfer_record_changed = after_once(
        win32u, SWAPCHAIN_METADATA_ANCHOR, SWAPCHAIN_TRANSFER_RECORD
    )
    present_locals_changed = replace_once(win32u, PRESENT_LOCAL_ANCHOR, PRESENT_LOCALS)
    pre_present_changed = before_once(win32u, PRESENT_CALL, PRE_PRESENT_DISPATCH)
    cleanup_changed = after_once(win32u, PRESENT_CALL, POST_PRESENT_CLEANUP)

    include_changed = after_once(driver, DRIVER_OWNERSHIP_INCLUDE, DRIVER_COPY_INCLUDE)
    copy_globals_changed = after_once(driver, DRIVER_ROUNDTRIP_GLOBAL, DRIVER_COPY_GLOBALS)
    roundtrip_changed = replace_once(driver, DRIVER_ROUNDTRIP_IF, DRIVER_ROUNDTRIP_IF_V51)
    functions_changed = before_once(driver, DRIVER_FUNCTION_ANCHOR, DRIVER_COPY_FUNCTIONS)
    table_changed = after_once(driver, TABLE_IMAGE_ENTRY, TABLE_V51_ENTRIES)
    makefile_changed = after_once(makefile, MAKEFILE_OWNERSHIP.rstrip("\n"), MAKEFILE_COPY.rstrip("\n"))

    evidence = {
        "schemaVersion": 1,
        "status": "POCKETPC_PRE_PRESENT_COPY_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 51,
        "upstreamPinnedAbi": 47,
        "sourceIntegration": {
            "versionPatchChanged": version_changed,
            "driverCallbacksChanged": callback_changed,
            "swapchainTransferStateChanged": swapchain_state_changed,
            "swapchainTransferUsageChanged": swapchain_usage_changed,
            "swapchainTransferRecordChanged": swapchain_transfer_record_changed,
            "presentLocalsChanged": present_locals_changed,
            "prePresentDispatchChanged": pre_present_changed,
            "postPresentCleanupChanged": cleanup_changed,
            "driverIncludeChanged": include_changed,
            "driverGlobalsChanged": copy_globals_changed,
            "postPresentRoundTripGuardChanged": roundtrip_changed,
            "driverFunctionsChanged": functions_changed,
            "driverTableChanged": table_changed,
            "makefileChanged": makefile_changed,
        },
        "copyContract": {
            "exactPresentedImageRequired": True,
            "swapchainTransferSrcRequired": True,
            "destinationTransferDstRequired": True,
            "exactFormatRequired": True,
            "exactExtentRequired": True,
            "originalPresentWaitsConsumedByCopy": True,
            "replacementBinarySemaphoreFeedsPresent": True,
            "sourceRestoredToPresentLayout": True,
            "destinationReleasedToExternalGeneral": True,
            "samePresentQueue": True,
            "oneShotDiagnostic": True,
            "activationEnvironment": "POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC=1",
        },
        "pixelCopyImplemented": True,
        "pixelCopyExecuted": False,
        "formatConversionImplemented": False,
        "scalingImplemented": False,
        "androidVisibleConsumerImplemented": False,
        "hostVisiblePresentImplemented": False,
        "robloxExecuted": False,
        "helperFiles": helper_files,
        "files": {
            "vulkanDriverHeaderSha256": digest(header),
            "win32uVulkanSha256": digest(win32u),
            "pocketPcVulkanSha256": digest(driver),
            "pocketPcMakefileSha256": digest(makefile),
        },
        "notExecuted": [
            "Wine compilation after v51 patch",
            "pocketpc_guest_present_copy compilation",
            "pre-Present copy submission",
            "original Present semaphore consumption",
            "replacement Present semaphore wait",
            "exact swapchain pixels copied into PVI1",
            "Android external acquire after copied frame",
            "Android-visible frame",
            "DXVK Present physical test",
            "Roblox Desktop",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_PRE_PRESENT_COPY_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=51")
    print("pixel_copy_source_integrated=true")
    print("pixel_copy_executed=false")
    print("android_visible_consumer=false")
    print("roblox_executed=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
