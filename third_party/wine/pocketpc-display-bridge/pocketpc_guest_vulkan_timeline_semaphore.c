#include "pocketpc_guest_vulkan_timeline_semaphore.h"

#include <string.h>

static void pocketpc_guest_vulkan_timeline_reset(
    struct pocketpc_guest_vulkan_timeline *timeline
) {
    if (!timeline) return;
    memset(timeline, 0, sizeof(*timeline));
    timeline->semaphore = VK_NULL_HANDLE;
}

static int pocketpc_guest_vulkan_timeline_runtime_ready(
    const struct vulkan_device *device,
    const struct pocketpc_guest_vulkan_timeline *timeline
) {
    return
        device &&
        timeline &&
        timeline->semaphore != VK_NULL_HANDLE;
}

int pocketpc_guest_vulkan_timeline_import(
    struct vulkan_device *device,
    struct pocketpc_external_timeline_semaphore_fd_received *received,
    struct pocketpc_guest_vulkan_timeline *timeline
) {
    VkSemaphoreTypeCreateInfo type_info;
    VkSemaphoreCreateInfo create_info;
    VkImportSemaphoreFdInfoKHR import_info;
    VkResult result;

    if (!device || !received || !timeline || received->semaphore_fd < 0)
        return POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT;

    pocketpc_guest_vulkan_timeline_reset(timeline);

    if (!device->extensions.has_VK_KHR_external_semaphore_fd)
        return POCKETPC_GUEST_VULKAN_TIMELINE_EXTENSION_NOT_READY;

    if (
        !device->p_vkCreateSemaphore ||
        !device->p_vkDestroySemaphore ||
        !device->p_vkImportSemaphoreFdKHR
    ) {
        return POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY;
    }

    memset(&type_info, 0, sizeof(type_info));
    type_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    type_info.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    type_info.initialValue = received->metadata.initial_value;

    memset(&create_info, 0, sizeof(create_info));
    create_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    create_info.pNext = &type_info;

    result = device->p_vkCreateSemaphore(
        device->host.device,
        &create_info,
        NULL,
        &timeline->semaphore
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_TIMELINE_CREATE_FAILED;

    memset(&import_info, 0, sizeof(import_info));
    import_info.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    import_info.semaphore = timeline->semaphore;
    import_info.flags = 0;
    import_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_info.fd = received->semaphore_fd;

    result = device->p_vkImportSemaphoreFdKHR(
        device->host.device,
        &import_info
    );
    if (result != VK_SUCCESS) {
        device->p_vkDestroySemaphore(
            device->host.device,
            timeline->semaphore,
            NULL
        );
        pocketpc_guest_vulkan_timeline_reset(timeline);
        return POCKETPC_GUEST_VULKAN_TIMELINE_IMPORT_FAILED;
    }

    received->semaphore_fd = -1;
    timeline->resource_id = received->metadata.resource_id;
    timeline->generation = received->metadata.generation;
    timeline->last_known_value = received->metadata.initial_value;
    return POCKETPC_GUEST_VULKAN_TIMELINE_OK;
}

int pocketpc_guest_vulkan_timeline_get_counter(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t *value
) {
    VkResult result;
    uint64_t current = 0u;

    if (!pocketpc_guest_vulkan_timeline_runtime_ready(device, timeline) || !value)
        return POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT;
    if (!device->p_vkGetSemaphoreCounterValue)
        return POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY;

    result = device->p_vkGetSemaphoreCounterValue(
        device->host.device,
        timeline->semaphore,
        &current
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_TIMELINE_COUNTER_FAILED;

    timeline->last_known_value = current;
    *value = current;
    return POCKETPC_GUEST_VULKAN_TIMELINE_OK;
}

int pocketpc_guest_vulkan_timeline_signal_cpu(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value
) {
    VkSemaphoreSignalInfo signal_info;
    VkResult result;
    uint64_t current = 0u;
    int counter_result;

    if (!pocketpc_guest_vulkan_timeline_runtime_ready(device, timeline) || value == 0u)
        return POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT;
    if (!device->p_vkSignalSemaphore)
        return POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY;

    counter_result = pocketpc_guest_vulkan_timeline_get_counter(
        device,
        timeline,
        &current
    );
    if (counter_result != POCKETPC_GUEST_VULKAN_TIMELINE_OK)
        return counter_result;
    if (value <= current)
        return POCKETPC_GUEST_VULKAN_TIMELINE_NON_MONOTONIC;

    memset(&signal_info, 0, sizeof(signal_info));
    signal_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO;
    signal_info.semaphore = timeline->semaphore;
    signal_info.value = value;

    result = device->p_vkSignalSemaphore(
        device->host.device,
        &signal_info
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_TIMELINE_SIGNAL_FAILED;

    timeline->last_known_value = value;
    return POCKETPC_GUEST_VULKAN_TIMELINE_OK;
}

int pocketpc_guest_vulkan_timeline_wait_cpu(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value,
    uint64_t timeout_ns
) {
    VkSemaphoreWaitInfo wait_info;
    VkResult result;
    VkSemaphore semaphore;

    if (!pocketpc_guest_vulkan_timeline_runtime_ready(device, timeline) || value == 0u)
        return POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT;
    if (!device->p_vkWaitSemaphores)
        return POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY;

    semaphore = timeline->semaphore;
    memset(&wait_info, 0, sizeof(wait_info));
    wait_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    wait_info.semaphoreCount = 1u;
    wait_info.pSemaphores = &semaphore;
    wait_info.pValues = &value;

    result = device->p_vkWaitSemaphores(
        device->host.device,
        &wait_info,
        timeout_ns
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_TIMELINE_WAIT_FAILED;

    if (value > timeline->last_known_value)
        timeline->last_known_value = value;
    return POCKETPC_GUEST_VULKAN_TIMELINE_OK;
}

int pocketpc_guest_vulkan_timeline_signal_queue(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t value
) {
    VkTimelineSemaphoreSubmitInfo timeline_info;
    VkSubmitInfo submit_info;
    VkSemaphore semaphore;
    VkResult result;
    uint64_t current = 0u;
    int counter_result;

    if (!pocketpc_guest_vulkan_timeline_runtime_ready(device, timeline) ||
        !queue || queue->device != device || value == 0u)
        return POCKETPC_GUEST_VULKAN_TIMELINE_INVALID_ARGUMENT;
    if (!queue->host.queue)
        return POCKETPC_GUEST_VULKAN_TIMELINE_QUEUE_UNAVAILABLE;
    if (!device->p_vkQueueSubmit)
        return POCKETPC_GUEST_VULKAN_TIMELINE_FUNCTION_NOT_READY;

    counter_result = pocketpc_guest_vulkan_timeline_get_counter(device, timeline, &current);
    if (counter_result != POCKETPC_GUEST_VULKAN_TIMELINE_OK)
        return counter_result;
    if (value <= current)
        return POCKETPC_GUEST_VULKAN_TIMELINE_NON_MONOTONIC;

    semaphore = timeline->semaphore;
    memset(&timeline_info, 0, sizeof(timeline_info));
    timeline_info.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
    timeline_info.signalSemaphoreValueCount = 1u;
    timeline_info.pSignalSemaphoreValues = &value;

    memset(&submit_info, 0, sizeof(submit_info));
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.pNext = &timeline_info;
    submit_info.signalSemaphoreCount = 1u;
    submit_info.pSignalSemaphores = &semaphore;

    result = device->p_vkQueueSubmit(
        queue->host.queue,
        1u,
        &submit_info,
        VK_NULL_HANDLE
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_TIMELINE_QUEUE_SUBMIT_FAILED;

    timeline->last_known_value = value;
    return POCKETPC_GUEST_VULKAN_TIMELINE_OK;
}

void pocketpc_guest_vulkan_timeline_release(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline
) {
    if (!device || !timeline) return;
    if (timeline->semaphore != VK_NULL_HANDLE && device->p_vkDestroySemaphore) {
        device->p_vkDestroySemaphore(
            device->host.device,
            timeline->semaphore,
            NULL
        );
    }
    pocketpc_guest_vulkan_timeline_reset(timeline);
}
