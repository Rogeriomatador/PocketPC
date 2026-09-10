#include "pocketpc_guest_vulkan_timeline_semaphore.h"

#include <string.h>

static void pocketpc_guest_vulkan_timeline_reset(
    struct pocketpc_guest_vulkan_timeline *timeline
) {
    if (!timeline) return;
    memset(timeline, 0, sizeof(*timeline));
    timeline->semaphore = VK_NULL_HANDLE;
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

    /* Successful OPAQUE_FD import transfers FD ownership to Vulkan. */
    received->semaphore_fd = -1;
    timeline->resource_id = received->metadata.resource_id;
    timeline->generation = received->metadata.generation;
    timeline->last_known_value = received->metadata.initial_value;
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
