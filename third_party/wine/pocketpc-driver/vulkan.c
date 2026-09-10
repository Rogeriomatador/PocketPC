#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdlib.h>
#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS

#include "pocketpcdrv.h"
#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static BOOL pocketpc_headless_diagnostic_enabled(void)
{
    const char *value = getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC");
    return value && !strcmp(value, "1");
}

static void pocketpc_headless_client_surface_destroy(
    struct client_surface *surface
) {
    (void)surface;
}

static void pocketpc_headless_client_surface_detach(
    struct client_surface *surface
) {
    (void)surface;
}

static void pocketpc_headless_client_surface_update(
    struct client_surface *surface
) {
    (void)surface;
}

static void pocketpc_headless_client_surface_present(
    struct client_surface *surface,
    HDC hdc
) {
    (void)surface;
    (void)hdc;
}

static const struct client_surface_funcs pocketpc_headless_client_surface_funcs =
{
    .destroy = pocketpc_headless_client_surface_destroy,
    .detach = pocketpc_headless_client_surface_detach,
    .update = pocketpc_headless_client_surface_update,
    .present = pocketpc_headless_client_surface_present,
};

static VkResult pocketpc_vulkan_surface_create(
    HWND hwnd,
    const struct vulkan_instance *instance,
    VkSurfaceKHR *handle,
    struct client_surface **client_surface
) {
    VkHeadlessSurfaceCreateInfoEXT create_info = {
        .sType = VK_STRUCTURE_TYPE_HEADLESS_SURFACE_CREATE_INFO_EXT,
    };
    VkResult result;

    if (handle)
        *handle = VK_NULL_HANDLE;
    if (client_surface)
        *client_surface = NULL;

    if (!pocketpc_headless_diagnostic_enabled())
    {
        FIXME(
            "POCKETPC_VULKAN_WSI stage=surface_create_blocked version=%u reason=visible_surface_transport_not_implemented\n",
            WINE_VULKAN_DRIVER_VERSION
        );
        return VK_ERROR_INCOMPATIBLE_DRIVER;
    }

    if (
        !instance ||
        !handle ||
        !client_surface ||
        !instance->p_vkCreateHeadlessSurfaceEXT
    ) {
        ERR(
            "POCKETPC_VULKAN_WSI stage=headless_surface_rejected reason=missing_argument_or_extension\n"
        );
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    *client_surface = client_surface_create(
        sizeof(struct client_surface),
        &pocketpc_headless_client_surface_funcs,
        hwnd
    );
    if (!*client_surface)
        return VK_ERROR_OUT_OF_HOST_MEMORY;

    result = instance->p_vkCreateHeadlessSurfaceEXT(
        instance->host.instance,
        &create_info,
        NULL,
        handle
    );
    if (result != VK_SUCCESS)
    {
        client_surface_release(*client_surface);
        *client_surface = NULL;
        *handle = VK_NULL_HANDLE;
        ERR(
            "POCKETPC_VULKAN_WSI stage=headless_surface_create_failed result=%d\n",
            result
        );
        return result;
    }

    TRACE(
        "POCKETPC_VULKAN_WSI stage=headless_surface_created diagnostic_only=1 visible_present=0 surface=0x%s\n",
        wine_dbgstr_longlong(*handle)
    );
    return VK_SUCCESS;
}

static VkBool32 pocketpc_get_physical_device_presentation_support(
    struct vulkan_physical_device *physical_device,
    uint32_t queue_family
) {
    (void)physical_device;
    (void)queue_family;

    if (pocketpc_headless_diagnostic_enabled())
    {
        TRACE(
            "POCKETPC_VULKAN_WSI stage=headless_presentation_support diagnostic_only=1 visible_present=0\n"
        );
        return VK_TRUE;
    }

    TRACE(
        "POCKETPC_VULKAN_WSI stage=presentation_support_blocked version=%u\n",
        WINE_VULKAN_DRIVER_VERSION
    );
    return VK_FALSE;
}

static void pocketpc_map_instance_extensions(
    struct vulkan_instance_extensions *extensions
) {
    if (!extensions || !pocketpc_headless_diagnostic_enabled())
        return;

    /*
     * Diagnostic-only mapping follows Wine's nulldrv model. It creates an
     * actual host VkSurfaceKHR backed by VK_EXT_headless_surface, but it is not
     * a visible Android presentation path and must never be promoted as one.
     */
    if (extensions->has_VK_KHR_win32_surface)
        extensions->has_VK_EXT_headless_surface = 1;
    if (extensions->has_VK_EXT_headless_surface)
        extensions->has_VK_KHR_win32_surface = 1;
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
        "POCKETPC_VULKAN_WSI stage=external_handle_extension_mapping_ready version=%u visible_surface_mapping=blocked\n",
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
        "POCKETPC_VULKAN_WSI stage=abi_initialized version=%u visible_surface_backend=blocked headless_diagnostic=%u external_handle_mapping=ready\n",
        WINE_VULKAN_DRIVER_VERSION,
        pocketpc_headless_diagnostic_enabled()
    );

    return STATUS_SUCCESS;
}
