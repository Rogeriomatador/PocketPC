#include <jni.h>
#include <vulkan/vulkan.h>

#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "vulkan_continuous_present_contract.h"

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
    JNIEnv*, jobject, jlong, jlong, jint, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSendTimeline(
    JNIEnv*, jobject, jlong, jlong, jint);

namespace {

namespace v52 = pocketpc::vulkan_continuous_present;

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kPviMagic = 0x31495650u;
constexpr uint16_t kPviVersion = 1;
constexpr size_t kPviBytes = 64;
constexpr uint32_t kPvsMagic = 0x31535650u;
constexpr uint16_t kPvsVersion = 1;
constexpr size_t kPvsBytes = 40;
constexpr uint32_t kTimelineRoleFrameOwnership = 1;
constexpr VkImageLayout kExternalBoundaryLayout = VK_IMAGE_LAYOUT_GENERAL;
constexpr VkImageUsageFlags kExpectedUsage =
    VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
    VK_IMAGE_USAGE_TRANSFER_DST_BIT |
    VK_IMAGE_USAGE_SAMPLED_BIT |
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
constexpr VkFormat kExpectedFormat = VK_FORMAT_R8G8B8A8_UNORM;
constexpr uint32_t kMaxDimension = 4096;
constexpr uint64_t kMaxReadbackBytes = 4096ull * 4096ull * 4ull;
constexpr uint64_t kNanosPerSecond = 1000000000ull;
constexpr uint64_t kNanosPerMicrosecond = 1000ull;
constexpr uint64_t kFnvOffset = 14695981039346656037ull;
constexpr uint64_t kFnvPrime = 1099511628211ull;
constexpr size_t kMaxSessions = 16;

enum class SessionPhase {
    WAITING_GUEST,
    GUEST_READY,
    READBACK_RETURNED_EXTERNAL,
    POISONED,
    CLOSED,
};

struct ReceivedFd {
    int fd = -1;
    std::vector<uint8_t> payload;
};

struct Session {
    std::mutex mutex;
    bool closed = false;
    uint64_t session_id = 0;
    uint64_t resource_id = 0;
    uint64_t generation = 0;
    uint64_t offer_sequence = 0;
    uint64_t expected_frame_sequence = v52::kFirstFrameSequence;
    uint32_t width = 0;
    uint32_t height = 0;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint64_t allocation_size = 0;
    uint32_t memory_type_bits = 0;
    uint32_t memory_type_index = 0;
    SessionPhase phase = SessionPhase::WAITING_GUEST;

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
    PFN_vkSignalSemaphore signal_semaphore = nullptr;
    PFN_vkGetSemaphoreCounterValue get_semaphore_counter = nullptr;
};

std::mutex g_sessions_mutex;
std::unordered_map<uint64_t, std::shared_ptr<Session>> g_sessions;
std::atomic<uint64_t> g_next_session_id {1u};

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
    for (unsigned i = 0; i < 8; ++i) {
        value |= static_cast<uint64_t>(p[i]) << (i * 8u);
    }
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
        if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS) continue;
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

