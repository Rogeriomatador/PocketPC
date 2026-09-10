#include <jni.h>
#include <vulkan/vulkan.h>

#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kExternalImageMagic = 0x31495650u;  // PVI1 little-endian.
constexpr uint16_t kExternalImageVersion = 1;
constexpr size_t kExternalImagePayloadBytes = 64;
constexpr uint32_t kExternalTimelineMagic = 0x31535650u;  // PVS1 little-endian.
constexpr uint16_t kExternalTimelineVersion = 1;
constexpr size_t kExternalTimelinePayloadBytes = 40;
constexpr uint32_t kTimelineRoleFrameOwnership = 1;
constexpr uint32_t kMaxDimension = 4096;
constexpr size_t kMaxResources = 16;
constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkImageUsageFlags kImageUsage =
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;

struct Resource {
    uint64_t generation = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkSemaphore timeline = VK_NULL_HANDLE;
    VkDeviceSize allocation_size = 0;
    uint32_t memory_type_bits = 0;
    uint32_t memory_type_index = 0;
    PFN_vkGetMemoryFdKHR get_memory_fd = nullptr;
    PFN_vkGetSemaphoreFdKHR get_semaphore_fd = nullptr;
};

std::mutex g_mutex;
std::unordered_map<uint64_t, Resource> g_resources;
std::atomic<uint64_t> g_next_id {1};
std::atomic<uint64_t> g_next_generation {1};

jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void PutU16Le(uint8_t* out, uint16_t value) {
    out[0] = static_cast<uint8_t>(value & 0xffu);
    out[1] = static_cast<uint8_t>((value >> 8u) & 0xffu);
}

void PutU32Le(uint8_t* out, uint32_t value) {
    out[0] = static_cast<uint8_t>(value & 0xffu);
    out[1] = static_cast<uint8_t>((value >> 8u) & 0xffu);
    out[2] = static_cast<uint8_t>((value >> 16u) & 0xffu);
    out[3] = static_cast<uint8_t>((value >> 24u) & 0xffu);
}

void PutU64Le(uint8_t* out, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i) {
        out[i] = static_cast<uint8_t>((value >> (i * 8u)) & 0xffu);
    }
}

void EncodeExternalImagePayload(
        uint8_t out[kExternalImagePayloadBytes],
        uint64_t resource_id,
        const Resource& resource,
        uint64_t sequence) {
    std::memset(out, 0, kExternalImagePayloadBytes);
    PutU32Le(out + 0, kExternalImageMagic);
    PutU16Le(out + 4, kExternalImageVersion);
    PutU16Le(out + 6, 0);
    PutU64Le(out + 8, resource_id);
    PutU64Le(out + 16, resource.generation);
    PutU64Le(out + 24, sequence);
    PutU32Le(out + 32, resource.width);
    PutU32Le(out + 36, resource.height);
    PutU32Le(out + 40, static_cast<uint32_t>(kFormat));
    PutU32Le(out + 44, static_cast<uint32_t>(kImageUsage));
    PutU64Le(out + 48, static_cast<uint64_t>(resource.allocation_size));
    PutU32Le(out + 56, resource.memory_type_bits);
    PutU32Le(out + 60, resource.memory_type_index);
}

void EncodeExternalTimelinePayload(
        uint8_t out[kExternalTimelinePayloadBytes],
        uint64_t resource_id,
        const Resource& resource) {
    std::memset(out, 0, kExternalTimelinePayloadBytes);
    PutU32Le(out + 0, kExternalTimelineMagic);
    PutU16Le(out + 4, kExternalTimelineVersion);
    PutU16Le(out + 6, 0);
    PutU64Le(out + 8, resource_id);
    PutU64Le(out + 16, resource.generation);
    PutU64Le(out + 24, 0u);
    PutU32Le(out + 32, kTimelineRoleFrameOwnership);
    PutU32Le(out + 36, 0u);
}

bool IsSeqpacket(int socket_fd) {
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);
    return socket_fd >= 0 &&
        getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) == 0 &&
        socket_type == SOCK_SEQPACKET;
}

