#include "pocketpc_guest_continuous_present.h"

#include <stdlib.h>
#include <string.h>

static void pocketpc_guest_continuous_present_reset(
    struct pocketpc_guest_continuous_present_submission *submission
) {
    if (!submission) return;
    memset(submission, 0, sizeof(*submission));
    submission->command_pool = VK_NULL_HANDLE;
    submission->present_wait_semaphore = VK_NULL_HANDLE;
}

static void pocketpc_guest_continuous_present_destroy_idle(
    struct vulkan_device *device,
    struct pocketpc_guest_continuous_present_submission *submission
) {
    if (!device || !submission) return;

    if (
        submission->present_wait_semaphore != VK_NULL_HANDLE &&
        device->p_vkDestroySemaphore
    ) {
        device->p_vkDestroySemaphore(
            device->host.device,
            submission->present_wait_semaphore,
            NULL
        );
    }
    if (
        submission->command_pool != VK_NULL_HANDLE &&
        device->p_vkDestroyCommandPool
    ) {
        device->p_vkDestroyCommandPool(
            device->host.device,
            submission->command_pool,
            NULL
        );
    }
    pocketpc_guest_continuous_present_reset(submission);
}

uint64_t pocketpc_guest_continuous_present_guest_ready_value(uint64_t frame_sequence)
{
    if (
        frame_sequence < POCKETPC_GUEST_CONTINUOUS_PRESENT_FIRST_FRAME ||
        frame_sequence > POCKETPC_GUEST_CONTINUOUS_PRESENT_MAX_FRAME
    ) {
        return 0u;
    }
    return frame_sequence * 2u - 1u;
}

uint64_t pocketpc_guest_continuous_present_previous_host_consumed_value(
    uint64_t frame_sequence
) {
    if (
        frame_sequence < POCKETPC_GUEST_CONTINUOUS_PRESENT_FIRST_FRAME ||
        frame_sequence > POCKETPC_GUEST_CONTINUOUS_PRESENT_MAX_FRAME
    ) {
        return UINT64_MAX;
    }
    return (frame_sequence - 1u) * 2u;
}

