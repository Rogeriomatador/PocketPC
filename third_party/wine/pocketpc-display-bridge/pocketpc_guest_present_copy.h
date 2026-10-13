#ifndef POCKETPC_GUEST_PRESENT_COPY_H
#define POCKETPC_GUEST_PRESENT_COPY_H

#include "pocketpc_guest_vulkan_import.h"

#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

#ifdef __cplusplus
extern "C" {
#endif

enum pocketpc_guest_present_copy_result {
    POCKETPC_GUEST_PRESENT_COPY_OK = 0,
    POCKETPC_GUEST_PRESENT_COPY_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_PRESENT_COPY_FORMAT_MISMATCH = -2,
    POCKETPC_GUEST_PRESENT_COPY_EXTENT_MISMATCH = -3,
    POCKETPC_GUEST_PRESENT_COPY_DESTINATION_USAGE_MISSING = -4,
    POCKETPC_GUEST_PRESENT_COPY_FUNCTION_NOT_READY = -5,
    POCKETPC_GUEST_PRESENT_COPY_COMMAND_POOL_CREATE_FAILED = -6,
    POCKETPC_GUEST_PRESENT_COPY_COMMAND_BUFFER_ALLOCATE_FAILED = -7,
    POCKETPC_GUEST_PRESENT_COPY_SEMAPHORE_CREATE_FAILED = -8,
    POCKETPC_GUEST_PRESENT_COPY_COMMAND_BEGIN_FAILED = -9,
    POCKETPC_GUEST_PRESENT_COPY_COMMAND_END_FAILED = -10,
    POCKETPC_GUEST_PRESENT_COPY_QUEUE_SUBMIT_FAILED = -11,
    POCKETPC_GUEST_PRESENT_COPY_QUEUE_WAIT_FAILED = -12,
};

struct pocketpc_guest_present_copy_submission {
    VkCommandPool command_pool;
    VkSemaphore present_wait_semaphore;
    int active;
};

/*
 * Queue a correctness-first, one-frame copy before vkQueuePresentKHR.
 *
 * The caller must pass the exact host swapchain VkImage selected by
 * VkPresentInfoKHR::pImageIndices and the already translated host wait
 * semaphores. This function consumes those waits in an intermediate submit,
 * copies into the imported PVI1 image, releases that image back to
 * VK_QUEUE_FAMILY_EXTERNAL in GENERAL, then signals a binary semaphore. The
 * real Present must wait on present_wait_semaphore instead of waiting on the
 * original semaphores a second time.
 *
 * v1 is intentionally strict: source/destination format and extent must match.
 * Format conversion/scaling is a later gate and must not be inferred here.
 */
int pocketpc_guest_present_copy_submit(
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
    uint32_t wait_semaphore_count,
    const VkSemaphore *wait_semaphores,
    struct pocketpc_guest_present_copy_submission *submission
);

/*
 * Cleanup is called only after vkQueuePresentKHR has consumed the replacement
 * semaphore. It waits the exact Present queue idle before destroying the
 * command pool and semaphore. This is deliberately slow and suitable only for
 * the one-shot proof path.
 */
int pocketpc_guest_present_copy_cleanup(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    struct pocketpc_guest_present_copy_submission *submission
);

#ifdef __cplusplus
}
#endif

#endif