int SendFdPayload(
        int socket_fd,
        int fd,
        const uint8_t* payload,
        size_t payload_bytes) {
    if (!IsSeqpacket(socket_fd) || fd < 0 || payload == nullptr || payload_bytes == 0) {
        return -1;
    }

    struct iovec iov {};
    iov.iov_base = const_cast<uint8_t*>(payload);
    iov.iov_len = payload_bytes;

    char control[CMSG_SPACE(sizeof(int))];
    std::memset(control, 0, sizeof(control));

    struct msghdr message {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    struct cmsghdr* header = CMSG_FIRSTHDR(&message);
    if (header == nullptr) return -2;
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    message.msg_controllen = CMSG_SPACE(sizeof(int));

    ssize_t written;
    do {
        written = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (written < 0 && errno == EINTR);

    return written == static_cast<ssize_t>(payload_bytes) ? 0 : -3;
}

int SendExternalImageFd(
        int socket_fd,
        int fd,
        uint64_t resource_id,
        const Resource& resource,
        uint64_t sequence) {
    if (resource_id == 0 || resource.generation == 0 || sequence == 0 ||
        resource.width == 0 || resource.height == 0 ||
        resource.allocation_size == 0 || resource.memory_type_bits == 0 ||
        resource.memory_type_index >= 32 ||
        (resource.memory_type_bits & (1u << resource.memory_type_index)) == 0) {
        return -1;
    }

    uint8_t payload[kExternalImagePayloadBytes];
    EncodeExternalImagePayload(payload, resource_id, resource, sequence);
    return SendFdPayload(socket_fd, fd, payload, sizeof(payload));
}

int SendExternalTimelineFd(
        int socket_fd,
        int fd,
        uint64_t resource_id,
        const Resource& resource) {
    if (resource_id == 0 || resource.generation == 0 || resource.timeline == VK_NULL_HANDLE) {
        return -1;
    }

    uint8_t payload[kExternalTimelinePayloadBytes];
    EncodeExternalTimelinePayload(payload, resource_id, resource);
    return SendFdPayload(socket_fd, fd, payload, sizeof(payload));
}

void Destroy(Resource* resource) {
    if (resource == nullptr) return;
    if (resource->device != VK_NULL_HANDLE) {
        if (resource->timeline != VK_NULL_HANDLE) {
            vkDestroySemaphore(resource->device, resource->timeline, nullptr);
            resource->timeline = VK_NULL_HANDLE;
        }
        if (resource->image != VK_NULL_HANDLE) {
            vkDestroyImage(resource->device, resource->image, nullptr);
            resource->image = VK_NULL_HANDLE;
        }
        if (resource->memory != VK_NULL_HANDLE) {
            vkFreeMemory(resource->device, resource->memory, nullptr);
            resource->memory = VK_NULL_HANDLE;
        }
        vkDestroyDevice(resource->device, nullptr);
        resource->device = VK_NULL_HANDLE;
    }
    if (resource->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(resource->instance, nullptr);
        resource->instance = VK_NULL_HANDLE;
    }
}

bool HasDeviceExtension(VkPhysicalDevice physical, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS ||
        count > 4096) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (count != 0) {
        uint32_t read_count = count;
        const VkResult result = vkEnumerateDeviceExtensionProperties(
            physical, nullptr, &read_count, extensions.data());
        if (result != VK_SUCCESS) return false;
        extensions.resize(read_count);
    }
    for (const auto& extension : extensions) {
        if (std::strcmp(extension.extensionName, name) == 0) return true;
    }
    return false;
}

int FindGraphicsQueue(VkPhysicalDevice physical, uint32_t* queue_family) {
    if (queue_family == nullptr) return -1;
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, nullptr);
    if (count == 0 || count > 256) return -2;
    std::vector<VkQueueFamilyProperties> properties(count);
    vkGetPhysicalDeviceQueueFamilyProperties(physical, &count, properties.data());
    for (uint32_t i = 0; i < count; ++i) {
        if (properties[i].queueCount > 0 &&
            (properties[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
            *queue_family = i;
            return 0;
        }
    }
    return -3;
}

int FindMemoryType(
        VkPhysicalDevice physical,
        uint32_t type_bits,
        uint32_t* memory_type_index) {
    if (memory_type_index == nullptr || type_bits == 0) return -1;
    VkPhysicalDeviceMemoryProperties properties {};
    vkGetPhysicalDeviceMemoryProperties(physical, &properties);

    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((type_bits & (1u << i)) != 0 &&
            (properties.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0) {
            *memory_type_index = i;
            return 0;
        }
    }
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        if ((type_bits & (1u << i)) != 0) {
            *memory_type_index = i;
            return 0;
        }
    }
    return -2;
}

bool TimelineSemaphoreSupported(
        VkPhysicalDevice physical,
        const VkPhysicalDeviceProperties& properties,
        bool* needs_extension) {
    if (needs_extension == nullptr) return false;

    const bool core_12 =
        VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
        (VK_VERSION_MAJOR(properties.apiVersion) == 1 &&
            VK_VERSION_MINOR(properties.apiVersion) >= 2);
    *needs_extension = !core_12;

    if (*needs_extension && !HasDeviceExtension(physical, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
        return false;
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_features {};
    timeline_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    VkPhysicalDeviceFeatures2 features {};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &timeline_features;
    vkGetPhysicalDeviceFeatures2(physical, &features);
    return timeline_features.timelineSemaphore == VK_TRUE;
}

int CreateResource(uint32_t width, uint32_t height, Resource* out, std::string* reason) {
    if (out == nullptr || reason == nullptr) return -1;

    VkApplicationInfo app {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketPC External Image FD Broker";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "PocketPC";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instance_info {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    VkResult result = vkCreateInstance(&instance_info, nullptr, &out->instance);
    if (result != VK_SUCCESS) {
        *reason = "instance-create-failed:" + std::to_string(result);
        return -2;
    }

    uint32_t physical_count = 0;
    result = vkEnumeratePhysicalDevices(out->instance, &physical_count, nullptr);
    if (result != VK_SUCCESS || physical_count == 0 || physical_count > 64) {
        *reason = "physical-device-enumeration-failed:" + std::to_string(result);
        return -3;
    }
    std::vector<VkPhysicalDevice> physicals(physical_count);
    result = vkEnumeratePhysicalDevices(out->instance, &physical_count, physicals.data());
    if (result != VK_SUCCESS) {
        *reason = "physical-device-read-failed:" + std::to_string(result);
        return -4;
    }

    out->physical = physicals.front();
    for (VkPhysicalDevice candidate : physicals) {
        VkPhysicalDeviceProperties candidate_properties {};
        vkGetPhysicalDeviceProperties(candidate, &candidate_properties);
        if (candidate_properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            out->physical = candidate;
            break;
        }
    }

    VkPhysicalDeviceProperties selected_properties {};
    vkGetPhysicalDeviceProperties(out->physical, &selected_properties);
    if (
        VK_VERSION_MAJOR(selected_properties.apiVersion) < 1 ||
        (VK_VERSION_MAJOR(selected_properties.apiVersion) == 1 &&
            VK_VERSION_MINOR(selected_properties.apiVersion) < 1)
    ) {
        *reason = "vulkan-1.1-required";
        return -5;
    }

    if (!HasDeviceExtension(out->physical, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) {
        *reason = "external-memory-fd-extension-missing";
        return -6;
    }
    if (!HasDeviceExtension(out->physical, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
        *reason = "external-semaphore-fd-extension-missing";
        return -7;
    }

    bool needs_timeline_extension = false;
    if (!TimelineSemaphoreSupported(out->physical, selected_properties, &needs_timeline_extension)) {
        *reason = "timeline-semaphore-not-supported";
        return -8;
    }

    uint32_t queue_family = 0;
    if (FindGraphicsQueue(out->physical, &queue_family) != 0) {
        *reason = "graphics-queue-missing";
        return -9;
    }

    const float queue_priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &queue_priority;

    std::vector<const char*> device_extensions = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
    };
    if (needs_timeline_extension) {
        device_extensions.push_back(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_enable {};
    timeline_enable.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    timeline_enable.timelineSemaphore = VK_TRUE;

    VkDeviceCreateInfo device_info {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.pNext = &timeline_enable;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = static_cast<uint32_t>(device_extensions.size());
    device_info.ppEnabledExtensionNames = device_extensions.data();
    result = vkCreateDevice(out->physical, &device_info, nullptr, &out->device);
    if (result != VK_SUCCESS) {
        *reason = "device-create-failed:" + std::to_string(result);
        return -10;
    }

    out->get_memory_fd = reinterpret_cast<PFN_vkGetMemoryFdKHR>(
        vkGetDeviceProcAddr(out->device, "vkGetMemoryFdKHR"));
    out->get_semaphore_fd = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(out->device, "vkGetSemaphoreFdKHR"));
    if (out->get_memory_fd == nullptr) {
        *reason = "vkGetMemoryFdKHR-missing";
        return -11;
    }
    if (out->get_semaphore_fd == nullptr) {
        *reason = "vkGetSemaphoreFdKHR-missing";
        return -12;
    }

    VkPhysicalDeviceExternalImageFormatInfo external_format_info {};
    external_format_info.sType =
        VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    external_format_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    VkPhysicalDeviceImageFormatInfo2 format_info {};
    format_info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    format_info.pNext = &external_format_info;
    format_info.format = kFormat;
    format_info.type = VK_IMAGE_TYPE_2D;
    format_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    format_info.usage = kImageUsage;
    format_info.flags = 0;

    VkExternalImageFormatProperties external_properties {};
    external_properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    VkImageFormatProperties2 format_properties {};
    format_properties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    format_properties.pNext = &external_properties;
    result = vkGetPhysicalDeviceImageFormatProperties2(
        out->physical, &format_info, &format_properties);
    if (result != VK_SUCCESS) {
        *reason = "external-image-format-unsupported:" + std::to_string(result);
        return -13;
    }
    const VkExternalMemoryProperties& external_memory =
        external_properties.externalMemoryProperties;
    const VkExternalMemoryFeatureFlags features = external_memory.externalMemoryFeatures;
    if ((external_memory.compatibleHandleTypes &
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT) == 0 ||
        (features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0 ||
        (features & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
        *reason = "external-image-not-import-export-compatible";
        return -14;
    }

    VkExternalMemoryImageCreateInfo external_image {};
    external_image.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external_image.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    VkImageCreateInfo image_info {};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.pNext = &external_image;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = kFormat;
    image_info.extent = {width, height, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = kImageUsage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    result = vkCreateImage(out->device, &image_info, nullptr, &out->image);
    if (result != VK_SUCCESS) {
        *reason = "image-create-failed:" + std::to_string(result);
        return -15;
    }

    VkMemoryRequirements requirements {};
    vkGetImageMemoryRequirements(out->device, out->image, &requirements);
    if (requirements.size == 0 || requirements.memoryTypeBits == 0) {
        *reason = "image-memory-requirements-invalid";
        return -16;
    }
    out->memory_type_bits = requirements.memoryTypeBits;

    if (FindMemoryType(
            out->physical,
            requirements.memoryTypeBits,
            &out->memory_type_index) != 0) {
        *reason = "compatible-memory-type-missing";
        return -17;
    }

    VkExportMemoryAllocateInfo export_info {};
    export_info.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    export_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    VkMemoryDedicatedAllocateInfo dedicated_info {};
    dedicated_info.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated_info.pNext = &export_info;
    dedicated_info.image = out->image;
    dedicated_info.buffer = VK_NULL_HANDLE;

    VkMemoryAllocateInfo allocation_info {};
    allocation_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation_info.pNext = &dedicated_info;
    allocation_info.allocationSize = requirements.size;
    allocation_info.memoryTypeIndex = out->memory_type_index;
    result = vkAllocateMemory(out->device, &allocation_info, nullptr, &out->memory);
    if (result != VK_SUCCESS) {
        *reason = "memory-allocate-failed:" + std::to_string(result);
        return -18;
    }
    out->allocation_size = requirements.size;

    result = vkBindImageMemory(out->device, out->image, out->memory, 0);
    if (result != VK_SUCCESS) {
        *reason = "image-bind-failed:" + std::to_string(result);
        return -19;
    }

    VkExportSemaphoreCreateInfo export_semaphore {};
    export_semaphore.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    export_semaphore.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

    VkSemaphoreTypeCreateInfo timeline_info {};
    timeline_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    timeline_info.pNext = &export_semaphore;
    timeline_info.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    timeline_info.initialValue = 0u;

    VkSemaphoreCreateInfo semaphore_info {};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphore_info.pNext = &timeline_info;
    result = vkCreateSemaphore(out->device, &semaphore_info, nullptr, &out->timeline);
    if (result != VK_SUCCESS) {
        *reason = "timeline-semaphore-create-failed:" + std::to_string(result);
        return -20;
    }

    out->width = width;
    out->height = height;
    return 0;
}

std::string LeaseRecord(
        const char* status,
        uint64_t resource_id,
        const Resource* resource,
        const std::string& reason = {}) {
    std::ostringstream out;
    out << "vulkan-external-image-fd=" << status
        << ";protocol=" << kProtocol
        << ";resource_id=" << resource_id;
    if (resource != nullptr && resource->generation != 0) {
        out << ";generation=" << resource->generation
            << ";width=" << resource->width
            << ";height=" << resource->height
            << ";format=" << static_cast<int>(kFormat)
            << ";allocation_size=" << resource->allocation_size
            << ";memory_type_bits=" << resource->memory_type_bits
            << ";memory_type_index=" << resource->memory_type_index;
    }
    if (!reason.empty()) out << ";reason=" << reason;
    return out.str();
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeCreate(
        JNIEnv* env,
        jobject /* thiz */,
        jint width,
        jint height) {
    if (width <= 0 || height <= 0 ||
        static_cast<uint32_t>(width) > kMaxDimension ||
        static_cast<uint32_t>(height) > kMaxDimension) {
        return ToJString(
            env,
            "vulkan-external-image-fd=invalid-dimensions;protocol=1;resource_id=0");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_resources.size() >= kMaxResources) {
        return ToJString(
            env,
            "vulkan-external-image-fd=resource-limit;protocol=1;resource_id=0");
    }

    Resource resource;
    resource.generation = g_next_generation.fetch_add(1);
    if (resource.generation == 0) {
        resource.generation = g_next_generation.fetch_add(1);
    }

    std::string reason;
    const int create_result = CreateResource(
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        &resource,
        &reason);
    if (create_result != 0) {
        const std::string record = LeaseRecord("create-failed", 0, &resource, reason);
        Destroy(&resource);
        return ToJString(env, record);
    }

    uint64_t resource_id = g_next_id.fetch_add(1);
    if (resource_id == 0) resource_id = g_next_id.fetch_add(1);
    if (resource_id == 0 || g_resources.find(resource_id) != g_resources.end()) {
        Destroy(&resource);
        return ToJString(
            env,
            "vulkan-external-image-fd=id-collision;protocol=1;resource_id=0");
    }

    const auto inserted = g_resources.emplace(resource_id, resource);
    if (!inserted.second) {
        Destroy(&resource);
        return ToJString(
            env,
            "vulkan-external-image-fd=id-collision;protocol=1;resource_id=0");
    }

    return ToJString(env, LeaseRecord("ok", resource_id, &inserted.first->second));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation,
        jint socket_fd,
        jlong sequence) {
    if (resource_id <= 0 || generation <= 0 || sequence <= 0 || socket_fd < 0) {
        return ToJString(
            env,
            "vulkan-external-image-fd-send=invalid-argument;protocol=1");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const auto found = g_resources.find(static_cast<uint64_t>(resource_id));
    if (found == g_resources.end() ||
        found->second.generation != static_cast<uint64_t>(generation)) {
        return ToJString(
            env,
            "vulkan-external-image-fd-send=stale-or-unknown-resource;protocol=1");
    }

    Resource& resource = found->second;
    VkMemoryGetFdInfoKHR get_fd_info {};
    get_fd_info.sType = VK_STRUCTURE_TYPE_MEMORY_GET_FD_INFO_KHR;
    get_fd_info.memory = resource.memory;
    get_fd_info.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    int exported_fd = -1;
    const VkResult fd_result = resource.get_memory_fd(
        resource.device, &get_fd_info, &exported_fd);
    if (fd_result != VK_SUCCESS || exported_fd < 0) {
        std::ostringstream out;
        out << "vulkan-external-image-fd-send=export-failed;protocol=" << kProtocol
            << ";vk_result=" << static_cast<int>(fd_result);
        return ToJString(env, out.str());
    }

    const int send_result = SendExternalImageFd(
        socket_fd,
        exported_fd,
        static_cast<uint64_t>(resource_id),
        resource,
        static_cast<uint64_t>(sequence));
    close(exported_fd);

    std::ostringstream out;
    out << "vulkan-external-image-fd-send=" << (send_result == 0 ? "ok" : "failed")
        << ";protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation
        << ";sequence=" << sequence
        << ";send_result=" << send_result;
    return ToJString(env, out.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSendTimeline(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation,
        jint socket_fd) {
    if (resource_id <= 0 || generation <= 0 || socket_fd < 0) {
        return ToJString(
            env,
            "vulkan-external-timeline-fd-send=invalid-argument;protocol=1");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const auto found = g_resources.find(static_cast<uint64_t>(resource_id));
    if (found == g_resources.end() ||
        found->second.generation != static_cast<uint64_t>(generation)) {
        return ToJString(
            env,
            "vulkan-external-timeline-fd-send=stale-or-unknown-resource;protocol=1");
    }

    Resource& resource = found->second;
    if (resource.timeline == VK_NULL_HANDLE || resource.get_semaphore_fd == nullptr) {
        return ToJString(
            env,
            "vulkan-external-timeline-fd-send=timeline-not-ready;protocol=1");
    }

    VkSemaphoreGetFdInfoKHR get_fd_info {};
    get_fd_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    get_fd_info.semaphore = resource.timeline;
    get_fd_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;

    int exported_fd = -1;
    const VkResult fd_result = resource.get_semaphore_fd(
        resource.device, &get_fd_info, &exported_fd);
    if (fd_result != VK_SUCCESS || exported_fd < 0) {
        std::ostringstream out;
        out << "vulkan-external-timeline-fd-send=export-failed;protocol=" << kProtocol
            << ";vk_result=" << static_cast<int>(fd_result);
        return ToJString(env, out.str());
    }

    const int send_result = SendExternalTimelineFd(
        socket_fd,
        exported_fd,
        static_cast<uint64_t>(resource_id),
        resource);
    close(exported_fd);

    std::ostringstream out;
    out << "vulkan-external-timeline-fd-send=" << (send_result == 0 ? "ok" : "failed")
        << ";protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation
        << ";initial_value=0"
        << ";role=" << kTimelineRoleFrameOwnership
        << ";send_result=" << send_result;
    return ToJString(env, out.str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeRelease(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation) {
    if (resource_id <= 0 || generation <= 0) {
        return ToJString(
            env,
            "vulkan-external-image-fd-release=invalid-argument;protocol=1");
    }

    Resource resource;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_resources.find(static_cast<uint64_t>(resource_id));
        if (found == g_resources.end() ||
            found->second.generation != static_cast<uint64_t>(generation)) {
            return ToJString(
                env,
                "vulkan-external-image-fd-release=stale-or-unknown-resource;protocol=1");
        }
        resource = found->second;
        g_resources.erase(found);
    }
    Destroy(&resource);

    std::ostringstream out;
    out << "vulkan-external-image-fd-release=ok;protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation;
    return ToJString(env, out.str());
}
