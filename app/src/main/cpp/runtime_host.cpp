#include <jni.h>
#include <sys/socket.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include <android/hardware_buffer.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string VersionString(uint32_t version) {
    std::ostringstream output;
    output << VK_VERSION_MAJOR(version)
           << "." << VK_VERSION_MINOR(version)
           << "." << VK_VERSION_PATCH(version);
    return output.str();
}

const char* DeviceTypeString(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU:
            return "integrated";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:
            return "discrete";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:
            return "virtual";
        case VK_PHYSICAL_DEVICE_TYPE_CPU:
            return "cpu";
        case VK_PHYSICAL_DEVICE_TYPE_OTHER:
        default:
            return "other";
    }
}

bool HasExtension(
        const std::vector<VkExtensionProperties>& extensions,
        const char* name) {
    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [name](const VkExtensionProperties& extension) {
            return std::string(extension.extensionName) == name;
        });
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    struct utsname info {};
    const int uname_result = uname(&info);

    std::ostringstream output;
    output << "native-host=loaded";
    output << ";pid=" << getpid();

#if defined(__aarch64__)
    output << ";abi=arm64-v8a";
#elif defined(__x86_64__)
    output << ";abi=x86_64";
#else
    output << ";abi=unknown";
#endif

    if (uname_result == 0) {
        output << ";kernel=" << info.release;
        output << ";machine=" << info.machine;
    } else {
        output << ";kernel=unavailable";
    }

#if defined(__ANDROID_API__)
    output << ";ndk-api=" << __ANDROID_API__;
