#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr uint32_t kProbeProtocol = 1;
constexpr uint32_t kMaximumEnumeratedExtensions = 4096;

jstring JString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
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

void AppendFlag(
        std::ostringstream& output,
        const char* key,
        bool value) {
    output << ';' << key << '=' << (value ? "yes" : "no");
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_NativeRuntimeHost_nativeVulkanWsiCapabilityProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    std::ostringstream output;
    output << "vulkan-wsi-capabilities=";

    uint32_t instance_extension_count = 0;
    const VkResult instance_count_result =
        vkEnumerateInstanceExtensionProperties(
            nullptr,
            &instance_extension_count,
            nullptr);
    if (
        instance_count_result != VK_SUCCESS ||
        instance_extension_count > kMaximumEnumeratedExtensions
    ) {
        output << "instance-extension-enumeration-failed"
               << ";protocol=" << kProbeProtocol
               << ";result=" << static_cast<int>(instance_count_result)
               << ";count=" << instance_extension_count;
        return JString(env, output.str());
    }

    std::vector<VkExtensionProperties> instance_extensions(
        instance_extension_count);
    if (instance_extension_count > 0) {
        uint32_t count = instance_extension_count;
        const VkResult result =
            vkEnumerateInstanceExtensionProperties(
                nullptr,
                &count,
                instance_extensions.data());
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            output << "instance-extension-read-failed"
                   << ";protocol=" << kProbeProtocol
                   << ";result=" << static_cast<int>(result);
            return JString(env, output.str());
        }
        instance_extensions.resize(count);
    }

    VkApplicationInfo application_info {};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "PocketPC WSI Capability Probe";
    application_info.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.pEngineName = "PocketPC";
    application_info.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &application_info;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult create_result =
        vkCreateInstance(
            &create_info,
            nullptr,
            &instance);
    if (create_result != VK_SUCCESS) {
        output << "instance-create-failed"
               << ";protocol=" << kProbeProtocol
               << ";result=" << static_cast<int>(create_result);
        return JString(env, output.str());
    }

    uint32_t physical_device_count = 0;
    VkResult physical_result =
        vkEnumeratePhysicalDevices(
            instance,
            &physical_device_count,
            nullptr);
    if (
        physical_result != VK_SUCCESS ||
        physical_device_count == 0 ||
        physical_device_count > 64
    ) {
        output << "physical-device-enumeration-failed"
               << ";protocol=" << kProbeProtocol
               << ";result=" << static_cast<int>(physical_result)
               << ";count=" << physical_device_count;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    std::vector<VkPhysicalDevice> physical_devices(
        physical_device_count);
    physical_result =
        vkEnumeratePhysicalDevices(
            instance,
            &physical_device_count,
            physical_devices.data());
    if (physical_result != VK_SUCCESS) {
        output << "physical-device-read-failed"
               << ";protocol=" << kProbeProtocol
               << ";result=" << static_cast<int>(physical_result);
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    VkPhysicalDevice selected = physical_devices.front();
    for (const VkPhysicalDevice candidate : physical_devices) {
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            selected = candidate;
            break;
        }
    }

    VkPhysicalDeviceProperties selected_properties {};
    vkGetPhysicalDeviceProperties(
        selected,
        &selected_properties);

    uint32_t device_extension_count = 0;
    VkResult device_extension_result =
        vkEnumerateDeviceExtensionProperties(
            selected,
            nullptr,
            &device_extension_count,
            nullptr);
    if (
        device_extension_result != VK_SUCCESS ||
        device_extension_count > kMaximumEnumeratedExtensions
    ) {
        output << "device-extension-enumeration-failed"
               << ";protocol=" << kProbeProtocol
               << ";result=" << static_cast<int>(device_extension_result)
               << ";count=" << device_extension_count;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    std::vector<VkExtensionProperties> device_extensions(
        device_extension_count);
    if (device_extension_count > 0) {
        uint32_t count = device_extension_count;
        device_extension_result =
            vkEnumerateDeviceExtensionProperties(
                selected,
                nullptr,
                &count,
                device_extensions.data());
        if (
            device_extension_result != VK_SUCCESS &&
            device_extension_result != VK_INCOMPLETE
        ) {
            output << "device-extension-read-failed"
                   << ";protocol=" << kProbeProtocol
                   << ";result=" << static_cast<int>(device_extension_result);
            vkDestroyInstance(instance, nullptr);
            return JString(env, output.str());
        }
        device_extensions.resize(count);
    }

    output << "ok"
           << ";protocol=" << kProbeProtocol
           << ";vendor_id=" << selected_properties.vendorID
           << ";device_id=" << selected_properties.deviceID
           << ";instance_extensions=" << instance_extensions.size()
           << ";device_extensions=" << device_extensions.size();

    AppendFlag(
        output,
        "khr_surface",
        HasExtension(instance_extensions, "VK_KHR_surface"));
    AppendFlag(
        output,
        "khr_android_surface",
        HasExtension(instance_extensions, "VK_KHR_android_surface"));
    AppendFlag(
        output,
        "ext_headless_surface",
        HasExtension(instance_extensions, "VK_EXT_headless_surface"));
    AppendFlag(
        output,
        "khr_external_memory_capabilities",
        HasExtension(
            instance_extensions,
            "VK_KHR_external_memory_capabilities"));
    AppendFlag(
        output,
        "khr_swapchain",
        HasExtension(device_extensions, "VK_KHR_swapchain"));
    AppendFlag(
        output,
        "android_external_memory_ahb",
        HasExtension(
            device_extensions,
            "VK_ANDROID_external_memory_android_hardware_buffer"));
    AppendFlag(
        output,
        "khr_external_memory",
        HasExtension(device_extensions, "VK_KHR_external_memory"));
    AppendFlag(
        output,
        "khr_external_memory_fd",
        HasExtension(device_extensions, "VK_KHR_external_memory_fd"));
    AppendFlag(
        output,
        "khr_timeline_semaphore",
        HasExtension(device_extensions, "VK_KHR_timeline_semaphore"));
    AppendFlag(
        output,
        "khr_synchronization2",
        HasExtension(device_extensions, "VK_KHR_synchronization2"));

    vkDestroyInstance(instance, nullptr);
    return JString(env, output.str());
}