static int pocketpc_guest_continuous_present_wait_for_host(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_timeline *timeline,
    uint64_t frame_sequence,
    uint64_t *previous_host_consumed
) {
    uint64_t expected;
    uint64_t observed = 0u;
    int result;

    if (!device || !timeline || !previous_host_consumed)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_INVALID_ARGUMENT;

    expected = pocketpc_guest_continuous_present_previous_host_consumed_value(
        frame_sequence
    );
    if (expected == UINT64_MAX)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_FRAME_OUT_OF_RANGE;

    if (expected != 0u)
    {
        result = pocketpc_guest_vulkan_timeline_wait_cpu(
            device,
            timeline,
            expected,
            POCKETPC_GUEST_CONTINUOUS_PRESENT_HOST_WAIT_TIMEOUT_NS
        );
        if (result != POCKETPC_GUEST_VULKAN_TIMELINE_OK)
            return POCKETPC_GUEST_CONTINUOUS_PRESENT_HOST_WAIT_FAILED;
    }

    result = pocketpc_guest_vulkan_timeline_get_counter(
        device,
        timeline,
        &observed
    );
    if (result != POCKETPC_GUEST_VULKAN_TIMELINE_OK)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_TIMELINE_COUNTER_FAILED;
    if (observed != expected)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_TIMELINE_VALUE_UNEXPECTED;

    *previous_host_consumed = expected;
    return POCKETPC_GUEST_CONTINUOUS_PRESENT_OK;
}

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
) {
    VkCommandPoolCreateInfo pool_info;
    VkCommandBufferAllocateInfo allocate_info;
    VkCommandBufferBeginInfo begin_info;
    VkSemaphoreCreateInfo semaphore_info;
    VkImageMemoryBarrier before[2];
    VkImageMemoryBarrier after[2];
    VkImageCopy region;
    VkTimelineSemaphoreSubmitInfo timeline_info;
    VkSubmitInfo submit_info;
    VkSemaphore signal_semaphores[2];
    uint64_t signal_values[2];
    VkPipelineStageFlags *wait_stages = NULL;
    uint64_t *wait_values = NULL;
    VkCommandBuffer command_buffer = VK_NULL_HANDLE;
    uint64_t previous_host_consumed = 0u;
    uint64_t guest_ready;
    VkResult result;
    int wait_result;

    if (
        !device || !queue || queue->device != device ||
        queue->host.queue == VK_NULL_HANDLE ||
        source_image == VK_NULL_HANDLE || !destination ||
        destination->image == VK_NULL_HANDLE || !timeline ||
        timeline->semaphore == VK_NULL_HANDLE || !submission ||
        source_extent.width == 0u || source_extent.height == 0u ||
        destination_width == 0u || destination_height == 0u ||
        (wait_semaphore_count != 0u && !wait_semaphores)
    ) {
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_INVALID_ARGUMENT;
    }

    guest_ready = pocketpc_guest_continuous_present_guest_ready_value(frame_sequence);
    if (guest_ready == 0u)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_FRAME_OUT_OF_RANGE;

    if (
        destination->resource_id == 0u ||
        destination->generation == 0u ||
        destination->resource_id != timeline->resource_id ||
        destination->generation != timeline->generation
    ) {
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_RESOURCE_IDENTITY_MISMATCH;
    }
    if (source_format != destination_format)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_FORMAT_MISMATCH;
    if (
        source_extent.width != destination_width ||
        source_extent.height != destination_height
    ) {
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_EXTENT_MISMATCH;
    }
    if ((destination_usage & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0u)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_DESTINATION_USAGE_MISSING;

    if (
        !device->p_vkCreateCommandPool ||
        !device->p_vkDestroyCommandPool ||
        !device->p_vkAllocateCommandBuffers ||
        !device->p_vkBeginCommandBuffer ||
        !device->p_vkEndCommandBuffer ||
        !device->p_vkCreateSemaphore ||
        !device->p_vkDestroySemaphore ||
        !device->p_vkCmdPipelineBarrier ||
        !device->p_vkCmdCopyImage ||
        !device->p_vkQueueSubmit ||
        !device->p_vkQueueWaitIdle ||
        !device->p_vkGetSemaphoreCounterValue ||
        !device->p_vkWaitSemaphores
    ) {
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_FUNCTION_NOT_READY;
    }

    pocketpc_guest_continuous_present_reset(submission);

    wait_result = pocketpc_guest_continuous_present_wait_for_host(
        device,
        timeline,
        frame_sequence,
        &previous_host_consumed
    );
    if (wait_result != POCKETPC_GUEST_CONTINUOUS_PRESENT_OK)
        return wait_result;

    if (wait_semaphore_count != 0u)
    {
        wait_stages = calloc(wait_semaphore_count, sizeof(*wait_stages));
        wait_values = calloc(wait_semaphore_count, sizeof(*wait_values));
        if (!wait_stages || !wait_values)
        {
            free(wait_stages);
            free(wait_values);
            return POCKETPC_GUEST_CONTINUOUS_PRESENT_INVALID_ARGUMENT;
        }
        for (uint32_t i = 0u; i < wait_semaphore_count; ++i)
        {
            wait_stages[i] = VK_PIPELINE_STAGE_TRANSFER_BIT;
            /* vkQueuePresentKHR waits are binary; timeline value is ignored and stays zero. */
            wait_values[i] = 0u;
        }
    }

    memset(&pool_info, 0, sizeof(pool_info));
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    pool_info.queueFamilyIndex = queue->info.queueFamilyIndex;
    result = device->p_vkCreateCommandPool(
        device->host.device,
        &pool_info,
        NULL,
        &submission->command_pool
    );
    if (result != VK_SUCCESS)
    {
        free(wait_stages);
        free(wait_values);
        pocketpc_guest_continuous_present_reset(submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_POOL_CREATE_FAILED;
    }

    memset(&allocate_info, 0, sizeof(allocate_info));
    allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate_info.commandPool = submission->command_pool;
    allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate_info.commandBufferCount = 1u;
    result = device->p_vkAllocateCommandBuffers(
        device->host.device,
        &allocate_info,
        &command_buffer
    );
    if (result != VK_SUCCESS)
    {
        free(wait_stages);
        free(wait_values);
        pocketpc_guest_continuous_present_destroy_idle(device, submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_BUFFER_ALLOCATE_FAILED;
    }

    memset(&semaphore_info, 0, sizeof(semaphore_info));
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    result = device->p_vkCreateSemaphore(
        device->host.device,
        &semaphore_info,
        NULL,
        &submission->present_wait_semaphore
    );
    if (result != VK_SUCCESS)
    {
        free(wait_stages);
        free(wait_values);
        pocketpc_guest_continuous_present_destroy_idle(device, submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_SEMAPHORE_CREATE_FAILED;
    }

    memset(&begin_info, 0, sizeof(begin_info));
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = device->p_vkBeginCommandBuffer(command_buffer, &begin_info);
    if (result != VK_SUCCESS)
    {
        free(wait_stages);
        free(wait_values);
        pocketpc_guest_continuous_present_destroy_idle(device, submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_BEGIN_FAILED;
    }

    memset(before, 0, sizeof(before));

    before[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    before[0].srcAccessMask = 0u;
    before[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    before[0].oldLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    before[0].newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    before[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    before[0].image = source_image;
    before[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    before[0].subresourceRange.baseMipLevel = 0u;
    before[0].subresourceRange.levelCount = 1u;
    before[0].subresourceRange.baseArrayLayer = 0u;
    before[0].subresourceRange.layerCount = 1u;

    before[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    before[1].srcAccessMask = 0u;
    before[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    before[1].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    before[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    before[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    before[1].dstQueueFamilyIndex = queue->info.queueFamilyIndex;
    before[1].image = destination->image;
    before[1].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    before[1].subresourceRange.baseMipLevel = 0u;
    before[1].subresourceRange.levelCount = 1u;
    before[1].subresourceRange.baseArrayLayer = 0u;
    before[1].subresourceRange.layerCount = 1u;

    device->p_vkCmdPipelineBarrier(
        command_buffer,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0u,
        0u,
        NULL,
        0u,
        NULL,
        2u,
        before
    );

    memset(&region, 0, sizeof(region));
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.mipLevel = 0u;
    region.srcSubresource.baseArrayLayer = 0u;
    region.srcSubresource.layerCount = 1u;
    region.dstSubresource = region.srcSubresource;
    region.extent.width = source_extent.width;
    region.extent.height = source_extent.height;
    region.extent.depth = 1u;

    device->p_vkCmdCopyImage(
        command_buffer,
        source_image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        destination->image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1u,
        &region
    );

    memset(after, 0, sizeof(after));

    after[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    after[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    after[0].dstAccessMask = 0u;
    after[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    after[0].newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    after[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    after[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    after[0].image = source_image;
    after[0].subresourceRange = before[0].subresourceRange;

    after[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    after[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    after[1].dstAccessMask = 0u;
    after[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    after[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    after[1].srcQueueFamilyIndex = queue->info.queueFamilyIndex;
    after[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    after[1].image = destination->image;
    after[1].subresourceRange = before[1].subresourceRange;

    device->p_vkCmdPipelineBarrier(
        command_buffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0u,
        0u,
        NULL,
        0u,
        NULL,
        2u,
        after
    );

    result = device->p_vkEndCommandBuffer(command_buffer);
    if (result != VK_SUCCESS)
    {
        free(wait_stages);
        free(wait_values);
        pocketpc_guest_continuous_present_destroy_idle(device, submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_COMMAND_END_FAILED;
    }

    signal_semaphores[0] = submission->present_wait_semaphore;
    signal_semaphores[1] = timeline->semaphore;
    signal_values[0] = 0u;
    signal_values[1] = guest_ready;

    memset(&timeline_info, 0, sizeof(timeline_info));
    timeline_info.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
    timeline_info.waitSemaphoreValueCount = wait_semaphore_count;
    timeline_info.pWaitSemaphoreValues = wait_values;
    timeline_info.signalSemaphoreValueCount = 2u;
    timeline_info.pSignalSemaphoreValues = signal_values;

    memset(&submit_info, 0, sizeof(submit_info));
    submit_info.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit_info.pNext = &timeline_info;
    submit_info.waitSemaphoreCount = wait_semaphore_count;
    submit_info.pWaitSemaphores = wait_semaphores;
    submit_info.pWaitDstStageMask = wait_stages;
    submit_info.commandBufferCount = 1u;
    submit_info.pCommandBuffers = &command_buffer;
    submit_info.signalSemaphoreCount = 2u;
    submit_info.pSignalSemaphores = signal_semaphores;

    result = device->p_vkQueueSubmit(
        queue->host.queue,
        1u,
        &submit_info,
        VK_NULL_HANDLE
    );
    free(wait_stages);
    free(wait_values);
    if (result != VK_SUCCESS)
    {
        pocketpc_guest_continuous_present_destroy_idle(device, submission);
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_QUEUE_SUBMIT_FAILED;
    }

    submission->frame_sequence = frame_sequence;
    submission->previous_host_consumed_value = previous_host_consumed;
    submission->guest_ready_value = guest_ready;
    submission->active = 1;

    /* This records only that vkQueueSubmit accepted the odd signal. */
    timeline->last_known_value = guest_ready;
    timeline->queue_signal_value = guest_ready;
    timeline->queue_signal_submitted = 1u;
    return POCKETPC_GUEST_CONTINUOUS_PRESENT_OK;
}

int pocketpc_guest_continuous_present_cleanup(
    struct vulkan_device *device,
    struct vulkan_queue *queue,
    struct pocketpc_guest_continuous_present_submission *submission
) {
    VkResult result;

    if (
        !device || !queue || queue->device != device ||
        queue->host.queue == VK_NULL_HANDLE ||
        !submission || !submission->active
    ) {
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_INVALID_ARGUMENT;
    }
    if (!device->p_vkQueueWaitIdle)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_FUNCTION_NOT_READY;

    result = device->p_vkQueueWaitIdle(queue->host.queue);
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_CONTINUOUS_PRESENT_QUEUE_WAIT_FAILED;

    pocketpc_guest_continuous_present_destroy_idle(device, submission);
    return POCKETPC_GUEST_CONTINUOUS_PRESENT_OK;
}