#endif

    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeGraphicsProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    uint32_t instance_api = VK_API_VERSION_1_0;
    const auto enumerate_instance_version =
        reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));

    if (enumerate_instance_version != nullptr) {
        if (enumerate_instance_version(&instance_api) != VK_SUCCESS) {
            instance_api = VK_API_VERSION_1_0;
        }
    }

    VkApplicationInfo app_info {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "PocketPC Probe";
    app_info.applicationVersion = VK_MAKE_VERSION(0, 1, 4);
    app_info.pEngineName = "PocketPC";
    app_info.engineVersion = VK_MAKE_VERSION(0, 1, 4);
    app_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult create_result = vkCreateInstance(&create_info, nullptr, &instance);
    if (create_result != VK_SUCCESS) {
        std::ostringstream output;
        output << "vulkan=unavailable"
               << ";instance_api=" << VersionString(instance_api)
               << ";vkCreateInstance=" << static_cast<int>(create_result);
        const std::string value = output.str();
        return env->NewStringUTF(value.c_str());
    }

    uint32_t device_count = 0;
    VkResult enumerate_result =
        vkEnumeratePhysicalDevices(instance, &device_count, nullptr);

    if (enumerate_result != VK_SUCCESS || device_count == 0) {
        std::ostringstream output;
        output << "vulkan=no-physical-device"
               << ";instance_api=" << VersionString(instance_api)
               << ";result=" << static_cast<int>(enumerate_result);
        vkDestroyInstance(instance, nullptr);
        const std::string value = output.str();
        return env->NewStringUTF(value.c_str());
    }

    std::vector<VkPhysicalDevice> devices(device_count);
    enumerate_result =
        vkEnumeratePhysicalDevices(instance, &device_count, devices.data());

    if (enumerate_result != VK_SUCCESS) {
        std::ostringstream output;
        output << "vulkan=device-enumeration-failed"
               << ";instance_api=" << VersionString(instance_api)
               << ";result=" << static_cast<int>(enumerate_result);
        vkDestroyInstance(instance, nullptr);
        const std::string value = output.str();
        return env->NewStringUTF(value.c_str());
    }

    VkPhysicalDevice selected = devices.front();
    for (const VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceProperties candidate_properties {};
        vkGetPhysicalDeviceProperties(candidate, &candidate_properties);
        if (candidate_properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            selected = candidate;
            break;
        }
    }

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(selected, &properties);

    uint32_t queue_family_count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(
        selected,
        &queue_family_count,
        nullptr);
    std::vector<VkQueueFamilyProperties> queue_families(queue_family_count);
    if (queue_family_count > 0) {
        vkGetPhysicalDeviceQueueFamilyProperties(
            selected,
            &queue_family_count,
            queue_families.data());
    }
    const bool has_graphics_queue = std::any_of(
        queue_families.begin(),
        queue_families.end(),
        [](const VkQueueFamilyProperties& queue) {
            return queue.queueCount > 0 &&
                   (queue.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
        });

    uint32_t extension_count = 0;
    vkEnumerateDeviceExtensionProperties(
        selected,
        nullptr,
        &extension_count,
        nullptr);
    std::vector<VkExtensionProperties> extensions(extension_count);
    if (extension_count > 0) {
        vkEnumerateDeviceExtensionProperties(
            selected,
            nullptr,
            &extension_count,
            extensions.data());
    }

    std::ostringstream output;
    output << "vulkan=available"
           << ";instance_api=" << VersionString(instance_api)
           << ";device_count=" << device_count
           << ";device=" << properties.deviceName
           << ";device_type=" << DeviceTypeString(properties.deviceType)
           << ";device_api=" << VersionString(properties.apiVersion)
           << ";driver_version=" << properties.driverVersion
           << ";vendor_id=" << properties.vendorID
           << ";device_id=" << properties.deviceID
           << ";queue_families=" << queue_family_count
           << ";graphics_queue=" << (has_graphics_queue ? "yes" : "no")
           << ";device_extensions=" << extension_count
           << ";swapchain="
           << (HasExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME) ? "yes" : "no")
#ifdef VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME
           << ";android_hardware_buffer_vulkan="
           << (HasExtension(
                   extensions,
                   VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME)
                   ? "yes"
                   : "no")
#endif
#ifdef VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME
           << ";google_display_timing="
           << (HasExtension(extensions, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME) ? "yes" : "no")
#endif
           ;

    vkDestroyInstance(instance, nullptr);
    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeHardwareBufferProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    AHardwareBuffer_Desc requested {};
    requested.width = 64;
    requested.height = 64;
    requested.layers = 1;
    requested.format =
        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    requested.usage =
        AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT |
        AHARDWAREBUFFER_USAGE_CPU_READ_RARELY |
        AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;

    AHardwareBuffer* source = nullptr;
    const int allocate_result =
        AHardwareBuffer_allocate(
            &requested,
            &source);

    std::ostringstream output;
    output << "ahardwarebuffer=";

    if (allocate_result != 0 || source == nullptr) {
        output << "allocation-failed"
               << ";allocate_result="
               << allocate_result;
        const std::string value = output.str();
        return env->NewStringUTF(value.c_str());
    }

    AHardwareBuffer_Desc source_desc {};
    AHardwareBuffer_describe(
        source,
        &source_desc);

    void* mapped = nullptr;
    const int lock_result =
        AHardwareBuffer_lock(
            source,
            AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1,
            nullptr,
            &mapped);
    int unlock_result = -1;
    if (lock_result == 0 && mapped != nullptr) {
        std::memset(mapped, 0, 4);
        unlock_result =
            AHardwareBuffer_unlock(
                source,
                nullptr);
    }

    int sockets[2] = {-1, -1};
    const int socket_result =
        socketpair(
            AF_UNIX,
            SOCK_STREAM | SOCK_CLOEXEC,
            0,
            sockets);

    int send_result = -1;
    int receive_result = -1;
    AHardwareBuffer* received = nullptr;

    if (socket_result == 0) {
        send_result =
            AHardwareBuffer_sendHandleToUnixSocket(
                source,
                sockets[0]);
        if (send_result == 0) {
            receive_result =
                AHardwareBuffer_recvHandleFromUnixSocket(
                    sockets[1],
                    &received);
        }
    }

    AHardwareBuffer_Desc received_desc {};
    bool descriptor_match = false;
    if (receive_result == 0 && received != nullptr) {
        AHardwareBuffer_describe(
            received,
            &received_desc);
        descriptor_match =
            received_desc.width == source_desc.width &&
            received_desc.height == source_desc.height &&
            received_desc.layers == source_desc.layers &&
            received_desc.format == source_desc.format;
    }

    if (sockets[0] >= 0) {
        close(sockets[0]);
    }
    if (sockets[1] >= 0) {
        close(sockets[1]);
    }
    if (received != nullptr) {
        AHardwareBuffer_release(received);
    }
    AHardwareBuffer_release(source);

    const bool allocation_ok =
        source_desc.width == requested.width &&
        source_desc.height == requested.height &&
        source_desc.layers == requested.layers &&
        source_desc.format == requested.format;
    const bool socket_roundtrip_ok =
        socket_result == 0 &&
        send_result == 0 &&
        receive_result == 0 &&
        descriptor_match;

    output << (allocation_ok ? "available" : "descriptor-mismatch")
           << ";width=" << source_desc.width
           << ";height=" << source_desc.height
           << ";layers=" << source_desc.layers
           << ";format=" << source_desc.format
           << ";stride=" << source_desc.stride
           << ";cpu_lock=" << (lock_result == 0 ? "ok" : "failed")
           << ";cpu_unlock=" << (unlock_result == 0 ? "ok" : "failed")
           << ";unix_socket=" << (socket_result == 0 ? "ok" : "failed")
           << ";send_handle=" << (send_result == 0 ? "ok" : "failed")
           << ";recv_handle=" << (receive_result == 0 ? "ok" : "failed")
           << ";descriptor_match=" << (descriptor_match ? "yes" : "no")
           << ";cross_process_transport="
           << (socket_roundtrip_ok ? "structurally-ready" : "not-ready")
           << ";vulkan_wsi=not-tested";

    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}
