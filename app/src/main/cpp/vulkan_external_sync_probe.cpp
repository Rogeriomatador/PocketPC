#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr uint32_t kProtocol = 1;
constexpr uint32_t kMaximumExtensions = 4096;

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

void AppendFlag(std::ostringstream& out, const char* key, bool value) {
    out << ';' << key << '=' << (value ? "yes" : "no");
}

bool EnumerateInstanceExtensions(std::vector<VkExtensionProperties>* out) {
    if (!out) return false;
    uint32_t count = 0;
    VkResult result = vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
    if (result != VK_SUCCESS || count > kMaximumExtensions) return false;
    out->assign(count, VkExtensionProperties {});
    if (count == 0) return true;
    result = vkEnumerateInstanceExtensionProperties(nullptr, &count, out->data());
    if (result != VK_SUCCESS) {
        out->clear();
        return false;
    }
    out->resize(count);
    return true;
}

bool EnumerateDeviceExtensions(
        VkPhysicalDevice physical,
        std::vector<VkExtensionProperties>* out) {
    if (!out) return false;
    uint32_t count = 0;
    VkResult result = vkEnumerateDeviceExtensionProperties(
        physical, nullptr, &count, nullptr);
    if (result != VK_SUCCESS || count > kMaximumExtensions) return false;
    out->assign(count, VkExtensionProperties {});
    if (count == 0) return true;
    result = vkEnumerateDeviceExtensionProperties(
        physical, nullptr, &count, out->data());
    if (result != VK_SUCCESS) {
        out->clear();
        return false;
    }
    out->resize(count);
    return true;
}

