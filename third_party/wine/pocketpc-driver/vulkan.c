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
     * Surface extension mapping remains deliberately disabled. A Win32 surface
     * must never be advertised until PocketPC can create a real host
     * VkSurfaceKHR with process-valid native presentation state.
     */
    (void)extensions;
}

static void pocketpc_map_device_extensions(
    struct vulkan_device_extensions *extensions
) {
    if (!extensions)
        return;

    /*
     * Wine's Linux graphics drivers translate Win32 external-handle
     * extensions to the host fd variants. This mapping is independent of WSI
     * surface creation and allows Wine's Vulkan core to use its normal fd
     * external-memory/semaphore/fence paths when the host driver supports
     * them. It does NOT prove that PocketPC's guest graphics broker has
     * transported or imported any particular resource.
     */
    if (extensions->has_VK_KHR_external_memory_win32)
        extensions->has_VK_KHR_external_memory_fd = 1;
    if (extensions->has_VK_KHR_external_memory_fd)
        extensions->has_VK_KHR_external_memory_win32 = 1;

    if (extensions->has_VK_KHR_external_semaphore_win32)
        extensions->has_VK_KHR_external_semaphore_fd = 1;
    if (extensions->has_VK_KHR_external_semaphore_fd)
        extensions->has_VK_KHR_external_semaphore_win32 = 1;

    if (extensions->has_VK_KHR_external_fence_win32)
        extensions->has_VK_KHR_external_fence_fd = 1;
    if (extensions->has_VK_KHR_external_fence_fd)
        extensions->has_VK_KHR_external_fence_win32 = 1;

    TRACE(
        "POCKETPC_VULKAN_WSI stage=external_handle_extension_mapping_ready version=%u surface_mapping=blocked\n",
        WINE_VULKAN_DRIVER_VERSION
    );
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
        "POCKETPC_VULKAN_WSI stage=abi_initialized version=%u surface_backend=blocked external_handle_mapping=ready\n",
        WINE_VULKAN_DRIVER_VERSION
    );

    return STATUS_SUCCESS;
}
