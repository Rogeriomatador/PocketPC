#!/usr/bin/env python3
"""Extend the prepared PocketPC Wine overlay with exact presented-image identity.

Input must already have been prepared by prepare-wine-pocketpc-driver.py. This
patch bumps the private PocketPC Vulkan driver ABI from v49 to v50, caches the
host VkImage handles belonging to every host swapchain and reports the exact
image selected by VkPresentInfoKHR::pImageIndices back to winepocketpc.drv.

It also carries the guest queue-family ownership helper and wires a one-shot
correctness diagnostic. The Android broker performs the matching initial
release to VK_QUEUE_FAMILY_EXTERNAL before PVI1 is sent; the diagnostic then
acquires and releases the imported image on the exact Wine Present queue.

This is source integration only. It does NOT copy pixels, prove execution,
produce an Android-visible frame or prove Roblox works.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "third_party/wine/pocketpc-display-bridge"
OWNERSHIP_FILES = (
    "pocketpc_guest_external_image_ownership.h",
    "pocketpc_guest_external_image_ownership.c",
)
UNIX_MAKEDEP_PREAMBLE = """#if 0
#pragma makedep unix
#endif

"""

V49 = "#define WINE_VULKAN_DRIVER_VERSION 49"
V50 = "#define WINE_VULKAN_DRIVER_VERSION 50"

DRIVER_CALLBACK_ANCHOR = (
    "    void (*p_vulkan_queue_presented)( struct vulkan_queue *queue, VkResult result );"
)
DRIVER_IMAGE_CALLBACK = (
    "    void (*p_vulkan_image_presented)( struct vulkan_queue *queue, VkImage image, "
    "VkFormat format, VkExtent2D extent, uint32_t image_index, VkResult result );"
)

SWAPCHAIN_STRUCT_OLD = """struct swapchain
{
    struct vulkan_swapchain obj;
    struct surface *surface;
    VkExtent2D extents;
};"""
SWAPCHAIN_STRUCT_NEW = """struct swapchain
{
    struct vulkan_swapchain obj;
    struct surface *surface;
    VkExtent2D extents;

    /* PocketPC v50: exact host images for VkPresentInfoKHR image-index mapping. */
    VkImage *pocketpc_host_images;
    uint32_t pocketpc_host_image_count;
    VkFormat pocketpc_host_image_format;
    VkExtent2D pocketpc_host_extents;
};"""

SWAPCHAIN_INSERT_ANCHOR = "    instance->p_insert_object( instance, &swapchain->obj.obj );"
SWAPCHAIN_IMAGE_CACHE = r'''    /*
     * PocketPC v50 source integration: retain the host swapchain image list so
     * pImageIndices can later resolve to the exact host VkImage. Failure to
     * cache is non-fatal for Wine; PocketPC simply withholds image evidence.
     */
    swapchain->pocketpc_host_image_format = create_info_host.imageFormat;
    swapchain->pocketpc_host_extents = create_info_host.imageExtent;
    if (device->p_vkGetSwapchainImagesKHR)
    {
        uint32_t pocketpc_image_count = 0;
        if (device->p_vkGetSwapchainImagesKHR( device->host.device, host_swapchain,
                                               &pocketpc_image_count, NULL ) == VK_SUCCESS &&
            pocketpc_image_count &&
            (swapchain->pocketpc_host_images = calloc( pocketpc_image_count, sizeof(VkImage) )))
        {
            uint32_t pocketpc_capacity = pocketpc_image_count;
            if (device->p_vkGetSwapchainImagesKHR( device->host.device, host_swapchain,
                                                   &pocketpc_capacity,
                                                   swapchain->pocketpc_host_images ) == VK_SUCCESS &&
                pocketpc_capacity)
                swapchain->pocketpc_host_image_count = pocketpc_capacity;
            else
            {
                free( swapchain->pocketpc_host_images );
                swapchain->pocketpc_host_images = NULL;
            }
        }
    }'''

SWAPCHAIN_DESTROY_ANCHOR = "    instance->p_remove_object( instance, &swapchain->obj.obj );"
SWAPCHAIN_IMAGE_RELEASE = """    free( swapchain->pocketpc_host_images );
    swapchain->pocketpc_host_images = NULL;
    swapchain->pocketpc_host_image_count = 0;"""

PRESENT_QUEUE_CALLBACK = """    if (driver_funcs->p_vulkan_queue_presented)
        driver_funcs->p_vulkan_queue_presented( queue, res );"""
PRESENT_IMAGE_CALLBACK = r'''    if (driver_funcs->p_vulkan_image_presented && present_info->pImageIndices)
    {
        for (uint32_t pocketpc_i = 0; pocketpc_i < present_info->swapchainCount; pocketpc_i++)
        {
            struct swapchain *pocketpc_swapchain = swapchain_from_handle( client_swapchains[pocketpc_i] );
            uint32_t pocketpc_image_index = present_info->pImageIndices[pocketpc_i];
            VkResult pocketpc_present_result = present_info->pResults ? present_info->pResults[pocketpc_i] : res;

            if (!pocketpc_swapchain->pocketpc_host_images ||
                pocketpc_image_index >= pocketpc_swapchain->pocketpc_host_image_count)
                continue;

            driver_funcs->p_vulkan_image_presented(
                queue,
                pocketpc_swapchain->pocketpc_host_images[pocketpc_image_index],
                pocketpc_swapchain->pocketpc_host_image_format,
                pocketpc_swapchain->pocketpc_host_extents,
                pocketpc_image_index,
                pocketpc_present_result );
        }
    }'''

POCKETPC_DRIVER_INCLUDE_ANCHOR = '#include "pocketpc_guest_vulkan_import.h"'
POCKETPC_DRIVER_OWNERSHIP_INCLUDE = '#include "pocketpc_guest_external_image_ownership.h"'
POCKETPC_DRIVER_FUNCTION_ANCHOR = "static void pocketpc_headless_client_surface_destroy("
POCKETPC_DRIVER_IMAGE_FUNCTION = r'''static LONG pocketpc_external_ownership_roundtrip_done;

static void pocketpc_vulkan_image_presented(
    struct vulkan_queue *queue,
    VkImage image,
    VkFormat format,
    VkExtent2D extent,
    uint32_t image_index,
    VkResult present_result)
{
    struct vulkan_device *device;

    if (!queue || !queue->device || image == VK_NULL_HANDLE ||
        !extent.width || !extent.height)
        return;
    if (present_result != VK_SUCCESS && present_result != VK_SUBOPTIMAL_KHR)
        return;

    device = queue->device;
    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device == device &&
        !pocketpc_guest_resource.stopping &&
        pocketpc_guest_resource.image_imported &&
        pocketpc_guest_resource.timeline_imported)
    {
        TRACE("POCKETPC_VULKAN_PRESENT_IMAGE stage=exact_swapchain_image_observed execution_evidence=0 pixels_copied=0 visible_present=0 image=%p format=%u width=%u height=%u image_index=%u queue_family=%u\n",
              (void *)(uintptr_t)image,
              (unsigned int)format,
              extent.width,
              extent.height,
              image_index,
              queue->info.queueFamilyIndex);

        if (InterlockedCompareExchange(&pocketpc_external_ownership_roundtrip_done, 1, 0) == 0)
        {
            int acquire_result = pocketpc_guest_external_image_acquire(
                device,
                queue,
                &pocketpc_guest_resource.image,
                VK_IMAGE_LAYOUT_GENERAL);
            if (acquire_result == POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_OK)
            {
                int release_result = pocketpc_guest_external_image_release(
                    device,
                    queue,
                    &pocketpc_guest_resource.image,
                    VK_IMAGE_LAYOUT_GENERAL);
                if (release_result == POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_OK)
                {
                    TRACE("POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=roundtrip_completed execution_evidence=0 pixels_copied=0 visible_present=0 queue_family=%u\n",
                          queue->info.queueFamilyIndex);
                }
                else
                {
                    ERR("POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=release_failed result=%d\n", release_result);
                    InterlockedExchange(&pocketpc_external_ownership_roundtrip_done, 0);
                }
            }
            else
            {
                ERR("POCKETPC_VULKAN_EXTERNAL_OWNERSHIP stage=acquire_failed result=%d\n", acquire_result);
                InterlockedExchange(&pocketpc_external_ownership_roundtrip_done, 0);
            }
        }
    }
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
}

'''

POCKETPC_TABLE_ANCHOR = "    .p_vulkan_queue_presented = pocketpc_vulkan_queue_presented,"
POCKETPC_TABLE_IMAGE_ENTRY = "    .p_vulkan_image_presented = pocketpc_vulkan_image_presented,"


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
        raise RuntimeError(f"PRESENT_IMAGE_REPLACE_ANCHOR_INVALID:{path}:{text.count(old)}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True


def after_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PRESENT_IMAGE_AFTER_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    path.write_text(text.replace(anchor, anchor + "\n" + insertion, 1), encoding="utf-8")
    return True


def before_once(path: Path, anchor: str, insertion: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return False
    if text.count(anchor) != 1:
        raise RuntimeError(f"PRESENT_IMAGE_BEFORE_ANCHOR_INVALID:{path}:{text.count(anchor)}")
    path.write_text(text.replace(anchor, insertion + "\n" + anchor, 1), encoding="utf-8")
    return True


def copy_ownership_files(destination: Path) -> list[dict[str, object]]:
    copied: list[dict[str, object]] = []
    for name in OWNERSHIP_FILES:
        source_file = BRIDGE / name
        if not source_file.is_file():
            raise SystemExit(f"PRESENT_IMAGE_OWNERSHIP_SOURCE_MISSING:{name}")
        destination_file = destination / name
        if destination_file.exists():
            expected = source_file.read_text(encoding="utf-8")
            if name.endswith(".c"):
                expected = UNIX_MAKEDEP_PREAMBLE + expected
            if destination_file.read_text(encoding="utf-8") != expected:
                raise SystemExit(f"PRESENT_IMAGE_OWNERSHIP_DESTINATION_MISMATCH:{name}")
        else:
            shutil.copyfile(source_file, destination_file)
            if name.endswith(".c"):
                original = destination_file.read_text(encoding="utf-8")
                destination_file.write_text(
                    UNIX_MAKEDEP_PREAMBLE + original,
                    encoding="utf-8",
                )
        copied.append(
            {
                "path": f"dlls/winepocketpc.drv/{name}",
                "sha256": digest(destination_file),
                "source": "PocketPC external image ownership helper",
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
    for required in (header, win32u, driver):
        if not required.is_file():
            raise SystemExit(f"PRESENT_IMAGE_REQUIRED_FILE_MISSING:{required}")

    ownership_files = copy_ownership_files(driver_dir)

    version_changed = replace_once(header, V49, V50)
    callback_changed = after_once(header, DRIVER_CALLBACK_ANCHOR, DRIVER_IMAGE_CALLBACK)
    swapchain_changed = replace_once(win32u, SWAPCHAIN_STRUCT_OLD, SWAPCHAIN_STRUCT_NEW)
    cache_changed = after_once(win32u, SWAPCHAIN_INSERT_ANCHOR, SWAPCHAIN_IMAGE_CACHE)
    release_changed = after_once(win32u, SWAPCHAIN_DESTROY_ANCHOR, SWAPCHAIN_IMAGE_RELEASE)
    present_changed = after_once(win32u, PRESENT_QUEUE_CALLBACK, PRESENT_IMAGE_CALLBACK)
    ownership_include_changed = after_once(
        driver,
        POCKETPC_DRIVER_INCLUDE_ANCHOR,
        POCKETPC_DRIVER_OWNERSHIP_INCLUDE,
    )
    driver_function_changed = before_once(
        driver,
        POCKETPC_DRIVER_FUNCTION_ANCHOR,
        POCKETPC_DRIVER_IMAGE_FUNCTION,
    )
    driver_table_changed = after_once(driver, POCKETPC_TABLE_ANCHOR, POCKETPC_TABLE_IMAGE_ENTRY)

    evidence = {
        "schemaVersion": 3,
        "status": "POCKETPC_EXACT_PRESENTED_IMAGE_SOURCE_INTEGRATED_NOT_BUILT_NOT_EXECUTED",
        "privateWineVulkanAbi": 50,
        "upstreamPinnedAbi": 47,
        "sourceIntegration": {
            "versionPatchChanged": version_changed,
            "driverCallbackPatchChanged": callback_changed,
            "swapchainStatePatchChanged": swapchain_changed,
            "swapchainImageCachePatchChanged": cache_changed,
            "swapchainImageReleasePatchChanged": release_changed,
            "presentImageDispatchPatchChanged": present_changed,
            "pocketpcDriverOwnershipIncludePatchChanged": ownership_include_changed,
            "pocketpcDriverCallbackPatchChanged": driver_function_changed,
            "pocketpcDriverTablePatchChanged": driver_table_changed,
            "guestExternalOwnershipHelperCarried": True,
            "guestExternalOwnershipRoundTripSourceIntegrated": True,
        },
        "exactIdentity": {
            "usesVkGetSwapchainImagesKHR": True,
            "usesVkPresentInfoImageIndex": True,
            "passesExactHostVkImage": True,
            "passesHostFormat": True,
            "passesHostExtent": True,
            "passesQueueFamily": True,
        },
        "externalOwnership": {
            "guestAcquireReleasePrimitiveImplemented": True,
            "boundaryLayout": "VK_IMAGE_LAYOUT_GENERAL",
            "externalQueueFamily": "VK_QUEUE_FAMILY_EXTERNAL",
            "androidInitialReleaseImplemented": True,
            "guestPrimitiveActivated": True,
            "roundTripSourceIntegrated": True,
            "executed": False,
        },
        "pixelCopyImplemented": False,
        "gpuSynchronizationExecuted": False,
        "hostVisiblePresentImplemented": False,
        "robloxExecuted": False,
        "ownershipFiles": ownership_files,
        "files": {
            "vulkanDriverHeaderSha256": digest(header),
            "win32uVulkanSha256": digest(win32u),
            "pocketPcVulkanSha256": digest(driver),
        },
        "notExecuted": [
            "Wine compilation after v50 patch",
            "guest external ownership helper compilation",
            "win32u compilation after swapchain cache patch",
            "winepocketpc.so compilation after exact-image callback patch",
            "VkGetSwapchainImagesKHR cache path",
            "exact presented VkImage callback",
            "guest external queue-family acquire/release round-trip",
            "Android initial queue-family release physical execution",
            "swapchain pixel copy",
            "Android-visible frame",
            "DXVK Present physical test",
            "Roblox",
        ],
    }
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    print("POCKETPC_EXACT_PRESENTED_IMAGE_SOURCE_INTEGRATED_NOT_EXECUTED")
    print("private_wine_vulkan_abi=50")
    print("exact_host_swapchain_image_identity=true")
    print("guest_external_ownership_primitive=true")
    print("android_initial_external_release_source_integrated=true")
    print("guest_external_ownership_roundtrip_source_integrated=true")
    print("external_ownership_roundtrip_executed=false")
    print("pixel_copy=false")
    print("host_visible_present=false")
    print("runtime_execution_evidence=false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
