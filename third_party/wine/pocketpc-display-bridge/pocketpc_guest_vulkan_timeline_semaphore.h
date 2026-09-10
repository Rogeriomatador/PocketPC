#ifndef POCKETPC_GUEST_VULKAN_TIMELINE_SEMAPHORE_H
#define POCKETPC_GUEST_VULKAN_TIMELINE_SEMAPHORE_H

#include "pocketpc_external_timeline_semaphore_fd_protocol.h"

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum pocketpc_guest_vulkan_timeline_result {
    POCKETPC_GUEST_VULKAN_TIMELINE_OK = 0,
    POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_VULKAN_TIMELINE_EXTENSION_NOT_READY = -2,
    POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY = -3,
    POCKETPC_GUEST_VULKAN_TIMELINE_CREATE_FAILED = -4,
    POCKETPC_GUEST_VULKAN_TIMELINE_IMPORT_FAILED = -5,
};

struct pocketpc_guest_vulkan_timeline {
    VkSemaphore semaphore;
    uint64_t resource_id;
    uint64_t generation;
    uint64_t last_known_value;
};

int pocketpc_guest_vulkan_timeline_import(
    struct vulkan_device *device,
    struct pocketpc_external_timeline_semaphore_fd_received *received,
    struct pocketpc_guest_vulkan_timeline *timeline
);

void pocketpc_guest_vulkan_timeline_release(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline
);

#ifdef __cplusplus
}
#endif

#endif
