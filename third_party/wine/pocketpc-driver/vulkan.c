#if 0
#pragma makedep unix
#endif

#include "config.h"

#include "ntstatus.h"
#define WIN32_NO_STATUS

#include "pocketpcdrv.h"
#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static VkResult pocketpc_vulkan_surface_create(
    HWND hwnd,
    const struct vulkan_instance *instance,
    VkSurfaceKHR *handle,
    struct client_surface **client_surface
) {
    (void)hwnd;
    (void)instance;

    if (handle)
        *handle = VK_NULL_HANDLE;
    if (client_surface)
        *client_surface = NULL;

    FIXME(
        "POCKETPC_VULKAN_WSI stage=surface_create_blocked version=%u reason=surface_transport_not_implemented\n",
        WINE_VULKAN_DRIVER_VERSION
    );

    return VK_ERROR_INCOMPATIBLE_DRIVER;
}

static VkBool32 pocketpc_get_physical_device_presentation_support(
    struct vulkan_physical_device *physical_device,
    uint32_t queue_family
) {
    (void)physical_device;
    (void)queue_family;

    TRACE(
        "POCKETPC_VULKAN_WSI stage=presentation_support_blocked version=%u\n",
        WINE_VULKAN_DRIVER_VERSION
    );
    return VK_FALSE;
}

static void pocketpc_map_instance_extensions(
    struct vulkan_instance_extensions *extensions
) {
    /*
     * Do not advertise a host surface route until PocketPC can create a
     * process-valid native Vulkan surface for the Wine guest. In particular,
     * never translate VK_KHR_win32_surface directly to VK_KHR_android_surface
     * without a valid ANativeWindow in this process.
     */
    (void)extensions;
}

static void pocketpc_map_device_extensions(
    struct vulkan_device_extensions *extensions
) {
    /*
     * External-memory mappings will be added only with an implementation that
     * preserves handle ownership and synchronization across Box64/Android.
     */
    (void)extensions;
}

static const struct vulkan_driver_funcs pocketpc_vulkan_driver_funcs =
{
    .p_vulkan_surface_create = pocketpc_vulkan_surface_create,
    .p_get_physical_device_presentation_support =
        pocketpc_get_physical_device_presentation_support,
    .p_map_instance_extensions = pocketpc_map_instance_extensions,
    .p_map_device_extensions = pocketpc_map_device_extensions,
};

UINT POCKETPC_VulkanInit(
    UINT version,
    void *vulkan_handle,
    const struct vulkan_driver_funcs **driver_funcs
) {
    if (
        version != WINE_VULKAN_DRIVER_VERSION ||
        !vulkan_handle ||
        !driver_funcs
    ) {
        ERR(
            "POCKETPC_VULKAN_WSI stage=abi_init_rejected requested=%u expected=%u handle=%p funcs=%p\n",
            version,
            WINE_VULKAN_DRIVER_VERSION,
            vulkan_handle,
            driver_funcs
        );
        return STATUS_INVALID_PARAMETER;
    }

    *driver_funcs = &pocketpc_vulkan_driver_funcs;

    TRACE(
        "POCKETPC_VULKAN_WSI stage=abi_initialized version=%u surface_backend=blocked\n",
        WINE_VULKAN_DRIVER_VERSION
    );

    return STATUS_SUCCESS;
}
