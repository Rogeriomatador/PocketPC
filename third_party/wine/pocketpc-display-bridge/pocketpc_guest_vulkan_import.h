#ifndef POCKETPC_GUEST_VULKAN_IMPORT_H
#define POCKETPC_GUEST_VULKAN_IMPORT_H

#include "pocketpc_external_image_fd_protocol.h"

#include "windef.h"
#include "winbase.h"
#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum pocketpc_guest_vulkan_import_result {
    POCKETPC_GUEST_VULKAN_IMPORT_OK = 0,
    POCKETPC_GUEST_VULKAN_IMPORT_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_VULKAN_IMPORT_EXTENSION_NOT_READY = -2,
    POCKETPC_GUEST_VULKAN_IMPORT_FUNCTION_NOT_READY = -3,
    POCKETPC_GUEST_VULKAN_IMPORT_FD_PROPERTIES_FAILED = -4,
    POCKETPC_GUEST_VULKAN_IMPORT_IMAGE_CREATE_FAILED = -5,
    POCKETPC_GUEST_VULKAN_IMPORT_REQUIREMENTS_MISMATCH = -6,
    POCKETPC_GUEST_VULKAN_IMPORT_MEMORY_TYPE_MISSING = -7,
    POCKETPC_GUEST_VULKAN_IMPORT_MEMORY_ALLOCATE_FAILED = -8,
    POCKETPC_GUEST_VULKAN_IMPORT_IMAGE_BIND_FAILED = -9,
};

struct pocketpc_guest_vulkan_image {
    VkImage image;
    VkDeviceMemory memory;
    uint64_t resource_id;
    uint64_t generation;
    uint64_t sequence;
    VkDeviceSize allocation_size;
    uint32_t memory_type_bits;
    uint32_t memory_type_index;
};

/*
 * Import one PVI1 OPAQUE_FD allocation into the exact host VkDevice backing a
 * Wine Vulkan device. The received FD is consumed by Vulkan only after a
 * successful vkAllocateMemory; on failures before that point the caller still
 * owns it through pocketpc_external_image_fd_received.
 *
 * This proves resource import only. It does not establish semaphore/fence
 * synchronization, swapchain ownership, visible Present or Roblox support.
 */
int pocketpc_guest_vulkan_import_external_image(
    struct vulkan_device *device,
    struct pocketpc_external_image_fd_received *received,
    struct pocketpc_guest_vulkan_image *imported
);

void pocketpc_guest_vulkan_import_release(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_image *imported
);

#ifdef __cplusplus
}
#endif

#endif