bool ValidatePvi(
        const ReceivedFd& packet,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t offer_sequence,
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
        GetU64Le(p + 24) == offer_sequence &&
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

bool HasDeviceExtension(VkPhysicalDevice physical, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(physical, nullptr, &count, nullptr) != VK_SUCCESS ||
        count > 4096) {
        return false;
    }
    std::vector<VkExtensionProperties> properties(count);
    if (count != 0) {
        uint32_t read_count = count;
        if (vkEnumerateDeviceExtensionProperties(
                physical, nullptr, &read_count, properties.data()) != VK_SUCCESS) {
            return false;
        }
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
        physical_count == 0 || physical_count > 64) {
        return false;
    }
    std::vector<VkPhysicalDevice> physicals(physical_count);
    if (vkEnumeratePhysicalDevices(instance, &physical_count, physicals.data()) != VK_SUCCESS) {
        return false;
    }

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

void DestroySession(Session* session) {
    if (!session) return;
    if (session->device != VK_NULL_HANDLE) {
        if (session->command_pool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(session->device, session->command_pool, nullptr);
            session->command_pool = VK_NULL_HANDLE;
        }
        if (session->staging != VK_NULL_HANDLE) {
            vkDestroyBuffer(session->device, session->staging, nullptr);
            session->staging = VK_NULL_HANDLE;
        }
        if (session->staging_memory != VK_NULL_HANDLE) {
            vkFreeMemory(session->device, session->staging_memory, nullptr);
            session->staging_memory = VK_NULL_HANDLE;
        }
        if (session->timeline != VK_NULL_HANDLE) {
            vkDestroySemaphore(session->device, session->timeline, nullptr);
            session->timeline = VK_NULL_HANDLE;
        }
        if (session->image != VK_NULL_HANDLE) {
            vkDestroyImage(session->device, session->image, nullptr);
            session->image = VK_NULL_HANDLE;
        }
        if (session->image_memory != VK_NULL_HANDLE) {
            vkFreeMemory(session->device, session->image_memory, nullptr);
            session->image_memory = VK_NULL_HANDLE;
        }
        vkDestroyDevice(session->device, nullptr);
        session->device = VK_NULL_HANDLE;
    }
    if (session->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(session->instance, nullptr);
        session->instance = VK_NULL_HANDLE;
    }
}

int CreateImportedSession(
        Session* session,
        int* image_fd,
        int* timeline_fd,
        std::string* reason) {
    if (!session || !image_fd || !timeline_fd || !reason ||
        *image_fd < 0 || *timeline_fd < 0) {
        return -1;
    }

    VkApplicationInfo app {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketPC Vulkan Continuous Present Host";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "PocketPC";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = VK_API_VERSION_1_1;

    VkInstanceCreateInfo instance_info {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    VkResult result = vkCreateInstance(&instance_info, nullptr, &session->instance);
    if (result != VK_SUCCESS) {
        *reason = "instance-create";
        return result;
    }

    if (!PickPhysicalAndQueue(session->instance, &session->physical, &session->queue_family)) {
        *reason = "physical-or-queue";
        return -2;
    }
    if (!HasDeviceExtension(session->physical, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME) ||
        !HasDeviceExtension(session->physical, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
        *reason = "external-fd-extension";
        return -3;
    }

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(session->physical, &properties);
    const bool core_12 =
        VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
        (VK_VERSION_MAJOR(properties.apiVersion) == 1 &&
            VK_VERSION_MINOR(properties.apiVersion) >= 2);
    if (!core_12 &&
        !HasDeviceExtension(session->physical, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME)) {
        *reason = "timeline-extension";
        return -4;
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_features {};
    timeline_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    VkPhysicalDeviceFeatures2 features {};
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &timeline_features;
    auto get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        vkGetInstanceProcAddr(session->instance, "vkGetPhysicalDeviceFeatures2"));
    if (get_features2 == nullptr) {
        get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(session->instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }
    if (get_features2 == nullptr) {
        *reason = "vkGetPhysicalDeviceFeatures2-missing";
        return -5;
    }
    get_features2(session->physical, &features);
    if (timeline_features.timelineSemaphore != VK_TRUE) {
        *reason = "timeline-feature";
        return -5;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = session->queue_family;
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
    result = vkCreateDevice(session->physical, &device_info, nullptr, &session->device);
    if (result != VK_SUCCESS) {
        *reason = "device-create";
        return result;
    }

    vkGetDeviceQueue(session->device, session->queue_family, 0, &session->queue);
    if (session->queue == VK_NULL_HANDLE) {
        *reason = "queue-get";
        return -6;
    }

    session->import_semaphore_fd = reinterpret_cast<PFN_vkImportSemaphoreFdKHR>(
        vkGetDeviceProcAddr(session->device, "vkImportSemaphoreFdKHR"));
    session->wait_semaphores = reinterpret_cast<PFN_vkWaitSemaphores>(
        vkGetDeviceProcAddr(session->device, "vkWaitSemaphores"));
    if (!session->wait_semaphores) {
        session->wait_semaphores = reinterpret_cast<PFN_vkWaitSemaphores>(
            vkGetDeviceProcAddr(session->device, "vkWaitSemaphoresKHR"));
    }
    session->signal_semaphore = reinterpret_cast<PFN_vkSignalSemaphore>(
        vkGetDeviceProcAddr(session->device, "vkSignalSemaphore"));
    if (!session->signal_semaphore) {
        session->signal_semaphore = reinterpret_cast<PFN_vkSignalSemaphore>(
            vkGetDeviceProcAddr(session->device, "vkSignalSemaphoreKHR"));
    }
    session->get_semaphore_counter = reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
        vkGetDeviceProcAddr(session->device, "vkGetSemaphoreCounterValue"));
    if (!session->get_semaphore_counter) {
        session->get_semaphore_counter = reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
            vkGetDeviceProcAddr(session->device, "vkGetSemaphoreCounterValueKHR"));
    }
    if (!session->import_semaphore_fd || !session->wait_semaphores ||
        !session->signal_semaphore || !session->get_semaphore_counter) {
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
    image_info.format = session->format;
    image_info.extent = {session->width, session->height, 1};
    image_info.mipLevels = 1;
    image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = kExpectedUsage;
    image_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    image_info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    result = vkCreateImage(session->device, &image_info, nullptr, &session->image);
    if (result != VK_SUCCESS) {
        *reason = "image-create";
        return result;
    }

    VkMemoryRequirements image_requirements {};
    vkGetImageMemoryRequirements(session->device, session->image, &image_requirements);
    if (image_requirements.size == 0 || image_requirements.memoryTypeBits == 0 ||
        session->memory_type_index >= 32 ||
        (image_requirements.memoryTypeBits & (1u << session->memory_type_index)) == 0 ||
        session->allocation_size < image_requirements.size) {
        *reason = "import-memory-requirements";
        return -8;
    }

    VkMemoryDedicatedAllocateInfo dedicated {};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = session->image;
    VkImportMemoryFdInfoKHR import_memory {};
    import_memory.sType = VK_STRUCTURE_TYPE_IMPORT_MEMORY_FD_INFO_KHR;
    import_memory.pNext = &dedicated;
    import_memory.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_memory.fd = *image_fd;
    VkMemoryAllocateInfo allocation {};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &import_memory;
    allocation.allocationSize = session->allocation_size;
    allocation.memoryTypeIndex = session->memory_type_index;
    result = vkAllocateMemory(session->device, &allocation, nullptr, &session->image_memory);
    if (result != VK_SUCCESS) {
        *reason = "image-memory-import";
        return result;
    }
    *image_fd = -1;

    result = vkBindImageMemory(session->device, session->image, session->image_memory, 0);
    if (result != VK_SUCCESS) {
        *reason = "image-bind";
        return result;
    }

    VkSemaphoreTypeCreateInfo type_info {};
    type_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    type_info.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    type_info.initialValue = 0;
    VkSemaphoreCreateInfo semaphore_info {};
    semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphore_info.pNext = &type_info;
    result = vkCreateSemaphore(session->device, &semaphore_info, nullptr, &session->timeline);
    if (result != VK_SUCCESS) {
        *reason = "timeline-create";
        return result;
    }

    VkImportSemaphoreFdInfoKHR import_semaphore {};
    import_semaphore.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    import_semaphore.semaphore = session->timeline;
    import_semaphore.flags = 0;
    import_semaphore.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
    import_semaphore.fd = *timeline_fd;
    result = session->import_semaphore_fd(session->device, &import_semaphore);
    if (result != VK_SUCCESS) {
        *reason = "timeline-import";
        return result;
    }
    *timeline_fd = -1;

    VkCommandPoolCreateInfo pool_info {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.flags =
        VK_COMMAND_POOL_CREATE_TRANSIENT_BIT |
        VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pool_info.queueFamilyIndex = session->queue_family;
    result = vkCreateCommandPool(session->device, &pool_info, nullptr, &session->command_pool);
    if (result != VK_SUCCESS) {
        *reason = "command-pool";
        return result;
    }

    const uint64_t bytes =
        static_cast<uint64_t>(session->width) *
        static_cast<uint64_t>(session->height) * 4ull;
    if (bytes == 0 || bytes > kMaxReadbackBytes ||
        bytes > static_cast<uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        *reason = "readback-size";
        return -9;
    }
    session->staging_size = static_cast<VkDeviceSize>(bytes);

    VkBufferCreateInfo buffer_info {};
    buffer_info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    buffer_info.size = session->staging_size;
    buffer_info.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    buffer_info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    result = vkCreateBuffer(session->device, &buffer_info, nullptr, &session->staging);
    if (result != VK_SUCCESS) {
        *reason = "staging-buffer";
        return result;
    }

    VkMemoryRequirements staging_requirements {};
    vkGetBufferMemoryRequirements(session->device, session->staging, &staging_requirements);
    uint32_t staging_memory_index = 0;
    if (!FindHostVisibleMemoryType(
            session->physical,
            staging_requirements.memoryTypeBits,
            &staging_memory_index,
            &session->staging_coherent)) {
        *reason = "host-visible-memory";
        return -10;
    }

    VkMemoryAllocateInfo staging_allocation {};
    staging_allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    staging_allocation.allocationSize = staging_requirements.size;
    staging_allocation.memoryTypeIndex = staging_memory_index;
    result = vkAllocateMemory(
        session->device,
        &staging_allocation,
        nullptr,
        &session->staging_memory);
    if (result != VK_SUCCESS) {
        *reason = "staging-memory";
        return result;
    }

    result = vkBindBufferMemory(
        session->device,
        session->staging,
        session->staging_memory,
        0);
    if (result != VK_SUCCESS) {
        *reason = "staging-bind";
        return result;
    }

    return 0;
}

int WaitExactGuestReady(
        Session* session,
        uint64_t target,
        uint64_t timeout_ns,
        uint64_t* observed,
        std::string* reason) {
    if (!session || !observed || !reason || target == 0 || timeout_ns == 0) return -1;

    VkSemaphore semaphore = session->timeline;
    VkSemaphoreWaitInfo wait_info {};
    wait_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    wait_info.semaphoreCount = 1;
    wait_info.pSemaphores = &semaphore;
    wait_info.pValues = &target;

    VkResult result = session->wait_semaphores(session->device, &wait_info, timeout_ns);
    if (result != VK_SUCCESS) {
        *reason = "timeline-wait";
        return result;
    }

    result = session->get_semaphore_counter(session->device, session->timeline, observed);
    if (result != VK_SUCCESS) {
        *reason = "timeline-counter";
        return result;
    }
    if (*observed != target) {
        *reason = "timeline-not-exact";
        return -2;
    }
    return 0;
}

int ReadbackAndReturnExternal(
        Session* session,
        jint* output_argb,
        size_t output_pixels,
        bool* returned_external,
        uint64_t* checksum,
        uint64_t* nonzero_bytes,
        std::string* reason) {
    if (!session || !output_argb || !returned_external || !checksum ||
        !nonzero_bytes || !reason) {
        return -1;
    }
    *returned_external = false;

    const uint64_t expected_pixels =
        static_cast<uint64_t>(session->width) *
        static_cast<uint64_t>(session->height);
    if (expected_pixels == 0 || expected_pixels != static_cast<uint64_t>(output_pixels)) {
        *reason = "argb-size";
        return -2;
    }

    VkCommandBufferAllocateInfo allocate_info {};
    allocate_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate_info.commandPool = session->command_pool;
    allocate_info.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate_info.commandBufferCount = 1;
    VkCommandBuffer command = VK_NULL_HANDLE;
    VkResult result = vkAllocateCommandBuffers(session->device, &allocate_info, &command);
    if (result != VK_SUCCESS) {
        *reason = "command-allocate";
        return result;
    }

    VkCommandBufferBeginInfo begin_info {};
    begin_info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin_info.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    result = vkBeginCommandBuffer(command, &begin_info);
    if (result != VK_SUCCESS) {
        vkFreeCommandBuffers(session->device, session->command_pool, 1, &command);
        *reason = "command-begin";
        return result;
    }

    VkImageMemoryBarrier acquire {};
    acquire.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    acquire.srcAccessMask = 0;
    acquire.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    acquire.oldLayout = kExternalBoundaryLayout;
    acquire.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    acquire.dstQueueFamilyIndex = session->queue_family;
    acquire.image = session->image;
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
    region.imageExtent = {session->width, session->height, 1};
    vkCmdCopyImageToBuffer(
        command,
        session->image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        session->staging,
        1,
        &region);

    VkBufferMemoryBarrier buffer_barrier {};
    buffer_barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    buffer_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    buffer_barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    buffer_barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    buffer_barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    buffer_barrier.buffer = session->staging;
    buffer_barrier.offset = 0;
    buffer_barrier.size = session->staging_size;
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
    release.srcQueueFamilyIndex = session->queue_family;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    release.image = session->image;
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
    if (result != VK_SUCCESS) {
        vkFreeCommandBuffers(session->device, session->command_pool, 1, &command);
        *reason = "command-end";
        return result;
    }

    VkSubmitInfo submit {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    result = vkQueueSubmit(session->queue, 1, &submit, VK_NULL_HANDLE);
    if (result != VK_SUCCESS) {
        vkFreeCommandBuffers(session->device, session->command_pool, 1, &command);
        *reason = "queue-submit";
        return result;
    }

    result = vkQueueWaitIdle(session->queue);
    if (result != VK_SUCCESS) {
        *reason = "queue-wait";
        return result;
    }
    *returned_external = true;
    vkFreeCommandBuffers(session->device, session->command_pool, 1, &command);

    void* mapped = nullptr;
    result = vkMapMemory(
        session->device,
        session->staging_memory,
        0,
        VK_WHOLE_SIZE,
        0,
        &mapped);
    if (result != VK_SUCCESS || !mapped) {
        *reason = "staging-map";
        return result == VK_SUCCESS ? -3 : result;
    }

    if (!session->staging_coherent) {
        VkMappedMemoryRange range {};
        range.sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE;
        range.memory = session->staging_memory;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        result = vkInvalidateMappedMemoryRanges(session->device, 1, &range);
        if (result != VK_SUCCESS) {
            vkUnmapMemory(session->device, session->staging_memory);
            *reason = "staging-invalidate";
            return result;
        }
    }

    const auto* bytes = static_cast<const uint8_t*>(mapped);
    uint64_t hash = kFnvOffset;
    uint64_t nonzero = 0;
    for (VkDeviceSize i = 0; i < session->staging_size; ++i) {
        hash ^= bytes[i];
        hash *= kFnvPrime;
        if (bytes[i] != 0) ++nonzero;
    }

    for (size_t pixel = 0; pixel < output_pixels; ++pixel) {
        const size_t offset = pixel * 4u;
        const uint32_t r = bytes[offset + 0u];
        const uint32_t g = bytes[offset + 1u];
        const uint32_t b = bytes[offset + 2u];
        const uint32_t a = bytes[offset + 3u];
        const uint32_t packed =
            (a << 24u) |
            (r << 16u) |
            (g << 8u) |
            b;
        static_assert(sizeof(jint) == sizeof(uint32_t));
        std::memcpy(&output_argb[pixel], &packed, sizeof(packed));
    }

    vkUnmapMemory(session->device, session->staging_memory);
    *checksum = hash;
    *nonzero_bytes = nonzero;
    return 0;
}

int SignalExactHostConsumed(
        Session* session,
        uint64_t expected_current,
        uint64_t signal_value,
        std::string* reason) {
    if (!session || !reason || expected_current == 0 || signal_value == 0) return -1;

    uint64_t observed = 0;
    VkResult result = session->get_semaphore_counter(
        session->device,
        session->timeline,
        &observed);
    if (result != VK_SUCCESS) {
        *reason = "timeline-counter-before-signal";
        return result;
    }
    if (observed != expected_current) {
        *reason = "timeline-current-not-exact";
        return -2;
    }

    VkSemaphoreSignalInfo signal_info {};
    signal_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_SIGNAL_INFO;
    signal_info.semaphore = session->timeline;
    signal_info.value = signal_value;
    result = session->signal_semaphore(session->device, &signal_info);
    if (result != VK_SUCCESS) {
        *reason = "timeline-signal";
        return result;
    }
    return 0;
}

std::shared_ptr<Session> FindSession(uint64_t session_id) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    const auto it = g_sessions.find(session_id);
    return it == g_sessions.end() ? nullptr : it->second;
}

std::string OpenFail(
        uint64_t resource_id,
        uint64_t generation,
        uint64_t offer_sequence,
        const char* reason,
        int code = 0) {
    std::ostringstream out;
    out << "vulkan-continuous-present-host-open=failed"
        << ";protocol=" << kProtocol
        << ";resource_id=" << resource_id
        << ";generation=" << generation
        << ";offer_sequence=" << offer_sequence
        << ";reason=" << reason
        << ";code=" << code;
    return out.str();
}

std::string StepFail(
        const char* operation,
        uint64_t session_id,
        uint64_t resource_id,
        uint64_t generation,
        uint64_t frame_sequence,
        const char* reason,
        int code = 0,
        bool returned_external = false) {
    std::ostringstream out;
    out << "vulkan-continuous-present-host-" << operation << "=failed"
        << ";protocol=" << kProtocol
        << ";session_id=" << session_id
        << ";resource_id=" << resource_id
        << ";generation=" << generation
        << ";frame_sequence=" << frame_sequence
        << ";reason=" << reason
        << ";code=" << code
        << ";returned_external=" << (returned_external ? 1 : 0)
        << ";visible_frame=0";
    return out.str();
}

bool IdentityMatches(
        const Session& session,
        uint64_t resource_id,
        uint64_t generation) {
    return !session.closed &&
        session.resource_id == resource_id &&
        session.generation == generation;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeOpen(
        JNIEnv* env,
        jobject /* thiz */,
        jlong resource_id,
        jlong generation,
        jlong offer_sequence,
        jint width,
        jint height,
        jint format,
        jlong allocation_size,
        jlong memory_type_bits,
        jint memory_type_index,
        jlong timeout_nanos) {
    const uint64_t rid = resource_id > 0 ? static_cast<uint64_t>(resource_id) : 0u;
    const uint64_t gen = generation > 0 ? static_cast<uint64_t>(generation) : 0u;
    const uint64_t offer =
        offer_sequence > 0 ? static_cast<uint64_t>(offer_sequence) : 0u;

    if (rid == 0 || gen == 0 || offer == 0 || width <= 0 || height <= 0 ||
        static_cast<uint32_t>(width) > kMaxDimension ||
        static_cast<uint32_t>(height) > kMaxDimension ||
        format != static_cast<jint>(kExpectedFormat) ||
        allocation_size <= 0 || memory_type_bits <= 0 ||
        memory_type_index < 0 || memory_type_index >= 32 ||
        timeout_nanos <= 0) {
        return ToJString(env, OpenFail(rid, gen, offer, "invalid-argument"));
    }

    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        if (g_sessions.size() >= kMaxSessions) {
            return ToJString(env, OpenFail(rid, gen, offer, "session-capacity"));
        }
    }

    int sockets[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sockets) != 0) {
        return ToJString(env, OpenFail(rid, gen, offer, "socketpair", errno));
    }

    ReceivedFd image_packet;
    ReceivedFd timeline_packet;
    std::shared_ptr<Session> session = std::make_shared<Session>();
    std::string reason;
    std::string response;

    session->resource_id = rid;
    session->generation = gen;
    session->offer_sequence = offer;
    session->width = static_cast<uint32_t>(width);
    session->height = static_cast<uint32_t>(height);
    session->format = static_cast<VkFormat>(format);
    session->allocation_size = static_cast<uint64_t>(allocation_size);
    session->memory_type_bits = static_cast<uint32_t>(memory_type_bits);
    session->memory_type_index = static_cast<uint32_t>(memory_type_index);

    if (!SetReceiveTimeout(sockets[1], static_cast<uint64_t>(timeout_nanos))) {
        response = OpenFail(rid, gen, offer, "socket-timeout", errno);
        goto done;
    }

    {
        jstring result = Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSend(
            env,
            nullptr,
            resource_id,
            generation,
            sockets[0],
            offer_sequence);
        const std::string status = FromJString(env, result);
        if (result) env->DeleteLocalRef(result);
        if (!StartsWith(status, "vulkan-external-image-fd-send=ok;")) {
            response = OpenFail(rid, gen, offer, "pvi1-reexport");
            goto done;
        }
    }
    if (!ReceiveSingleFd(sockets[1], kPviBytes, &image_packet) ||
        !ValidatePvi(
            image_packet,
            rid,
            gen,
            offer,
            session->width,
            session->height,
            session->format,
            session->allocation_size,
            session->memory_type_bits,
            session->memory_type_index)) {
        response = OpenFail(rid, gen, offer, "pvi1-identity");
        goto done;
    }

    {
        jstring result =
            Java_dev_pocketpc_core_runtime_VulkanExternalImageFdBroker_nativeSendTimeline(
                env,
                nullptr,
                resource_id,
                generation,
                sockets[0]);
        const std::string status = FromJString(env, result);
        if (result) env->DeleteLocalRef(result);
        if (!StartsWith(status, "vulkan-external-timeline-fd-send=ok;")) {
            response = OpenFail(rid, gen, offer, "pvs1-reexport");
            goto done;
        }
    }
    if (!ReceiveSingleFd(sockets[1], kPvsBytes, &timeline_packet) ||
        !ValidatePvs(timeline_packet, rid, gen)) {
        response = OpenFail(rid, gen, offer, "pvs1-identity");
        goto done;
    }

    {
        const int create_result = CreateImportedSession(
            session.get(),
            &image_packet.fd,
            &timeline_packet.fd,
            &reason);
        if (create_result != 0) {
            response = OpenFail(rid, gen, offer, reason.c_str(), create_result);
            goto done;
        }
    }

    {
        uint64_t session_id = g_next_session_id.fetch_add(1u);
        if (session_id == 0u) {
            session_id = g_next_session_id.fetch_add(1u);
        }
        if (session_id == 0u) {
            response = OpenFail(rid, gen, offer, "session-id-exhausted");
            goto done;
        }
        session->session_id = session_id;

        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        if (g_sessions.size() >= kMaxSessions || g_sessions.count(session_id) != 0u) {
            response = OpenFail(rid, gen, offer, "session-register");
            goto done;
        }
        g_sessions.emplace(session_id, session);

        std::ostringstream out;
        out << "vulkan-continuous-present-host-open=ok"
            << ";protocol=" << kProtocol
            << ";session_id=" << session_id
            << ";resource_id=" << rid
            << ";generation=" << gen
            << ";offer_sequence=" << offer
            << ";visible_frame=0";
        response = out.str();
    }

done:
    CloseReceived(&image_packet);
    CloseReceived(&timeline_packet);
    if (sockets[0] >= 0) close(sockets[0]);
    if (sockets[1] >= 0) close(sockets[1]);
    if (!StartsWith(response, "vulkan-continuous-present-host-open=ok;")) {
        DestroySession(session.get());
    }
    return ToJString(
        env,
        response.empty() ? OpenFail(rid, gen, offer, "unknown") : response);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeAwaitGuestReady(
        JNIEnv* env,
        jobject /* thiz */,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jlong target_value,
        jlong timeout_nanos) {
    const uint64_t sid = session_id > 0 ? static_cast<uint64_t>(session_id) : 0u;
    const uint64_t rid = resource_id > 0 ? static_cast<uint64_t>(resource_id) : 0u;
    const uint64_t gen = generation > 0 ? static_cast<uint64_t>(generation) : 0u;
    const uint64_t frame =
        frame_sequence > 0 ? static_cast<uint64_t>(frame_sequence) : 0u;
    const uint64_t target = target_value > 0 ? static_cast<uint64_t>(target_value) : 0u;

    if (sid == 0 || rid == 0 || gen == 0 || !v52::IsFrameSequenceValid(frame) ||
        target != v52::GuestReadyValue(frame) || timeout_nanos <= 0) {
        return ToJString(
            env,
            StepFail("await", sid, rid, gen, frame, "invalid-argument"));
    }

    const std::shared_ptr<Session> session = FindSession(sid);
    if (!session) {
        return ToJString(env, StepFail("await", sid, rid, gen, frame, "session-missing"));
    }

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!IdentityMatches(*session, rid, gen)) {
        return ToJString(env, StepFail("await", sid, rid, gen, frame, "identity-mismatch"));
    }
    if (session->phase == SessionPhase::POISONED) {
        return ToJString(env, StepFail("await", sid, rid, gen, frame, "generation-poisoned"));
    }
    if (session->expected_frame_sequence != frame) {
        return ToJString(env, StepFail("await", sid, rid, gen, frame, "frame-not-expected"));
    }
    if (session->phase != SessionPhase::WAITING_GUEST &&
        session->phase != SessionPhase::GUEST_READY &&
        session->phase != SessionPhase::READBACK_RETURNED_EXTERNAL) {
        return ToJString(env, StepFail("await", sid, rid, gen, frame, "phase-invalid"));
    }

    uint64_t observed = 0;
    std::string reason;
    const int result = WaitExactGuestReady(
        session.get(),
        target,
        static_cast<uint64_t>(timeout_nanos),
        &observed,
        &reason);
    if (result != 0) {
        return ToJString(
            env,
            StepFail("await", sid, rid, gen, frame, reason.c_str(), result, true));
    }

    session->phase = SessionPhase::GUEST_READY;
    std::ostringstream out;
    out << "vulkan-continuous-present-host-await=ok"
        << ";protocol=" << kProtocol
        << ";session_id=" << sid
        << ";resource_id=" << rid
        << ";generation=" << gen
        << ";frame_sequence=" << frame
        << ";target=" << target
        << ";observed=" << observed
        << ";visible_frame=0";
    return ToJString(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeReadback(
        JNIEnv* env,
        jobject /* thiz */,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jintArray output_argb) {
    const uint64_t sid = session_id > 0 ? static_cast<uint64_t>(session_id) : 0u;
    const uint64_t rid = resource_id > 0 ? static_cast<uint64_t>(resource_id) : 0u;
    const uint64_t gen = generation > 0 ? static_cast<uint64_t>(generation) : 0u;
    const uint64_t frame =
        frame_sequence > 0 ? static_cast<uint64_t>(frame_sequence) : 0u;

    if (sid == 0 || rid == 0 || gen == 0 || !v52::IsFrameSequenceValid(frame) ||
        !output_argb) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "invalid-argument"));
    }

    const std::shared_ptr<Session> session = FindSession(sid);
    if (!session) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "session-missing"));
    }

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!IdentityMatches(*session, rid, gen)) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "identity-mismatch"));
    }
    if (session->phase == SessionPhase::POISONED) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "generation-poisoned"));
    }
    if (session->expected_frame_sequence != frame ||
        session->phase != SessionPhase::GUEST_READY) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "phase-or-frame-invalid"));
    }

    const uint64_t expected_pixels =
        static_cast<uint64_t>(session->width) *
        static_cast<uint64_t>(session->height);
    if (expected_pixels == 0 ||
        expected_pixels > static_cast<uint64_t>(std::numeric_limits<jsize>::max()) ||
        env->GetArrayLength(output_argb) != static_cast<jsize>(expected_pixels)) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "argb-size"));
    }

    jint* argb = env->GetIntArrayElements(output_argb, nullptr);
    if (!argb) {
        return ToJString(
            env,
            StepFail("readback", sid, rid, gen, frame, "argb-map"));
    }

    bool returned_external = false;
    uint64_t checksum = 0;
    uint64_t nonzero_bytes = 0;
    std::string reason;
    const int result = ReadbackAndReturnExternal(
        session.get(),
        argb,
        static_cast<size_t>(expected_pixels),
        &returned_external,
        &checksum,
        &nonzero_bytes,
        &reason);
    if (result != 0) {
        env->ReleaseIntArrayElements(output_argb, argb, JNI_ABORT);
        if (returned_external) {
            session->phase = SessionPhase::WAITING_GUEST;
        } else {
            session->phase = SessionPhase::POISONED;
        }
        return ToJString(
            env,
            StepFail(
                "readback",
                sid,
                rid,
                gen,
                frame,
                reason.c_str(),
                result,
                returned_external));
    }
    env->ReleaseIntArrayElements(output_argb, argb, 0);

    session->phase = SessionPhase::READBACK_RETURNED_EXTERNAL;
    std::ostringstream out;
    out << "vulkan-continuous-present-host-readback=ok"
        << ";protocol=" << kProtocol
        << ";session_id=" << sid
        << ";resource_id=" << rid
        << ";generation=" << gen
        << ";frame_sequence=" << frame
        << ";bytes=" << session->staging_size
        << ";nonzero_bytes=" << nonzero_bytes
        << ";fnv1a64=" << checksum
        << ";returned_external=1"
        << ";visible_frame=0";
    return ToJString(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeSignalHostConsumed(
        JNIEnv* env,
        jobject /* thiz */,
        jlong session_id,
        jlong resource_id,
        jlong generation,
        jlong frame_sequence,
        jlong signal_value) {
    const uint64_t sid = session_id > 0 ? static_cast<uint64_t>(session_id) : 0u;
    const uint64_t rid = resource_id > 0 ? static_cast<uint64_t>(resource_id) : 0u;
    const uint64_t gen = generation > 0 ? static_cast<uint64_t>(generation) : 0u;
    const uint64_t frame =
        frame_sequence > 0 ? static_cast<uint64_t>(frame_sequence) : 0u;
    const uint64_t signal =
        signal_value > 0 ? static_cast<uint64_t>(signal_value) : 0u;
    const uint64_t expected_current = v52::GuestReadyValue(frame);

    if (sid == 0 || rid == 0 || gen == 0 || !v52::IsFrameSequenceValid(frame) ||
        signal != v52::HostConsumedValue(frame) ||
        !v52::IsExactOwnershipPair(frame, expected_current, signal)) {
        return ToJString(
            env,
            StepFail("signal", sid, rid, gen, frame, "invalid-argument"));
    }

    const std::shared_ptr<Session> session = FindSession(sid);
    if (!session) {
        return ToJString(env, StepFail("signal", sid, rid, gen, frame, "session-missing"));
    }

    std::lock_guard<std::mutex> lock(session->mutex);
    if (!IdentityMatches(*session, rid, gen)) {
        return ToJString(env, StepFail("signal", sid, rid, gen, frame, "identity-mismatch"));
    }
    if (session->phase == SessionPhase::POISONED) {
        return ToJString(
            env,
            StepFail("signal", sid, rid, gen, frame, "generation-poisoned"));
    }
    if (session->expected_frame_sequence != frame ||
        session->phase != SessionPhase::READBACK_RETURNED_EXTERNAL) {
        return ToJString(
            env,
            StepFail("signal", sid, rid, gen, frame, "phase-or-frame-invalid", 0, true));
    }

    std::string reason;
    const int result = SignalExactHostConsumed(
        session.get(),
        expected_current,
        signal,
        &reason);
    if (result != 0) {
        return ToJString(
            env,
            StepFail(
                "signal",
                sid,
                rid,
                gen,
                frame,
                reason.c_str(),
                result,
                true));
    }

    if (frame == v52::kMaximumFrameSequence) {
        session->phase = SessionPhase::POISONED;
    } else {
        session->expected_frame_sequence = frame + 1u;
        session->phase = SessionPhase::WAITING_GUEST;
    }

    std::ostringstream out;
    out << "vulkan-continuous-present-host-signal=ok"
        << ";protocol=" << kProtocol
        << ";session_id=" << sid
        << ";resource_id=" << rid
        << ";generation=" << gen
        << ";frame_sequence=" << frame
        << ";signal_value=" << signal
        << ";returned_external=1"
        << ";visible_frame=0";
    return ToJString(env, out.str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanContinuousPresentNativeSession_nativeClose(
        JNIEnv* env,
        jobject /* thiz */,
        jlong session_id) {
    const uint64_t sid = session_id > 0 ? static_cast<uint64_t>(session_id) : 0u;
    if (sid == 0) {
        return ToJString(env, "vulkan-continuous-present-host-close=failed;reason=invalid-session");
    }

    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        const auto it = g_sessions.find(sid);
        if (it == g_sessions.end()) {
            return ToJString(env, "vulkan-continuous-present-host-close=failed;reason=session-missing");
        }
        session = it->second;
        g_sessions.erase(it);
    }

    uint64_t rid = 0;
    uint64_t gen = 0;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        rid = session->resource_id;
        gen = session->generation;
        session->closed = true;
        session->phase = SessionPhase::CLOSED;
        DestroySession(session.get());
    }

    std::ostringstream out;
    out << "vulkan-continuous-present-host-close=ok"
        << ";protocol=" << kProtocol
        << ";session_id=" << sid
        << ";resource_id=" << rid
        << ";generation=" << gen;
    return ToJString(env, out.str());
}