uint32_t LoaderApiVersion() {
    auto enumerate = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    if (!enumerate) return VK_API_VERSION_1_0;
    uint32_t version = VK_API_VERSION_1_0;
    return enumerate(&version) == VK_SUCCESS ? version : VK_API_VERSION_1_0;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalSyncProbe_nativeProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    std::ostringstream output;
    output << "vulkan-external-sync=";

    std::vector<VkExtensionProperties> instance_extensions;
    if (!EnumerateInstanceExtensions(&instance_extensions)) {
        output << "instance-extension-enumeration-failed;protocol=" << kProtocol;
        return JString(env, output.str());
    }

    const uint32_t loader_api = LoaderApiVersion();
    const bool loader_11_or_newer =
        VK_VERSION_MAJOR(loader_api) > 1 ||
        (VK_VERSION_MAJOR(loader_api) == 1 && VK_VERSION_MINOR(loader_api) >= 1);
    const bool has_external_semaphore_capabilities =
        HasExtension(instance_extensions, VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME);
    const bool has_properties2 =
        HasExtension(instance_extensions, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

    std::vector<const char*> enabled_instance_extensions;
    if (!loader_11_or_newer) {
        if (!has_external_semaphore_capabilities || !has_properties2) {
            output << "loader-api-too-old;protocol=" << kProtocol
                   << ";api_major=" << VK_VERSION_MAJOR(loader_api)
                   << ";api_minor=" << VK_VERSION_MINOR(loader_api);
            return JString(env, output.str());
        }
        enabled_instance_extensions.push_back(
            VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);
        enabled_instance_extensions.push_back(
            VK_KHR_EXTERNAL_SEMAPHORE_CAPABILITIES_EXTENSION_NAME);
    }

    VkApplicationInfo app {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "PocketPC External Sync Probe";
    app.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    app.pEngineName = "PocketPC";
    app.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    app.apiVersion = loader_11_or_newer ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;

    VkInstanceCreateInfo instance_info {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    instance_info.enabledExtensionCount =
        static_cast<uint32_t>(enabled_instance_extensions.size());
    instance_info.ppEnabledExtensionNames =
        enabled_instance_extensions.empty() ? nullptr : enabled_instance_extensions.data();

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&instance_info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        output << "instance-create-failed;protocol=" << kProtocol
               << ";result=" << static_cast<int>(result);
        return JString(env, output.str());
    }

    uint32_t physical_count = 0;
    result = vkEnumeratePhysicalDevices(instance, &physical_count, nullptr);
    if (result != VK_SUCCESS || physical_count == 0 || physical_count > 64) {
        output << "physical-device-enumeration-failed;protocol=" << kProtocol
               << ";result=" << static_cast<int>(result)
               << ";count=" << physical_count;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    std::vector<VkPhysicalDevice> physicals(physical_count);
    result = vkEnumeratePhysicalDevices(instance, &physical_count, physicals.data());
    if (result != VK_SUCCESS) {
        output << "physical-device-read-failed;protocol=" << kProtocol
               << ";result=" << static_cast<int>(result);
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    VkPhysicalDevice physical = physicals.front();
    for (VkPhysicalDevice candidate : physicals) {
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.deviceType != VK_PHYSICAL_DEVICE_TYPE_CPU) {
            physical = candidate;
            break;
        }
    }

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(physical, &properties);

    std::vector<VkExtensionProperties> device_extensions;
    if (!EnumerateDeviceExtensions(physical, &device_extensions)) {
        output << "device-extension-enumeration-failed;protocol=" << kProtocol;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    const bool external_semaphore_fd_extension =
        HasExtension(device_extensions, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    const bool timeline_extension =
        HasExtension(device_extensions, VK_KHR_TIMELINE_SEMAPHORE_EXTENSION_NAME);

    PFN_vkGetPhysicalDeviceExternalSemaphoreProperties query =
        reinterpret_cast<PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceExternalSemaphoreProperties"));
    if (!query) {
        query = reinterpret_cast<PFN_vkGetPhysicalDeviceExternalSemaphoreProperties>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceExternalSemaphorePropertiesKHR"));
    }

    VkExternalSemaphoreProperties semaphore_properties {};
    semaphore_properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
    bool semaphore_queried = false;
    bool semaphore_importable = false;
    bool semaphore_exportable = false;
    bool semaphore_compatible = false;

    if (query) {
        VkPhysicalDeviceExternalSemaphoreInfo semaphore_info {};
        semaphore_info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
        semaphore_info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT;
        query(physical, &semaphore_info, &semaphore_properties);
        semaphore_queried = true;
        semaphore_importable =
            (semaphore_properties.externalSemaphoreFeatures &
                VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
        semaphore_exportable =
            (semaphore_properties.externalSemaphoreFeatures &
                VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
        semaphore_compatible =
            (semaphore_properties.compatibleHandleTypes &
                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT) != 0;
    }

    PFN_vkGetPhysicalDeviceFeatures2 get_features2 =
        reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2"));
    if (!get_features2) {
        get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }

    VkPhysicalDeviceTimelineSemaphoreFeatures timeline_features {};
    timeline_features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    VkPhysicalDeviceFeatures2 features2 {};
    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features2.pNext = &timeline_features;
    bool timeline_queried = false;
    bool timeline_supported = false;
    if (get_features2) {
        get_features2(physical, &features2);
        timeline_queried = true;
        timeline_supported = timeline_features.timelineSemaphore == VK_TRUE;
    }

    output << "ok;protocol=" << kProtocol
           << ";api_major=" << VK_VERSION_MAJOR(properties.apiVersion)
           << ";api_minor=" << VK_VERSION_MINOR(properties.apiVersion)
           << ";vendor_id=" << properties.vendorID
           << ";device_id=" << properties.deviceID;
    AppendFlag(output, "external_semaphore_fd_extension", external_semaphore_fd_extension);
    AppendFlag(output, "external_semaphore_query_function", query != nullptr);
    AppendFlag(output, "opaque_fd_semaphore_queried", semaphore_queried);
    AppendFlag(output, "opaque_fd_semaphore_importable", semaphore_importable);
    AppendFlag(output, "opaque_fd_semaphore_exportable", semaphore_exportable);
    AppendFlag(output, "opaque_fd_semaphore_compatible", semaphore_compatible);
    AppendFlag(output, "timeline_extension", timeline_extension);
    AppendFlag(output, "timeline_feature_queried", timeline_queried);
    AppendFlag(output, "timeline_supported", timeline_supported);
    AppendFlag(
        output,
        "pvs1_candidate",
        external_semaphore_fd_extension &&
            semaphore_queried &&
            semaphore_importable &&
            semaphore_exportable &&
            semaphore_compatible &&
            timeline_queried &&
            timeline_supported);

    vkDestroyInstance(instance, nullptr);
    return JString(env, output.str());
}
