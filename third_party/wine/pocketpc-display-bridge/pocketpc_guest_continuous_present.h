#ifndef POCKETPC_GUEST_CONTINUOUS_PRESENT_H
#define POCKETPC_GUEST_CONTINUOUS_PRESENT_H

#include "pocketpc_guest_vulkan_import.h"
#include "pocketpc_guest_vulkan_timeline_semaphore.h"

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define POCKETPC_GUEST_CONTINUOUS_PRESENT_FIRST_FRAME 1ull
#define POCKETPC_GUEST_CONTINUOUS_PRESENT_MAX_FRAME 4611686018427387903ull
#define POCKETPC_GUEST_CONTINUOUS_PRESENT_HOST_WAIT_TIMEOUT_NS 5000000000ull

enum pocketpc_guest_continuous_present_result {
    POCKETPC_GUEST_CONTINUOUS_PRESENT_OK = 0,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_FRAME_OUT_OF_RANGE = -2,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_RESOURCE_IDENTITY_MISMATCH = -3,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_FORMAT_MISMATCH = -4,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_EXTENT_MISMATCH = -5,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_DESTINATION_USAGE_MISSING = -6,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_FUNCTION_NOT_READY = -7,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_HOST_WAIT_FAILED = -8,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_TIMELINE_COUNTER_FAILED = -9,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_TIMELINE_VALUE_UNEXPECTED = -10,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_POOL_CREATE_FAILED = -11,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_BUFFER_ALLOCATE_FAILED = -12,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_SEMAPHORE_CREATE_FAILED = -13,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_BEGIN_FAILED = -14,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_END_FAILED = -15,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_QUEUE_SUBMIT_FAILED = -16,
    POCKETPC_GUEST_CONTINUOUS_PRESENT_QUEUE_WAIT_FAILED = -17,
};

struct pocketpc_guest_continuous_present_submission {
    VkCommandPool command_pool;
    VkSemaphore present_wait_semaphore;
    uint64_t frame_sequence;
    uint64_t previous_host_consumed_value;
    uint64_t guest_ready_value;
    int active;
};

uint64_t pocketpc_guest_continuous_present_guest_ready_value(uint64_t frame_sequence);
uint64_t pocketpc_guest_continuous_present_previous_host_consumed_value(uint64_t frame_sequence);

/*
 * Correctness-first v52 guest transaction for exactly one Present frame.
 *
 * Before consuming the application's Present waits, the helper proves that the
 * PVS1 counter is exactly hostConsumed(N-1) (zero for frame 1). It then submits
 * one command buffer on the exact Wine Present queue that:
 *
 *   - consumes the original binary Present waits;
 *   - acquires the imported PVI1 image from VK_QUEUE_FAMILY_EXTERNAL;
 *   - copies the exact selected swapchain image into PVI1;
 *   - releases PVI1 back to VK_QUEUE_FAMILY_EXTERNAL in GENERAL;
 *   - signals a replacement binary semaphore for the real vkQueuePresentKHR;
 *   - signals the shared PVS1 timeline to guestReady(N), the odd value 2*N-1.
 *
 * The even hostConsumed(N) value is never signalled here. It belongs solely to
 * the Android host after readback/model delivery and external ownership return.
 *
 * This helper is source implementation only. A successful queue submission is
 * not Android-visible-frame evidence and is not Roblox evidence.
 */
int pocketpc_guest_continuous_present_submit(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    VkImage source_image,
    VkFormat source_format,
    VkExtent2D source_extent,
    const struct pocketpc_guest_vulkan_image *destination,
    VkFormat destination_format,
    uint32_t destination_width,
    uint32_t destination_height,
    VkImageUsageFlags destination_usage,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t frame_sequence,
    uint32_t wait_semaphore_count,
    const VkSemaphore *wait_semaphores,
    struct pocketpc_guest_continuous_present_submission *submission
);

/*
 * Called only after the real vkQueuePresentKHR consumed the replacement binary
 * semaphore. The first v52 implementation intentionally waits the exact Present
 * queue idle before destroying per-frame command resources. It favors ownership
 * correctness over performance; persistent pools/fences are a later optimization.
 */
int pocketpc_guest_continuous_present_cleanup(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    struct pocketpc_guest_continuous_present_submission *submission
);

#ifdef __cplusplus
}
#endif

#endif
