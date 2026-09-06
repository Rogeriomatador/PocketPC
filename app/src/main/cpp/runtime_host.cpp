#include <jni.h>
#include <sys/utsname.h>
#include <unistd.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
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
#ifdef VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME
           << ";google_display_timing="
           << (HasExtension(extensions, VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME) ? "yes" : "no")
#endif
           ;

    vkDestroyInstance(instance, nullptr);
    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}
