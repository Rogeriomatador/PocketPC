#include "pocketpc_guest_vulkan_import.h"

#include <string.h>

static int pocketpc_pick_memory_type(
    const struct vulkan_physical_device *physical_device,
    uint32_t allowed_bits,
    uint32_t *memory_type_index
) {
    uint32_t index;

    if (!physical_device || !memory_type_index || allowed_bits == 0u)
        return 0;

    for (index = 0; index < physical_device->memory_properties.memoryTypeCount; ++index) {
        if (
            (allowed_bits & (1u << index)) != 0u &&
            (physical_device->memory_properties.memoryTypes[index].propertyFlags &
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0u
        ) {
            *memory_type_index = index;
            return 1;
        }
    }

    for (index = 0; index < physical_device->memory_properties.memoryTypeCount; ++index) {
        if ((allowed_bits & (1u << index)) != 0u) {
            *memory_type_index = index;
            return 1;
        }
    }

    return 0;
}

static void pocketpc_guest_vulkan_import_reset(
    struct pocketpc_guest_vulkan_image *imported
) {
    if (!imported) return;
    memset(imported, 0, sizeof(*imported));
    imported->image = VK_NULL_HANDLE;
    imported->memory = VK_NULL_HANDLE;
}

int pocketpc_guest_vulkan_import_external_image(
    struct vulkan_device *device,
    struct pocketpc_external_image_fd_received *received,
    struct pocketpc_guest_vulkan_image *imported
) {
    VkExternalMemoryImageCreateInfo external_image;
    VkImageCreateInfo image_info;
    VkMemoryRequirements requirements;
    VkMemoryFdPropertiesKHR fd_properties;
    VkImportMemoryFdInfoKHR import_fd;
    VkMemoryDedicatedAllocateInfo dedicated;
    VkMemoryAllocateInfo allocation;
    VkResult result;
    uint32_t allowed_memory_types;
    uint32_t selected_memory_type = 0u;

    if (!device || !received || !imported || received->resource_fd < 0)
        return POCKETPC_GUEST_VULKAN_IMPORT_INVALID_ARGUMENT;

    pocketpc_guest_vulkan_import_reset(imported);

    if (!device->extensions.has_VK_KHR_external_memory_fd)
        return POCKETPC_GUEST_VULKAN_IMPORT_EXTENSION_NOT_READY;

    if (
        !device->p_vkCreateImage ||
        !device->p_vkDestroyImage ||
        !device->p_vkGetImageMemoryRequirements ||
        !device->p_vkAllocateMemory ||
        !device->p_vkFreeMemory ||
        !device->p_vkBindImageMemory ||
        !device->p_vkGetMemoryFdPropertiesKHR
    ) {
        return POCKETPC_GUEST_VULKAN_IMPORT_FUNCTION_NOT_READY;
    }

    memset(&fd_properties, 0, sizeof(fd_properties));
    fd_properties.sType = VK_STRUCTURE_TYPE_MEMORY_FD_PROPERTIES_KHR;
    result = device->p_vkGetMemoryFdPropertiesKHR(
        device->host.device,
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT,
        received->resource_fd,
        &fd_properties
    );
    if (result != VK_SUCCESS || fd_properties.memoryTypeBits == 0u)
        return POCKETPC_GUEST_VULKAN_IMPORT_FD_PROPERTIES_FAILED;

    memset(&external_image, 0, sizeof(external_image));
    external_image.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external_image.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    memset(&image_info, 0, sizeof(image_info));
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.pNext = &external_image;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = (VkFormat)received->metadata.format;
    image_info.extent.width = received->metadata.width;
    image_info.extent.height = received->metadata.height;
    image_info.extent.depth = 1u;
    image_info.mipLevels = 1u;
    image_info.arrayLayers = 1u;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = (VkImageUsageFlags)received->metadata.usage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    result = device->p_vkCreateImage(
        device->host.device,
        &image_info,
        NULL,
        &imported->image
    );
    if (result != VK_SUCCESS)
        return POCKETPC_GUEST_VULKAN_IMPORT_IMAGE_CREATE_FAILED;

    memset(&requirements, 0, sizeof(requirements));
    device->p_vkGetImageMemoryRequirements(
        device->host.device,
        imported->image,
        &requirements
    );

    if (
        requirements.size == 0u ||
        requirements.memoryTypeBits == 0u ||
        requirements.size != (VkDeviceSize)received->metadata.allocation_size
    ) {
        device->p_vkDestroyImage(device->host.device, imported->image, NULL);
        pocketpc_guest_vulkan_import_reset(imported);
        return POCKETPC_GUEST_VULKAN_IMPORT_REQUIREMENTS_MISMATCH;
    }

    allowed_memory_types =
        requirements.memoryTypeBits &
        fd_properties.memoryTypeBits &
        received->metadata.memory_type_bits;
    if (!pocketpc_pick_memory_type(
            device->physical_device,
            allowed_memory_types,
            &selected_memory_type
        )) {
        device->p_vkDestroyImage(device->host.device, imported->image, NULL);
        pocketpc_guest_vulkan_import_reset(imported);
        return POCKETPC_GUEST_VULKAN_IMPORT_MEMORY_TYPE_MISSING;
    }

    memset(&dedicated, 0, sizeof(dedicated));
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = imported->image;

    memset(&import_fd, 0, sizeof(import_fd));
    import_fd.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
    import_fd.pNext = &dedicated;
    import_fd.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_fd.fd = received->resource_fd;

    memset(&allocation, 0, sizeof(allocation));
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &import_fd;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = selected_memory_type;

    result = device->p_vkAllocateMemory(
        device->host.device,
        &allocation,
        NULL,
        &imported->memory
    );
    if (result != VK_SUCCESS) {
        device->p_vkDestroyImage(device->host.device, imported->image, NULL);
        pocketpc_guest_vulkan_import_reset(imported);
        return POCKETPC_GUEST_VULKAN_IMPORT_MEMORY_ALLOCATE_FAILED;
    }

    /*
     * VK_KHR_external_memory_fd transfers ownership of an OPAQUE_FD to Vulkan
     * after successful vkAllocateMemory. Prevent generic cleanup from closing
     * a descriptor that the Vulkan implementation now owns.
     */
    received->resource_fd = -1;

    result = device->p_vkBindImageMemory(
        device->host.device,
        imported->image,
        imported->memory,
        0u
    );
    if (result != VK_SUCCESS) {
        device->p_vkFreeMemory(device->host.device, imported->memory, NULL);
        device->p_vkDestroyImage(device->host.device, imported->image, NULL);
        pocketpc_guest_vulkan_import_reset(imported);
        return POCKETPC_GUEST_VULKAN_IMPORT_IMAGE_BIND_FAILED;
    }

    imported->resource_id = received->metadata.resource_id;
    imported->generation = received->metadata.generation;
    imported->sequence = received->metadata.sequence;
    imported->allocation_size = requirements.size;
    imported->memory_type_bits = allowed_memory_types;
    imported->memory_type_index = selected_memory_type;

    return POCKETPC_GUEST_VULKAN_IMPORT_OK;
}

void pocketpc_guest_vulkan_import_release(
    struct vulkan_device *device,
    struct pocketpc_guest_vulkan_image *imported
) {
    if (!device || !imported) return;

    if (imported->memory != VK_NULL_HANDLE && device->p_vkFreeMemory)
        device->p_vkFreeMemory(device->host.device, imported->memory, NULL);
    if (imported->image != VK_NULL_HANDLE && device->p_vkDestroyImage)
        device->p_vkDestroyImage(device->host.device, imported->image, NULL);

    pocketpc_guest_vulkan_import_reset(imported);
}
