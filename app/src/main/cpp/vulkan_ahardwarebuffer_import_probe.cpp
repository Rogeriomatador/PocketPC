#include <jni.h>

#include <android/hardware_buffer.h>

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr uint32_t kProtocolVersion = 1;
constexpr uint32_t kMaxExtensions = 4096;
constexpr uint32_t kMaxPhysicalDevices = 64;
constexpr uint32_t kProbeWidth = 64;
constexpr uint32_t kProbeHeight = 64;
constexpr const char* kAhbExtension =
    "VK_ANDROID_external_memory_android_hardware_buffer";
constexpr const char* kForeignQueueExtension =
    "VK_EXT_queue_family_foreign";

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

bool EnumerateDeviceExtensions(
        VkPhysicalDevice physical_device,
        std::vector<VkExtensionProperties>* extensions) {
    if (extensions == nullptr) {
        return false;
    }

    uint32_t count = 0;
    VkResult result =
        vkEnumerateDeviceExtensionProperties(
            physical_device,
            nullptr,
            &count,
            nullptr);
    if (result != VK_SUCCESS || count > kMaxExtensions) {
        return false;
    }

    extensions->assign(count, VkExtensionProperties {});
    if (count == 0) {
        return true;
    }

    result =
        vkEnumerateDeviceExtensionProperties(
            physical_device,
            nullptr,
            &count,
            extensions->data());
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        extensions->clear();
        return false;
    }
    extensions->resize(count);
    return true;
}

