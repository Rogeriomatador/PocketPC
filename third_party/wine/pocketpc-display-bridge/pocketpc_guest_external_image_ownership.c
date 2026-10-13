#include "pocketpc_guest_external_image_ownership.h"

#include <string.h>

static int pocketpc_guest_external_image_transfer(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    const struct pocketpc_guest_vulkan_image *image,
    uint32_t source_queue_family,
    uint32_t destination_queue_family,
    VkImageLayout old_layout,
    VkImageLayout new_layout,
    VkAccessFlags source_access,
    VkAccessFlags destination_access,
    VkPipelineStageFlags source_stage,
    VkPipelineStageFlags destination_stage
) {
    VkCommandPoolCreateInfo pool_info;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo allocate_info;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkCommandBufferBeginInfo begin_info;
    VkImageMemoryBarrier barrier;
    VkSubmitInfo submit_info;
    VkResult result;
    int status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_OK;

    if (!device || !queue || !image || image->image == VK_NULL_HANDLE ||
        queue->device != device || queue->host.queue == VK_NULL_HANDLE)
        return POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_INVALID_ARGUMENT;

    if (!device->p_vkCreateCommandPool || !device->p_vkDestroyCommandPool ||
        !device->p_vkAllocateCommandBuffers || !device->p_vkFreeCommandBuffers ||
        !device->p_vkBeginCommandBuffer || !device->p_vkEndCommandBuffer ||
        !device->p_vkCmdPipelineBarrier || !device->p_vkQueueSubmit ||
        !device->p_vkQueueWaitIdle)
        return POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_FUNCTION_NOT_READY;

    memset(&pool_info, 0, sizeof(pool_info));
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    pool_info.queueFamilyIndex = queue->info.queueFamilyIndex;
    result = device->p_vkCreateCommandPool(device->host.device, &pool_info, NULL, &pool);
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_POOL_CREATE_FAILED;

    memset(&allocate_info, 0, sizeof(allocate_info));
    allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate_info.commandPool = pool;
    allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate_info.commandBufferCount = 1u;
    result = device->p_vkAllocateCommandBuffers(device->host.device, &allocate_info, &command);
    if (result != VK_SUCCESS)
    {
        status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_BUFFER_ALLOCATE_FAILED;
        goto cleanup;
    }

    memset(&begin_info, 0, sizeof(begin_info));
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = device->p_vkBeginCommandBuffer(command, &begin_info);
    if (result != VK_SUCCESS)
    {
        status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_BEGIN_FAILED;
        goto cleanup;
    }

    memset(&barrier, 0, sizeof(barrier));
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = source_access;
    barrier.dstAccessMask = destination_access;
    barrier.oldLayout = old_layout;
    barrier.newLayout = new_layout;
    barrier.srcQueueFamilyIndex = source_queue_family;
    barrier.dstQueueFamilyIndex = destination_queue_family;
    barrier.image = image->image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0u;
    barrier.subresourceRange.levelCount = 1u;
    barrier.subresourceRange.baseArrayLayer = 0u;
    barrier.subresourceRange.layerCount = 1u;

    device->p_vkCmdPipelineBarrier(
        command,
        source_stage,
        destination_stage,
        0u,
        0u,
        NULL,
        0u,
        NULL,
        1u,
        &barrier
    );

    result = device->p_vkEndCommandBuffer(command);
    if (result != VK_SUCCESS)
    {
        status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_COMMAND_END_FAILED;
        goto cleanup;
    }

    memset(&submit_info, 0, sizeof(submit_info));
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.commandBufferCount = 1u;
    submit_info.pCommandBuffers = &command;
    result = device->p_vkQueueSubmit(queue->host.queue, 1u, &submit_info, VK_NULL_HANDLE);
    if (result != VK_SUCCESS)
    {
        status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_QUEUE_SUBMIT_FAILED;
        goto cleanup;
    }

    result = device->p_vkQueueWaitIdle(queue->host.queue);
    if (result != VK_SUCCESS)
        status = POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_QUEUE_WAIT_FAILED;

cleanup:
    if (command != VK_NULL_HANDLE)
        device->p_vkFreeCommandBuffers(device->host.device, pool, 1u, &command);
    if (pool != VK_NULL_HANDLE)
        device->p_vkDestroyCommandPool(device->host.device, pool, NULL);
    return status;
}

int pocketpc_guest_external_image_acquire(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    const struct pocketpc_guest_vulkan_image *image,
    VkImageLayout target_layout
) {
    if (target_layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL &&
        target_layout != VK_IMAGE_LAYOUT_GENERAL)
        return POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_INVALID_ARGUMENT;

    return pocketpc_guest_external_image_transfer(
        device,
        queue,
        image,
        VK_QUEUE_FAMILY_EXTERNAL,
        queue->info.queueFamilyIndex,
        POCKETPC_EXTERNAL_IMAGE_BOUNDARY_LAYOUT,
        target_layout,
        0u,
        target_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
            ? VK_ACCESS_TRANSFER_WRITE_BIT
            : (VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT),
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        target_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
            ? VK_PIPELINE_STAGE_TRANSFER_BIT
            : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
    );
}

int pocketpc_guest_external_image_release(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    const struct pocketpc_guest_vulkan_image *image,
    VkImageLayout current_layout
) {
    VkAccessFlags source_access;
    VkPipelineStageFlags source_stage;

    if (current_layout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
    {
        source_access = VK_ACCESS_TRANSFER_WRITE_BIT;
        source_stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    }
    else if (current_layout == VK_IMAGE_LAYOUT_GENERAL)
    {
        source_access = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        source_stage = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    }
    else
    {
        return POCKETPC_GUEST_EXTERNAL_IMAGE_OWNERSHIP_INVALID_ARGUMENT;
    }

    return pocketpc_guest_external_image_transfer(
        device,
        queue,
        image,
        queue->info.queueFamilyIndex,
        VK_QUEUE_FAMILY_EXTERNAL,
        current_layout,
        POCKETPC_EXTERNAL_IMAGE_BOUNDARY_LAYOUT,
        source_access,
        0u,
        source_stage,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT
    );
}
