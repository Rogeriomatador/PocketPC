#include <jni.h>
#include <vulkan/vulkan.h>

#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <array>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
    JNIEnv*, jobject, jlong, jlong, jint, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSendTimeline(
    JNIEnv*, jobject, jlong, jlong, jint);

namespace {

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kPviMagic = 0x31495650u;  // PVI1 little-endian.
constexpr uint16_t kPviVersion = 1;
constexpr size_t kPviBytes = 64;
constexpr uint32_t kPvsMagic = 0x31535650u;  // PVS1 little-endian.
constexpr uint16_t kPvsVersion = 1;
constexpr size_t kPvsBytes = 40;
constexpr uint32_t kTimelineRoleFrameOwnership = 1;
constexpr VkImageLayout kExternalBoundaryLayout = VK_IMAGE_LAYOUT_GENERAL;
constexpr VkImageUsageFlags kExpectedUsage =
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
constexpr uint64_t kFirstGuestSignalValue = 1u;
constexpr uint64_t kFnvOffset = 14695981039346656037ull;
constexpr uint64_t kFnvPrime = 1099511628211ull;
constexpr uint32_t kMaxDimension = 4096;
constexpr uint64_t kMaxReadbackBytes = 4096ull * 4096ull * 4ull;
constexpr uint64_t kNanosPerSecond = 1000000000ull;
constexpr uint64_t kNanosPerMicrosecond = 1000ull;

struct ReceivedFd {
    int fd = -1;
    std::vector<uint8_t> payload;
};

struct VulkanConsumer {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queue_family = UINT32_MAX;
    VkCommandPool command_pool = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory image_memory = VK_NULL_HANDLE;
    VkSemaphore timeline = VK_NULL_HANDLE;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory staging_memory = VK_NULL_HANDLE;
    VkDeviceSize staging_size = 0;
    bool staging_coherent = false;
    PFN_vkImportSemaphoreFdKHR import_semaphore_fd = nullptr;
    PFN_vkWaitSemaphores wait_semaphores = nullptr;
    PFN_vkGetSemaphoreCounterValue get_semaphore_counter = nullptr;
};

jstring ToJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

std::string FromJString(JNIEnv* env, jstring value) {
    if (!env || !value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool StartsWith(const std::string& value, const char* prefix) {
    if (!prefix) return false;
    const size_t prefix_size = std::strlen(prefix);
    return value.size() >= prefix_size &&
        value.compare(0, prefix_size, prefix) == 0;
}

uint16_t GetU16Le(const uint8_t* p) {
    return static_cast<uint16_t>(p[0]) |
        (static_cast<uint16_t>(p[1]) << 8u);
}

uint32_t GetU32Le(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) |
        (static_cast<uint32_t>(p[1]) << 8u) |
        (static_cast<uint32_t>(p[2]) << 16u) |
        (static_cast<uint32_t>(p[3]) << 24u);
}

uint64_t GetU64Le(const uint8_t* p) {
    uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i)
        value |= static_cast<uint64_t>(p[i]) << (i * 8u);
    return value;
}

void CloseReceived(ReceivedFd* received) {
    if (!received) return;
    if (received->fd >= 0) close(received->fd);
    received->fd = -1;
    received->payload.clear();
}

bool SetReceiveTimeout(int socket_fd, uint64_t timeout_nanos) {
    if (socket_fd < 0 || timeout_nanos == 0) return false;

    timeval timeout {};
    timeout.tv_sec = static_cast<time_t>(timeout_nanos / kNanosPerSecond);
    const uint64_t remainder = timeout_nanos % kNanosPerSecond;
    uint64_t micros =
        (remainder + kNanosPerMicrosecond - 1u) / kNanosPerMicrosecond;
    if (micros >= 1000000u) {
        ++timeout.tv_sec;
        micros -= 1000000u;
    }
    timeout.tv_usec = static_cast<suseconds_t>(micros);
    if (timeout.tv_sec == 0 && timeout.tv_usec == 0) timeout.tv_usec = 1;

    return setsockopt(
        socket_fd,
        SOL_SOCKET,
        SO_RCVTIMEO,
        &timeout,
        sizeof(timeout)) == 0;
}

bool ReceiveSingleFd(int socket_fd, size_t payload_bytes, ReceivedFd* out) {
    if (socket_fd < 0 || payload_bytes == 0 || !out) return false;
    CloseReceived(out);
    out->payload.assign(payload_bytes, 0u);

    iovec iov {};
    iov.iov_base = out->payload.data();
    iov.iov_len = out->payload.size();

    std::array<uint8_t, CMSG_SPACE(sizeof(int) * 4)> control {};
    msghdr message {};
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control.data();
    message.msg_controllen = control.size();

    ssize_t received;
    do {
        received = recvmsg(socket_fd, &message, 0);
    } while (received < 0 && errno == EINTR);

    if (received != static_cast<ssize_t>(payload_bytes) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) {
        return false;
    }

    int accepted_fd = -1;
    size_t fd_count = 0;
    for (cmsghdr* cmsg = CMSG_FIRSTHDR(&message); cmsg != nullptr;
         cmsg = CMSG_NXTHDR(&message, cmsg)) {
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS)
            continue;
        if (cmsg->cmsg_len < CMSG_LEN(sizeof(int))) continue;
        const size_t bytes = cmsg->cmsg_len - CMSG_LEN(0);
        const size_t count = bytes / sizeof(int);
        const int* fds = reinterpret_cast<const int*>(CMSG_DATA(cmsg));
        for (size_t i = 0; i < count; ++i) {
            ++fd_count;
            if (fd_count == 1) accepted_fd = fds[i];
            else if (fds[i] >= 0) close(fds[i]);
        }
    }

