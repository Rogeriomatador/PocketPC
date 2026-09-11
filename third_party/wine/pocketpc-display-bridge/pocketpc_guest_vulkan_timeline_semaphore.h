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
    POCKETPC_GUEST_VULKAN_TIMELINE_SIGNAL_FAILED = -6,
    POCKETPC_GUEST_VULKAN_TIMELINE_WAIT_FAILED = -7,
    POCKETPC_GUEST_VULKAN_TIMELINE_COUNTER_FAILED = -8,
    POCKETPC_GUEST_VULKAN_TIMELINE_NON_MONOTONIC = -9,
    POCKETPC_GUEST_VULKAN_TIMELINE_QUEUE_UNAVAILABLE = -10,
    POCKETPC_GUEST_VULKAN_TIMELINE_QUEUE_SUBMIT_FAILED = -11,
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

int pocketpc_guest_vulkan_timeline_get_counter(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t *value
);

/* CPU-side diagnostic primitive. This does not prove queue/GPU ordering. */
int pocketpc_guest_vulkan_timeline_signal_cpu(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value
);

/* CPU-side diagnostic primitive. This does not prove queue/GPU ordering. */
int pocketpc_guest_vulkan_timeline_wait_cpu(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value,
    uint64_t timeout_ns
);

/*
 * Submit an empty ordered GPU operation on one real Wine Vulkan queue and
 * signal the imported PVS1 timeline semaphore to [value]. A successful return
 * proves only that vkQueueSubmit accepted the timeline signal. It does not
 * prove completion, Present ordering, swapchain capture, or Android visibility.
 */
int pocketpc_guest_vulkan_timeline_signal_queue(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value
);

void pocketpc_guest_vulkan_timeline_release(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline
);

#ifdef __cplusplus
}
#endif

#endif
