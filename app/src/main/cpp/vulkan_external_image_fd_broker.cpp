#include <jni.h>
#include <vulkan/vulkan.h>

#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kFdTokenMagic = 0x31444650u;  // PFD1 little-endian.
constexpr uint16_t kFdTokenVersion = 1;
constexpr size_t kFdTokenBytes = 32;
constexpr uint32_t kMaxDimension = 4096;
constexpr size_t kMaxResources = 16;
constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_UNORM;

struct Resource {
    uint64_t generation = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize allocation_size = 0;
    uint32_t memory_type_index = 0;
    PFN_vkGetMemoryFdKHR get_memory_fd = nullptr;
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

void EncodeToken(
        uint8_t out[kFdTokenBytes],
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence) {
    std::memset(out, 0, kFdTokenBytes);
    PutU32Le(out + 0, kFdTokenMagic);
    PutU16Le(out + 4, kFdTokenVersion);
    PutU16Le(out + 6, 0);
    PutU64Le(out + 8, resource_id);
    PutU64Le(out + 16, generation);
    PutU64Le(out + 24, sequence);
}

bool IsSeqpacket(int socket_fd) {
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);
    return socket_fd >= 0 &&
        getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) == 0 &&
        socket_type == SOCK_SEQPACKET;
}

int SendFd(
        int socket_fd,
        int fd,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence) {
    if (!IsSeqpacket(socket_fd) || fd < 0 || resource_id == 0 ||
        generation == 0 || sequence == 0) {
        return -1;
    }

    uint8_t payload[kFdTokenBytes];
    EncodeToken(payload, resource_id, generation, sequence);

    struct iovec iov {};
    iov.iov_base = payload;
    iov.iov_len = sizeof(payload);

    char control[CMSG_SPACE(sizeof(int))];
    std::memset(control, 0, sizeof(control));

    struct msghdr message {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    struct cmsghdr* header = CMSG_FIRSTHDR(&message);
    if (header == nullptr) {
        return -2;
    }
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    std::memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    message.msg_controllen = CMSG_SPACE(sizeof(int));

    ssize_t written;
    do {
        written = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (written < 0 && errno == EINTR);

    return written == static_cast<ssize_t>(sizeof(payload)) ? 0 : -3;
}

void Destroy(Resource* resource) {
    if (resource == nullptr) return;
    if (resource->device != VK_NULL_HANDLE) {
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
    if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS) {
        return false;
    }
    std::vector<VkExtensionProperties> extensions(count);
    if (count != 0 &&
        vkEnumerateDeviceExtensionProperties(
            physical, nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }
    for (uint32_t i = 0; i < count; ++i) {
        if (std::strcmp(extensions[i].extensionName, name) == 0) return true;
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
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            out->physical = candidate;
            break;
        }
    }

    if (!HasDeviceExtension(out->physical, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)) {
        *reason = "external-memory-fd-extension-missing";
        return -5;
    }

    uint32_t queue_family = 0;
    if (FindGraphicsQueue(out->physical, &queue_family) != 0) {
        *reason = "graphics-queue-missing";
        return -6;
    }

    const float queue_priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &queue_priority;

    const char* device_extensions[] = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
    };
    VkDeviceCreateInfo device_info {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = device_extensions;
    result = vkCreateDevice(out->physical, &device_info, nullptr, &out->device);
    if (result != VK_SUCCESS) {
        *reason = "device-create-failed:" + std::to_string(result);
        return -7;
    }

    out->get_memory_fd = reinterpret_cast<PFN_vkGetMemoryFdKHR>(
        vkGetDeviceProcAddr(out->device, "vkGetMemoryFdKHR"));
    if (out->get_memory_fd == nullptr) {
        *reason = "vkGetMemoryFdKHR-missing";
        return -8;
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
    format_info.usage =
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
        VK_IMAGE_USAGE_TRANSFER_DST_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
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
        return -9;
    }
    const VkExternalMemoryFeatureFlags features =
        external_properties.externalMemoryProperties.externalMemoryFeatures;
    if ((features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0 ||
        (features & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0) {
        *reason = "external-image-not-import-export-compatible";
        return -10;
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
    image_info.usage = format_info.usage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    result = vkCreateImage(out->device, &image_info, nullptr, &out->image);
    if (result != VK_SUCCESS) {
        *reason = "image-create-failed:" + std::to_string(result);
        return -11;
    }

    VkMemoryRequirements requirements {};
    vkGetImageMemoryRequirements(out->device, out->image, &requirements);
    if (requirements.size == 0 || requirements.memoryTypeBits == 0) {
        *reason = "image-memory-requirements-invalid";
        return -12;
    }

    if (FindMemoryType(
            out->physical,
            requirements.memoryTypeBits,
            &out->memory_type_index) != 0) {
        *reason = "compatible-memory-type-missing";
        return -13;
    }

    VkExportMemoryAllocateInfo export_info {};
    export_info.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    export_info.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;

    VkMemoryAllocateInfo allocation_info {};
    allocation_info.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation_info.pNext = &export_info;
    allocation_info.allocationSize = requirements.size;
    allocation_info.memoryTypeIndex = out->memory_type_index;
    result = vkAllocateMemory(out->device, &allocation_info, nullptr, &out->memory);
    if (result != VK_SUCCESS) {
        *reason = "memory-allocate-failed:" + std::to_string(result);
        return -14;
    }
    out->allocation_size = requirements.size;

    result = vkBindImageMemory(out->device, out->image, out->memory, 0);
    if (result != VK_SUCCESS) {
        *reason = "image-bind-failed:" + std::to_string(result);
        return -15;
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
    if (resource != nullptr) {
        out << ";generation=" << resource->generation
            << ";width=" << resource->width
            << ";height=" << resource->height
            << ";format=" << static_cast<int>(kFormat)
            << ";allocation_size=" << resource->allocation_size
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
        return ToJString(env, "vulkan-external-image-fd=invalid-dimensions;protocol=1;resource_id=0");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_resources.size() >= kMaxResources) {
        return ToJString(env, "vulkan-external-image-fd=resource-limit;protocol=1;resource_id=0");
    }

    Resource resource;
    resource.generation = g_next_generation.fetch_add(1);
    if (resource.generation == 0) resource.generation = g_next_generation.fetch_add(1);

    std::string reason;
    const int create_result = CreateResource(
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        &resource,
        &reason);
    if (create_result != 0) {
        Destroy(&resource);
        return ToJString(env, LeaseRecord("create-failed", 0, &resource, reason));
    }

    uint64_t resource_id = g_next_id.fetch_add(1);
    if (resource_id == 0) resource_id = g_next_id.fetch_add(1);
    auto inserted = g_resources.emplace(resource_id, std::move(resource));
    if (!inserted.second) {
        Resource cleanup = std::move(inserted.first->second);
        Destroy(&cleanup);
        return ToJString(env, "vulkan-external-image-fd=id-collision;protocol=1;resource_id=0");
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
        return ToJString(env, "vulkan-external-image-fd-send=invalid-argument;protocol=1");
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const auto found = g_resources.find(static_cast<uint64_t>(resource_id));
    if (found == g_resources.end() ||
        found->second.generation != static_cast<uint64_t>(generation)) {
        return ToJString(env, "vulkan-external-image-fd-send=stale-or-unknown-resource;protocol=1");
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

    const int send_result = SendFd(
        socket_fd,
        exported_fd,
        static_cast<uint64_t>(resource_id),
        static_cast<uint64_t>(generation),
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
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeRelease(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation) {
    if (resource_id <= 0 || generation <= 0) {
        return ToJString(env, "vulkan-external-image-fd-release=invalid-argument;protocol=1");
    }

    Resource resource;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        const auto found = g_resources.find(static_cast<uint64_t>(resource_id));
        if (found == g_resources.end() ||
            found->second.generation != static_cast<uint64_t>(generation)) {
            return ToJString(env, "vulkan-external-image-fd-release=stale-or-unknown-resource;protocol=1");
        }
        resource = std::move(found->second);
        g_resources.erase(found);
    }
    Destroy(&resource);

    std::ostringstream out;
    out << "vulkan-external-image-fd-release=ok;protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation;
    return ToJString(env, out.str());
}