    if (fd_count != 1 || accepted_fd < 0) {
        if (accepted_fd >= 0) close(accepted_fd);
        return false;
    }
    out->fd = accepted_fd;
    return true;
}

bool HasDeviceExtension(VkPhysicalDevice physical, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS ||
        count > 4096) return false;
    std::vector<VkExtensionProperties> properties(count);
    if (count != 0) {
        uint32_t read_count = count;
        if (vkEnumerateDeviceExtensionProperties(
                physical, nullptr, &read_count, properties.data()) != VK_SUCCESS) return false;
        properties.resize(read_count);
    }
    for (const auto& property : properties) {
        if (std::strcmp(property.extensionName, name) == 0) return true;
    }
    return false;
}

bool PickPhysicalAndQueue(
        VkInstance instance,
        VkPhysicalDevice* physical,
        uint32_t* queue_family) {
    if (!physical || !queue_family) return false;

    uint32_t physical_count = 0;
    if (vkEnumeratePhysicalDevices(instance, &physical_count, nullptr) != VK_SUCCESS ||
        physical_count == 0 || physical_count > 64) return false;
    std::vector<VkPhysicalDevice> physicals(physical_count);
    if (vkEnumeratePhysicalDevices(instance, &physical_count, physicals.data()) != VK_SUCCESS)
        return false;

    VkPhysicalDevice selected = physicals.front();
    for (VkPhysicalDevice candidate : physicals) {
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            selected = candidate;
            break;
        }
    }

    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(selected, &count, nullptr);
    if (count == 0 || count > 256) return false;
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(selected, &count, queues.data());
    for (uint32_t i = 0; i < count; ++i) {
        if (queues[i].queueCount > 0 && (queues[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
            *physical = selected;
            *queue_family = i;
            return true;
        }
    }
    return false;
}

bool FindHostVisibleMemoryType(
        VkPhysicalDevice physical,
        uint32_t type_bits,
        uint32_t* index,
        bool* coherent) {
    if (!index || !coherent || type_bits == 0) return false;

    VkPhysicalDeviceMemoryProperties properties {};
    vkGetPhysicalDeviceMemoryProperties(physical, &properties);
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        const VkMemoryPropertyFlags flags = properties.memoryTypes[i].propertyFlags;
        if ((type_bits & (1u << i)) != 0 &&
            (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0 &&
            (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) {
            *index = i;
            *coherent = true;
            return true;
        }
    }
    for (uint32_t i = 0; i < properties.memoryTypeCount; ++i) {
        const VkMemoryPropertyFlags flags = properties.memoryTypes[i].propertyFlags;
        if ((type_bits & (1u << i)) != 0 &&
            (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
            *index = i;
            *coherent = false;
            return true;
        }
    }
    return false;
}

void DestroyConsumer(VulkanConsumer* consumer) {
    if (!consumer) return;
    if (consumer->device != VK_NULL_HANDLE) {
        if (consumer->staging != VK_NULL_HANDLE)
            vkDestroyBuffer(consumer->device, consumer->staging, nullptr);
        if (consumer->staging_memory != VK_NULL_HANDLE)
            vkFreeMemory(consumer->device, consumer->staging_memory, nullptr);
        if (consumer->timeline != VK_NULL_HANDLE)
            vkDestroySemaphore(consumer->device, consumer->timeline, nullptr);
        if (consumer->image != VK_NULL_HANDLE)
            vkDestroyImage(consumer->device, consumer->image, nullptr);
        if (consumer->image_memory != VK_NULL_HANDLE)
            vkFreeMemory(consumer->device, consumer->image_memory, nullptr);
        if (consumer->command_pool != VK_NULL_HANDLE)
            vkDestroyCommandPool(consumer->device, consumer->command_pool, nullptr);
        vkDestroyDevice(consumer->device, nullptr);
    }
    if (consumer->instance != VK_NULL_HANDLE)
        vkDestroyInstance(consumer->instance, nullptr);
    *consumer = VulkanConsumer{};
}

std::string Fail(
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence,
        const char* reason,
        int code = 0) {
    std::ostringstream out;
    out << "vulkan-external-image-host-consume=failed"
        << ";protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation
        << ";sequence=" << sequence
        << ";reason=" << reason
        << ";code=" << code
        << ";acquired=0;returned_external=0;visible_frame=0";
    return out.str();
}

bool ValidatePvi(
        const ReceivedFd& packet,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t sequence,
        uint32_t width,
        uint32_t height,
        VkFormat format,
        uint64_t allocation_size,
        uint32_t memory_type_bits,
        uint32_t memory_type_index) {
    if (packet.fd < 0 || packet.payload.size() != kPviBytes) return false;
    const uint8_t* p = packet.payload.data();
    return GetU32Le(p + 0) == kPviMagic &&
        GetU16Le(p + 4) == kPviVersion &&
        GetU16Le(p + 6) == 0u &&
        GetU64Le(p + 8) == resource_id &&
        GetU64Le(p + 16) == generation &&
        GetU64Le(p + 24) == sequence &&
        GetU32Le(p + 32) == width &&
        GetU32Le(p + 36) == height &&
        GetU32Le(p + 40) == static_cast<uint32_t>(format) &&
        GetU32Le(p + 44) == static_cast<uint32_t>(kExpectedUsage) &&
        GetU64Le(p + 48) == allocation_size &&
        GetU32Le(p + 56) == memory_type_bits &&
        GetU32Le(p + 60) == memory_type_index;
}

bool ValidatePvs(
        const ReceivedFd& packet,
        uint64_t resource_id,
        uint64_t generation) {
    if (packet.fd < 0 || packet.payload.size() != kPvsBytes) return false;
    const uint8_t* p = packet.payload.data();
    return GetU32Le(p + 0) == kPvsMagic &&
        GetU16Le(p + 4) == kPvsVersion &&
        GetU16Le(p + 6) == 0u &&
        GetU64Le(p + 8) == resource_id &&
        GetU64Le(p + 16) == generation &&
        GetU64Le(p + 24) == 0u &&
        GetU32Le(p + 32) == kTimelineRoleFrameOwnership &&
        GetU32Le(p + 36) == 0u;
}

int CreateImportedConsumer(
        VulkanConsumer* consumer,
        int* image_fd,
        int* timeline_fd,
        uint32_t width,
        uint32_t height,
        VkFormat format,
        uint64_t allocation_size,
        uint32_t memory_type_index,
        std::string* reason) {
    if (!consumer || !image_fd || !timeline_fd || !reason || *image_fd < 0 || *timeline_fd < 0)
        return -1;

    VkApplicationInfo app {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketPC External Image Host Consumer";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "PocketPC";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instance_info {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    VkResult result = vkCreateInstance(&instance_info, nullptr, &consumer->instance);
    if (result != VK_SUCCESS) { *reason = "instance-create"; return result; }

    if (!PickPhysicalAndQueue(consumer->instance, &consumer->physical, &consumer->queue_family)) {
        *reason = "physical-or-queue";
        return -2;
    }
    if (!HasDeviceExtension(consumer->physical, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME) ||
        !HasDeviceExtension(consumer->physical, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
        *reason = "external-fd-extension";
        return -3;
    }

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(consumer->physical, &properties);
    const bool core_12 =
        VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
        (VK_VERSION_MAJOR(properties.apiVersion) == 1 && VK_VERSION_MINOR(properties.apiVersion) >= 2);
    if (!core_12 && !HasDeviceExtension(
            consumer->physical, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
        *reason = "timeline-extension";
        return -4;
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_features {};
    timeline_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    VkPhysicalDeviceFeatures2 features {};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &timeline_features;
    vkGetPhysicalDeviceFeatures2(consumer->physical, &features);
    if (timeline_features.timelineSemaphore != VK_TRUE) {
        *reason = "timeline-feature";
        return -5;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = consumer->queue_family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;

    std::vector<const char*> extensions = {
        VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME,
        VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME,
    };
    if (!core_12) extensions.push_back(VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);

    VkPhysicalDeviceTimelineSemaphoreFeatures enable_timeline {};
    enable_timeline.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    enable_timeline.timelineSemaphore = VK_TRUE;
    VkDeviceCreateInfo device_info {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.pNext = &enable_timeline;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    device_info.ppEnabledExtensionNames = extensions.data();
    result = vkCreateDevice(consumer->physical, &device_info, nullptr, &consumer->device);
    if (result != VK_SUCCESS) { *reason = "device-create"; return result; }

    vkGetDeviceQueue(consumer->device, consumer->queue_family, 0, &consumer->queue);
    if (consumer->queue == VK_NULL_HANDLE) { *reason = "queue-get"; return -6; }

    consumer->import_semaphore_fd = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
        vkGetDeviceProcAddr(consumer->device, "vkImportSemaphoreFdKHR"));
    consumer->wait_semaphores = reinterpret_cast<PFN_vkWaitSemaphores>(
        vkGetDeviceProcAddr(consumer->device, "vkWaitSemaphores"));
    if (!consumer->wait_semaphores) {
        consumer->wait_semaphores = reinterpret_cast<PFN_vkWaitSemaphores>(
            vkGetDeviceProcAddr(consumer->device, "vkWaitSemaphoresKHR"));
    }
    consumer->get_semaphore_counter = reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
        vkGetDeviceProcAddr(consumer->device, "vkGetSemaphoreCounterValue"));
    if (!consumer->get_semaphore_counter) {
        consumer->get_semaphore_counter = reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
            vkGetDeviceProcAddr(consumer->device, "vkGetSemaphoreCounterValueKHR"));
    }
    if (!consumer->import_semaphore_fd || !consumer->wait_semaphores ||
        !consumer->get_semaphore_counter) {
        *reason = "timeline-functions";
        return -7;
    }

    VkExternalMemoryImageCreateInfo external_image {};
    external_image.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    external_image.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    VkImageCreateInfo image_info {};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.pNext = &external_image;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = format;
    image_info.extent = {width, height, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = kExpectedUsage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    result = vkCreateImage(consumer->device, &image_info, nullptr, &consumer->image);
    if (result != VK_SUCCESS) { *reason = "image-create"; return result; }

    VkMemoryRequirements image_requirements {};
    vkGetImageMemoryRequirements(consumer->device, consumer->image, &image_requirements);
    if (image_requirements.size == 0 || image_requirements.memoryTypeBits == 0 ||
        memory_type_index >= 32 ||
        (image_requirements.memoryTypeBits & (1u << memory_type_index)) == 0 ||
        allocation_size < image_requirements.size) {
        *reason = "import-memory-requirements";
        return -8;
    }

    VkMemoryDedicatedAllocateInfo dedicated {};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = consumer->image;
    VkImportMemoryFdInfoKHR import_memory {};
    import_memory.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
    import_memory.pNext = &dedicated;
    import_memory.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_memory.fd = *image_fd;
    VkMemoryAllocateInfo allocation {};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &import_memory;
    allocation.allocationSize = allocation_size;
    allocation.memoryTypeIndex = memory_type_index;
    result = vkAllocateMemory(consumer->device, &allocation, nullptr, &consumer->image_memory);
    if (result != VK_SUCCESS) { *reason = "image-memory-import"; return result; }
    *image_fd = -1;

    result = vkBindImageMemory(consumer->device, consumer->image, consumer->image_memory, 0);
    if (result != VK_SUCCESS) { *reason = "image-bind"; return result; }

    VkSemaphoreTypeCreateInfo type_info {};
    type_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    type_info.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    type_info.initialValue = 0;
    VkSemaphoreCreateInfo semaphore_info {};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphore_info.pNext = &type_info;
    result = vkCreateSemaphore(consumer->device, &semaphore_info, nullptr, &consumer->timeline);
    if (result != VK_SUCCESS) { *reason = "timeline-create"; return result; }

    VkImportSemaphoreFdInfoKHR import_semaphore {};
    import_semaphore.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    import_semaphore.semaphore = consumer->timeline;
    import_semaphore.flags = 0;
    import_semaphore.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_semaphore.fd = *timeline_fd;
    result = consumer->import_semaphore_fd(consumer->device, &import_semaphore);
    if (result != VK_SUCCESS) { *reason = "timeline-import"; return result; }
    *timeline_fd = -1;

    VkCommandPoolCreateInfo pool_info {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    pool_info.queueFamilyIndex = consumer->queue_family;
    result = vkCreateCommandPool(consumer->device, &pool_info, nullptr, &consumer->command_pool);
    if (result != VK_SUCCESS) { *reason = "command-pool"; return result; }

    const uint64_t bytes = static_cast<uint64_t>(width) *
        static_cast<uint64_t>(height) * 4ull;
    if (bytes == 0 || bytes > kMaxReadbackBytes ||
        bytes > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        *reason = "readback-size";
        return -9;
    }
    consumer->staging_size = static_cast<VkDeviceSize>(bytes);

    VkBufferCreateInfo buffer_info {};
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.size = consumer->staging_size;
    buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    result = vkCreateBuffer(consumer->device, &buffer_info, nullptr, &consumer->staging);
    if (result != VK_SUCCESS) { *reason = "staging-buffer"; return result; }

    VkMemoryRequirements staging_requirements {};
    vkGetBufferMemoryRequirements(consumer->device, consumer->staging, &staging_requirements);
    uint32_t staging_memory_index = 0;
    if (!FindHostVisibleMemoryType(
            consumer->physical,
            staging_requirements.memoryTypeBits,
            &staging_memory_index,
            &consumer->staging_coherent)) {
        *reason = "host-visible-memory";
        return -10;
    }

    VkMemoryAllocateInfo staging_allocation {};
    staging_allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    staging_allocation.allocationSize = staging_requirements.size;
    staging_allocation.memoryTypeIndex = staging_memory_index;
    result = vkAllocateMemory(
        consumer->device, &staging_allocation, nullptr, &consumer->staging_memory);
    if (result != VK_SUCCESS) { *reason = "staging-memory"; return result; }

    result = vkBindBufferMemory(consumer->device, consumer->staging, consumer->staging_memory, 0);
    if (result != VK_SUCCESS) { *reason = "staging-bind"; return result; }

    return 0;
}

int WaitGuestSignal(
        VulkanConsumer* consumer,
        uint64_t timeout_ns,
        uint64_t* timeline_value,
        std::string* reason) {
    if (!consumer || !timeline_value || !reason || timeout_ns == 0) return -1;

    VkSemaphore semaphore = consumer->timeline;
    const uint64_t target = kFirstGuestSignalValue;
    VkSemaphoreWaitInfo wait_info {};
    wait_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    wait_info.semaphoreCount = 1;
    wait_info.pSemaphores = &semaphore;
    wait_info.pValues = &target;

    VkResult result = consumer->wait_semaphores(consumer->device, &wait_info, timeout_ns);
    if (result != VK_SUCCESS) { *reason = "timeline-wait"; return result; }

    result = consumer->get_semaphore_counter(
        consumer->device, consumer->timeline, timeline_value);
    if (result != VK_SUCCESS || *timeline_value < target) {
        *reason = "timeline-counter";
        return result == VK_SUCCESS ? -2 : result;
    }
    return 0;
}

int ReadbackAndReturnExternal(
        VulkanConsumer* consumer,
        uint32_t width,
        uint32_t height,
        uint64_t* checksum,
        uint64_t* nonzero_bytes,
        std::string* reason) {
    if (!consumer || !checksum || !nonzero_bytes || !reason) return -1;

    VkCommandBufferAllocateInfo allocate_info {};
    allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate_info.commandPool = consumer->command_pool;
    allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate_info.commandBufferCount = 1;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkResult result = vkAllocateCommandBuffers(
        consumer->device, &allocate_info, &command);
    if (result != VK_SUCCESS) { *reason = "command-allocate"; return result; }

    VkCommandBufferBeginInfo begin_info {};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = vkBeginCommandBuffer(command, &begin_info);
    if (result != VK_SUCCESS) { *reason = "command-begin"; return result; }

    VkImageMemoryBarrier acquire {};
    acquire.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    acquire.srcAccessMask = 0;
    acquire.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    acquire.oldLayout = kExternalBoundaryLayout;
    acquire.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    acquire.dstQueueFamilyIndex = consumer->queue_family;
    acquire.image = consumer->image;
    acquire.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    acquire.subresourceRange.baseMipLevel = 0;
    acquire.subresourceRange.levelCount = 1;
    acquire.subresourceRange.baseArrayLayer = 0;
    acquire.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(
        command,
        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &acquire);

    VkBufferImageCopy region {};
    region.bufferOffset = 0;
    region.bufferRowLength = 0;
    region.bufferImageHeight = 0;
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.mipLevel = 0;
    region.imageSubresource.baseArrayLayer = 0;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = {width, height, 1};
    vkCmdCopyImageToBuffer(
        command,
        consumer->image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        consumer->staging,
        1,
        &region);

    VkBufferMemoryBarrier buffer_barrier {};
    buffer_barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    buffer_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    buffer_barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    buffer_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    buffer_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    buffer_barrier.buffer = consumer->staging;
    buffer_barrier.offset = 0;
    buffer_barrier.size = consumer->staging_size;
    vkCmdPipelineBarrier(
        command,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0,
        0,
        nullptr,
        1,
        &buffer_barrier,
        0,
        nullptr);

    VkImageMemoryBarrier release {};
    release.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    release.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    release.dstAccessMask = 0;
    release.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    release.newLayout = kExternalBoundaryLayout;
    release.srcQueueFamilyIndex = consumer->queue_family;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    release.image = consumer->image;
    release.subresourceRange = acquire.subresourceRange;
    vkCmdPipelineBarrier(
        command,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0,
        0,
        nullptr,
        0,
        nullptr,
        1,
        &release);

    result = vkEndCommandBuffer(command);
    if (result != VK_SUCCESS) { *reason = "command-end"; return result; }

    VkSubmitInfo submit {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    result = vkQueueSubmit(consumer->queue, 1, &submit, VK_NULL_HANDLE);
    if (result != VK_SUCCESS) { *reason = "queue-submit"; return result; }

    result = vkQueueWaitIdle(consumer->queue);
    if (result != VK_SUCCESS) { *reason = "queue-wait"; return result; }

    void* mapped = nullptr;
    result = vkMapMemory(
        consumer->device,
        consumer->staging_memory,
        0,
        VK_WHOLE_SIZE,
        0,
        &mapped);
    if (result != VK_SUCCESS || !mapped) { *reason = "staging-map"; return result; }

    if (!consumer->staging_coherent) {
        VkMappedMemoryRange range {};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = consumer->staging_memory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        result = vkInvalidateMappedMemoryRanges(consumer->device, 1, &range);
        if (result != VK_SUCCESS) {
            vkUnmapMemory(consumer->device, consumer->staging_memory);
            *reason = "staging-invalidate";
            return result;
        }
    }

    const auto* bytes = static_cast<const uint8_t*>(mapped);
    uint64_t hash = kFnvOffset;
    uint64_t nonzero = 0;
    for (VkDeviceSize i = 0; i < consumer->staging_size; ++i) {
        hash ^= bytes[i];
        hash *= kFnvPrime;
        if (bytes[i] != 0) ++nonzero;
    }
    vkUnmapMemory(consumer->device, consumer->staging_memory);

    *checksum = hash;
    *nonzero_bytes = nonzero;
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageHostConsumer_nativeConsume(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation,
        jlong sequence,
        jint width,
        jint height,
        jint format,
        jlong allocation_size,
        jlong memory_type_bits,
        jint memory_type_index,
        jlong timeout_nanos) {
    const uint64_t rid = resource_id > 0 ? static_cast<uint64_t>(resource_id) : 0u;
    const uint64_t gen = generation > 0 ? static_cast<uint64_t>(generation) : 0u;
    const uint64_t seq = sequence > 0 ? static_cast<uint64_t>(sequence) : 0u;
    if (rid == 0 || gen == 0 || seq == 0 || width <= 0 || height <= 0 ||
        static_cast<uint32_t>(width) > kMaxDimension ||
        static_cast<uint32_t>(height) > kMaxDimension ||
        format != static_cast<jint>(VK_FORMAT_R8G8B8A8_UNORM) ||
        allocation_size <= 0 || memory_type_bits <= 0 ||
        memory_type_index < 0 || memory_type_index >= 32 || timeout_nanos <= 0) {
        return ToJString(env, Fail(rid, gen, seq, "invalid-argument"));
    }

    int sockets[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) != 0)
        return ToJString(env, Fail(rid, gen, seq, "socketpair", errno));

    ReceivedFd image_packet;
    ReceivedFd timeline_packet;
    VulkanConsumer consumer;
    std::string reason;
    std::string response;

    if (!SetReceiveTimeout(sockets[1], static_cast<uint64_t>(timeout_nanos))) {
        response = Fail(rid, gen, seq, "socket-timeout", errno);
        goto done;
    }

    {
        jstring result = Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
            env, nullptr, resource_id, generation, sockets[0], sequence);
        const std::string status = FromJString(env, result);
        if (result) env->DeleteLocalRef(result);
        if (!StartsWith(status, "vulkan-external-image-fd-send=ok;")) {
            response = Fail(rid, gen, seq, "pvi1-reexport");
            goto done;
        }
    }
    if (!ReceiveSingleFd(sockets[1], kPviBytes, &image_packet) ||
        !ValidatePvi(
            image_packet,
            rid,
            gen,
            seq,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<VkFormat>(format),
            static_cast<uint64_t>(allocation_size),
            static_cast<uint32_t>(memory_type_bits),
            static_cast<uint32_t>(memory_type_index))) {
        response = Fail(rid, gen, seq, "pvi1-identity");
        goto done;
    }

    {
        jstring result =
            Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSendTimeline(
                env, nullptr, resource_id, generation, sockets[0]);
        const std::string status = FromJString(env, result);
        if (result) env->DeleteLocalRef(result);
        if (!StartsWith(status, "vulkan-external-timeline-fd-send=ok;")) {
            response = Fail(rid, gen, seq, "pvs1-reexport");
            goto done;
        }
    }
    if (!ReceiveSingleFd(sockets[1], kPvsBytes, &timeline_packet) ||
        !ValidatePvs(timeline_packet, rid, gen)) {
        response = Fail(rid, gen, seq, "pvs1-identity");
        goto done;
    }

    {
        const int create_result = CreateImportedConsumer(
            &consumer,
            &image_packet.fd,
            &timeline_packet.fd,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<VkFormat>(format),
            static_cast<uint64_t>(allocation_size),
            static_cast<uint32_t>(memory_type_index),
            &reason);
        if (create_result != 0) {
            response = Fail(rid, gen, seq, reason.c_str(), create_result);
            goto done;
        }
    }

    {
        uint64_t timeline_value = 0;
        const int wait_result = WaitGuestSignal(
            &consumer,
            static_cast<uint64_t>(timeout_nanos),
            &timeline_value,
            &reason);
        if (wait_result != 0) {
            response = Fail(rid, gen, seq, reason.c_str(), wait_result);
            goto done;
        }

        uint64_t checksum = 0;
        uint64_t nonzero_bytes = 0;
        const int readback_result = ReadbackAndReturnExternal(
            &consumer,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            &checksum,
            &nonzero_bytes,
            &reason);
        if (readback_result != 0) {
            response = Fail(rid, gen, seq, reason.c_str(), readback_result);
            goto done;
        }

        std::ostringstream out;
        out << "vulkan-external-image-host-consume=ok"
            << ";protocol=" << kProtocol
            << ";resource_id=" << rid
            << ";generation=" << gen
            << ";sequence=" << seq
            << ";timeline_value=" << timeline_value
            << ";bytes=" << consumer.staging_size
            << ";nonzero_bytes=" << nonzero_bytes
            << ";fnv1a64=" << checksum
            << ";acquired=1;returned_external=1;visible_frame=0";
        response = out.str();
    }

done:
    DestroyConsumer(&consumer);
    CloseReceived(&image_packet);
    CloseReceived(&timeline_packet);
    if (sockets[0] >= 0) close(sockets[0]);
    if (sockets[1] >= 0) close(sockets[1]);
    return ToJString(env, response.empty() ? Fail(rid, gen, seq, "unknown") : response);
}