bool PickQueueFamily(
        VkPhysicalDevice physical_device,
        uint32_t* queue_family_index) {
    if (queue_family_index == nullptr) {
        return false;
    }

    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(
        physical_device,
        &count,
        nullptr);
    if (count == 0 || count > 256) {
        return false;
    }

    std::vector<VkQueueFamilyProperties> properties(count);
    vkGetPhysicalDeviceQueueFamilyProperties(
        physical_device,
        &count,
        properties.data());
    for (uint32_t index = 0; index < count; ++index) {
        if (properties[index].queueCount > 0) {
            *queue_family_index = index;
            return true;
        }
    }
    return false;
}

}  // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_pocketpc_core_runtime_VulkanAhardwareBufferImportProbe_nativeProbe(
        JNIEnv* env,
        jobject /* thiz */) {
    std::ostringstream output;
    output << "vulkan-ahb-import=";

    VkApplicationInfo application_info {};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "PocketPC AHB Import Probe";
    application_info.applicationVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.pEngineName = "PocketPC";
    application_info.engineVersion = VK_MAKE_VERSION(0, 1, 0);
    application_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo instance_create_info {};
    instance_create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_create_info.pApplicationInfo = &application_info;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result =
        vkCreateInstance(
            &instance_create_info,
            nullptr,
            &instance);
    if (result != VK_SUCCESS) {
        output << "instance-create-failed"
               << ";protocol=" << kProtocolVersion
               << ";result=" << static_cast<int>(result);
        return JString(env, output.str());
    }

    uint32_t physical_device_count = 0;
    result =
        vkEnumeratePhysicalDevices(
            instance,
            &physical_device_count,
            nullptr);
    if (
        result != VK_SUCCESS ||
        physical_device_count == 0 ||
        physical_device_count > kMaxPhysicalDevices
    ) {
        output << "physical-device-enumeration-failed"
               << ";protocol=" << kProtocolVersion
               << ";result=" << static_cast<int>(result)
               << ";count=" << physical_device_count;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    std::vector<VkPhysicalDevice> physical_devices(
        physical_device_count);
    result =
        vkEnumeratePhysicalDevices(
            instance,
            &physical_device_count,
            physical_devices.data());
    if (result != VK_SUCCESS) {
        output << "physical-device-read-failed"
               << ";protocol=" << kProtocolVersion
               << ";result=" << static_cast<int>(result);
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

    VkPhysicalDeviceProperties properties {};
    vkGetPhysicalDeviceProperties(selected, &properties);

    std::vector<VkExtensionProperties> extensions;
    if (!EnumerateDeviceExtensions(selected, &extensions)) {
        output << "device-extension-enumeration-failed"
               << ";protocol=" << kProtocolVersion;
        vkDestroyInstance(instance, nullptr);
        return JString(env, output.str());
    }

    const bool vulkan_11_or_newer =
        VK_API_VERSION_MAJOR(properties.apiVersion) > 1 ||
        (
            VK_API_VERSION_MAJOR(properties.apiVersion) == 1 &&
            VK_API_VERSION_MINOR(properties.apiVersion) >= 1
        );
    const bool ahb_extension =
        HasExtension(extensions, kAhbExtension);
    const bool foreign_queue_extension =
        HasExtension(extensions, kForeignQueueExtension);

    bool queue_family_available = false;
    uint32_t queue_family_index = 0;
    queue_family_available =
        PickQueueFamily(selected, &queue_family_index);

    bool device_created = false;
    bool query_function_available = false;
    bool ahb_allocated = false;
    bool properties_query_succeeded = false;
    bool memory_type_bits_nonzero = false;
    bool allocation_size_nonzero = false;
    uint64_t allocation_size = 0;
    uint32_t memory_type_bits = 0;
    int ahb_allocate_result = 0;
    int property_query_result =
        static_cast<int>(VK_ERROR_INITIALIZATION_FAILED);

    VkDevice device = VK_NULL_HANDLE;
    AHardwareBuffer* hardware_buffer = nullptr;

    if (
        vulkan_11_or_newer &&
        ahb_extension &&
        foreign_queue_extension &&
        queue_family_available
    ) {
        const float priority = 1.0f;
        VkDeviceQueueCreateInfo queue_create_info {};
        queue_create_info.sType =
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        queue_create_info.queueFamilyIndex = queue_family_index;
        queue_create_info.queueCount = 1;
        queue_create_info.pQueuePriorities = &priority;

        const char* enabled_extensions[] = {
            kAhbExtension,
            kForeignQueueExtension,
        };

        VkDeviceCreateInfo device_create_info {};
        device_create_info.sType =
            VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        device_create_info.queueCreateInfoCount = 1;
        device_create_info.pQueueCreateInfos = &queue_create_info;
        device_create_info.enabledExtensionCount = 2;
        device_create_info.ppEnabledExtensionNames =
            enabled_extensions;

        result =
            vkCreateDevice(
                selected,
                &device_create_info,
                nullptr,
                &device);
        device_created = result == VK_SUCCESS;
    }

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID query = nullptr;
    if (device_created) {
        query =
            reinterpret_cast<
                PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
                vkGetDeviceProcAddr(
                    device,
                    "vkGetAndroidHardwareBufferPropertiesANDROID"));
        query_function_available = query != nullptr;
    }

    if (query_function_available) {
        AHardwareBuffer_Desc descriptor {};
        descriptor.width = kProbeWidth;
        descriptor.height = kProbeHeight;
        descriptor.layers = 1;
        descriptor.format =
            AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        descriptor.usage =
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
            AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

        ahb_allocate_result =
            AHardwareBuffer_allocate(
                &descriptor,
                &hardware_buffer);
        ahb_allocated =
            ahb_allocate_result == 0 &&
            hardware_buffer != nullptr;
    }

    if (ahb_allocated) {
        VkAndroidHardwareBufferPropertiesANDROID ahb_properties {};
        ahb_properties.sType =
            VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;

        const VkResult query_result =
            query(
                device,
                hardware_buffer,
                &ahb_properties);
        property_query_result = static_cast<int>(query_result);
        properties_query_succeeded = query_result == VK_SUCCESS;
        if (properties_query_succeeded) {
            allocation_size =
                static_cast<uint64_t>(ahb_properties.allocationSize);
            memory_type_bits = ahb_properties.memoryTypeBits;
            allocation_size_nonzero = allocation_size != 0u;
            memory_type_bits_nonzero = memory_type_bits != 0u;
        }
    }

    if (hardware_buffer != nullptr) {
        AHardwareBuffer_release(hardware_buffer);
    }
    if (device != VK_NULL_HANDLE) {
        vkDestroyDevice(device, nullptr);
    }

    output << "ok"
           << ";protocol=" << kProtocolVersion
           << ";api_major=" << VK_API_VERSION_MAJOR(properties.apiVersion)
           << ";api_minor=" << VK_API_VERSION_MINOR(properties.apiVersion)
           << ";vendor_id=" << properties.vendorID
           << ";device_id=" << properties.deviceID
           << ";allocation_size=" << allocation_size
           << ";memory_type_bits=" << memory_type_bits
           << ";ahb_allocate_result=" << ahb_allocate_result
           << ";property_query_result=" << property_query_result;
    AppendFlag(output, "vulkan_1_1_or_newer", vulkan_11_or_newer);
    AppendFlag(output, "ahb_extension", ahb_extension);
    AppendFlag(output, "foreign_queue_extension", foreign_queue_extension);
    AppendFlag(output, "queue_family_available", queue_family_available);
    AppendFlag(output, "device_created", device_created);
    AppendFlag(output, "query_function_available", query_function_available);
    AppendFlag(output, "ahb_allocated", ahb_allocated);
    AppendFlag(output, "properties_query_succeeded", properties_query_succeeded);
    AppendFlag(output, "allocation_size_nonzero", allocation_size_nonzero);
    AppendFlag(output, "memory_type_bits_nonzero", memory_type_bits_nonzero);
    AppendFlag(
        output,
        "canonical_import_query_supported",
        properties_query_succeeded &&
            allocation_size_nonzero &&
            memory_type_bits_nonzero);

    vkDestroyInstance(instance, nullptr);
    return JString(env, output.str());
}
