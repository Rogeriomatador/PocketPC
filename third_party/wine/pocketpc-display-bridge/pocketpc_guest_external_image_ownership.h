#ifndef POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_H
#define POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_H

#include "pocketpc_guest_vulkan_import.h"

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * PVI1 v1 uses GENERAL as the process-boundary layout. The Android exporter
 * must release queue-family ownership to VK_QUEUE_FAMILY_EXTERNAL in GENERAL
 * before this guest acquire primitive may be used. The guest returns the image
 * to VK_QUEUE_FAMILY_EXTERNAL in GENERAL before Android may consume it again.
 *
 * These constants are a source contract only until the Android exporter has a
 * matching release/acquire implementation and physical execution evidence.
 */
#define POCKETPC_EXTERNAL_IMAGE_BOUNDARY_LAYOUT VK_IMAGE_LAYOUT_GENERAL

enum pocketpc_guest_external_image_ownership_result {
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_OK = 0,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_FUNCTION_NOT_READY = -2,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_POOL_CREATE_FAILED = -3,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_BUFFER_ALLOCATE_FAILED = -4,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_BEGIN_FAILED = -5,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_END_FAILED = -6,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_QUEUE_SUBMIT_FAILED = -7,
    POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_QUEUE_WAIT_FAILED = -8,
};

/*
 * Acquire an imported PVI1 image from VK_QUEUE_FAMILY_EXTERNAL onto the exact
 * Wine queue supplied by win32u. This uses a one-time command buffer and waits
 * for queue idle intentionally: it is a correctness-first precursor, not the
 * final high-performance frame path.
 */
int pocketpc_guest_external_image_acquire(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    const struct pocketpc_guest_vulkan_image *image,
    VkImageLayout target_layout
);

/*
 * Release the imported image from the exact Wine queue back to
 * VK_QUEUE_FAMILY_EXTERNAL, returning it to GENERAL for Android-side acquire.
 */
int pocketpc_guest_external_image_release(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    const struct pocketpc_guest_vulkan_image *image,
    VkImageLayout current_layout
);

#ifdef __cplusplus
}
#endif

#endif
