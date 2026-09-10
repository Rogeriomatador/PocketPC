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
constexpr const char* kGetPhysicalDeviceProperties2Extension =
    "VK_KHR_get_physical_device_properties2";
constexpr const char* kExternalMemoryCapabilitiesExtension =
    "VK_KHR_external_memory_capabilities";

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

struct ExternalBufferSupport {
    bool queried = false;
    bool importable = false;
    bool exportable = false;
};

ExternalBufferSupport QueryExternalBufferSupport(
        PFN_vkGetPhysicalDeviceExternalBufferPropertiesKHR query,
        VkPhysicalDevice physical_device,
        VkExternalMemoryHandleTypeFlagBits handle_type) {
    ExternalBufferSupport support {};
    if (query == nullptr || physical_device == VK_NULL_HANDLE) {
        return support;
    }

    VkPhysicalDeviceExternalBufferInfo info {};
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO;
    info.flags = 0;
    info.usage =
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
        VK_BUFFER_USAGE_TRANSFER_DST_BIT |
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.handleType = handle_type;

    VkExternalBufferProperties properties {};
    properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES;
    query(physical_device, &info, &properties);

    support.queried = true;
    const VkExternalMemoryFeatureFlags features =
        properties.externalMemoryProperties.externalMemoryFeatures;
    support.importable =
        (features & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
    support.exportable =
        (features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
    return support;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanExternalResourceProbe_nativeProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    std::ostringstream output;
    output << "vulkan-external-resource=";

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

    const bool has_get_physical_device_properties2 =
        HasExtension(
            instance_extensions,
            kGetPhysicalDeviceProperties2Extension);
    const bool has_external_memory_capabilities =
        HasExtension(
            instance_extensions,
            kExternalMemoryCapabilitiesExtension);

    std::vector<const char*> enabled_instance_extensions;
    if (
        has_get_physical_device_properties2 &&
        has_external_memory_capabilities
    ) {
        enabled_instance_extensions.push_back(
            kGetPhysicalDeviceProperties2Extension);
        enabled_instance_extensions.push_back(
            kExternalMemoryCapabilitiesExtension);
    }

    VkApplicationInfo application_info {};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "PocketPC External Resource Probe";
    application_info.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.pEngineName = "PocketPC";
    application_info.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &application_info;
    create_info.enabledExtensionCount =
        static_cast<uint32_t>(enabled_instance_extensions.size());
    create_info.ppEnabledExtensionNames =
        enabled_instance_extensions.empty()
            ? nullptr
            : enabled_instance_extensions.data();

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult create_result =
        vkCreateInstance(&create_info, nullptr, &instance);
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
    vkGetPhysicalDeviceProperties(selected, &selected_properties);

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

    PFN_vkGetPhysicalDeviceExternalBufferPropertiesKHR
        query_external_buffer_properties = nullptr;
    if (
        enabled_instance_extensions.size() == 2u
    ) {
        query_external_buffer_properties =
            reinterpret_cast<
                PFN_vkGetPhysicalDeviceExternalBufferPropertiesKHR>(
                vkGetInstanceProcAddr(
                    instance,
                    "vkGetPhysicalDeviceExternalBufferPropertiesKHR"));
    }

    ExternalBufferSupport opaque_fd {};
    ExternalBufferSupport ahb {};
    ExternalBufferSupport dma_buf {};

    if (query_external_buffer_properties != nullptr) {
        opaque_fd =
            QueryExternalBufferSupport(
                query_external_buffer_properties,
                selected,
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);

#ifdef VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID
        ahb =
            QueryExternalBufferSupport(
                query_external_buffer_properties,
                selected,
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
#endif

#ifdef VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
        dma_buf =
            QueryExternalBufferSupport(
                query_external_buffer_properties,
                selected,
                VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
#endif
    }

    output << "ok"
           << ";protocol=" << kProbeProtocol
           << ";vendor_id=" << selected_properties.vendorID
           << ";device_id=" << selected_properties.deviceID
           << ";instance_extensions=" << instance_extensions.size()
           << ";device_extensions=" << device_extensions.size();

    AppendFlag(
        output,
        "khr_external_memory_capabilities",
        has_external_memory_capabilities);
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
        "ext_external_memory_dma_buf",
        HasExtension(device_extensions, "VK_EXT_external_memory_dma_buf"));
    AppendFlag(
        output,
        "android_external_memory_ahb",
        HasExtension(
            device_extensions,
            "VK_ANDROID_external_memory_android_hardware_buffer"));
    AppendFlag(
        output,
        "khr_external_semaphore",
        HasExtension(device_extensions, "VK_KHR_external_semaphore"));
    AppendFlag(
        output,
        "khr_external_semaphore_fd",
        HasExtension(device_extensions, "VK_KHR_external_semaphore_fd"));
    AppendFlag(
        output,
        "khr_external_fence",
        HasExtension(device_extensions, "VK_KHR_external_fence"));
    AppendFlag(
        output,
        "khr_external_fence_fd",
        HasExtension(device_extensions, "VK_KHR_external_fence_fd"));
    AppendFlag(
        output,
        "query_external_buffer_properties",
        query_external_buffer_properties != nullptr);
    AppendFlag(output, "opaque_fd_queried", opaque_fd.queried);
    AppendFlag(output, "opaque_fd_importable", opaque_fd.importable);
    AppendFlag(output, "opaque_fd_exportable", opaque_fd.exportable);
    AppendFlag(output, "ahb_queried", ahb.queried);
    AppendFlag(output, "ahb_importable", ahb.importable);
    AppendFlag(output, "ahb_exportable", ahb.exportable);
    AppendFlag(output, "dma_buf_queried", dma_buf.queried);
    AppendFlag(output, "dma_buf_importable", dma_buf.importable);
    AppendFlag(output, "dma_buf_exportable", dma_buf.exportable);

    vkDestroyInstance(instance, nullptr);
    return JString(env, output.str());
}
