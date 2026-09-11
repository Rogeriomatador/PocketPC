#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS

#include "pocketpcdrv.h"
#include "pocketpc_graphics_session_client.h"
#include "pocketpc_graphics_transport.h"
#include "pocketpc_external_image_fd_protocol.h"
#include "pocketpc_guest_vulkan_import.h"
#include "pocketpc_external_timeline_semaphore_fd_protocol.h"
#include "pocketpc_guest_vulkan_timeline_semaphore.h"
#include "wine/vulkan.h"
#include "wine/vulkan_driver.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static LONG pocketpc_headless_present_count;

struct pocketpc_vulkan_guest_resource_state
{
    pthread_mutex_t mutex;
    pthread_t worker;
    BOOL worker_started;
    BOOL stopping;
    struct vulkan_device *device;
    struct pocketpc_graphics_session session;
    struct pgt_resource_descriptor descriptor;
    struct pgt_ownership_record ownership;
    struct pocketpc_guest_vulkan_image image;
    struct pocketpc_guest_vulkan_timeline timeline;
    BOOL image_imported;
    BOOL timeline_imported;
};

static struct pocketpc_vulkan_guest_resource_state pocketpc_guest_resource =
{
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .session = {.fd = -1},
};

static BOOL pocketpc_headless_diagnostic_enabled(void)
{
    const char *value = getenv("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC");
    return value && !strcmp(value, "1");
}

static BOOL pocketpc_graphics_session_requested(void)
{
    const char *protocol = getenv("POCKETPC_GRAPHICS_SESSION_PROTOCOL");
    return protocol && !strcmp(protocol, "1");
}

static void pocketpc_guest_resource_reset_records(void)
{
    memset(&pocketpc_guest_resource.descriptor, 0, sizeof(pocketpc_guest_resource.descriptor));
    memset(&pocketpc_guest_resource.ownership, 0, sizeof(pocketpc_guest_resource.ownership));
    memset(&pocketpc_guest_resource.image, 0, sizeof(pocketpc_guest_resource.image));
    memset(&pocketpc_guest_resource.timeline, 0, sizeof(pocketpc_guest_resource.timeline));
    pocketpc_guest_resource.image.image = VK_NULL_HANDLE;
    pocketpc_guest_resource.image.memory = VK_NULL_HANDLE;
    pocketpc_guest_resource.timeline.semaphore = VK_NULL_HANDLE;
    pocketpc_guest_resource.image_imported = FALSE;
    pocketpc_guest_resource.timeline_imported = FALSE;
}

static void pocketpc_guest_resource_release_imports_locked(struct vulkan_device *device)
{
    if (!device || pocketpc_guest_resource.device != device)
        return;

    if (pocketpc_guest_resource.timeline_imported)
        pocketpc_guest_vulkan_timeline_release(device, &pocketpc_guest_resource.timeline);
    if (pocketpc_guest_resource.image_imported)
        pocketpc_guest_vulkan_import_release(device, &pocketpc_guest_resource.image);

    pocketpc_guest_resource_reset_records();
}

static void *pocketpc_vulkan_guest_resource_worker(void *argument)
{
    struct vulkan_device *device = argument;
    struct pgt_resource_descriptor descriptor;
    struct pgt_ownership_record ownership;
    struct pocketpc_external_image_fd_received image_received;
    struct pocketpc_external_timeline_semaphore_fd_received timeline_received;
    struct pocketpc_guest_vulkan_image image;
    struct pocketpc_guest_vulkan_timeline timeline;
    BOOL image_imported = FALSE;
    BOOL timeline_imported = FALSE;
    int session_fd = -1;
    int result;

    memset(&descriptor, 0, sizeof(descriptor));
    memset(&ownership, 0, sizeof(ownership));
    memset(&image_received, 0, sizeof(image_received));
    image_received.resource_fd = -1;
    memset(&timeline_received, 0, sizeof(timeline_received));
    timeline_received.semaphore_fd = -1;
    memset(&image, 0, sizeof(image));
    image.image = VK_NULL_HANDLE;
    image.memory = VK_NULL_HANDLE;
    memset(&timeline, 0, sizeof(timeline));
    timeline.semaphore = VK_NULL_HANDLE;

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device == device && !pocketpc_guest_resource.stopping)
        session_fd = pocketpc_guest_resource.session.fd;
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);

    if (session_fd < 0)
        goto failed;

    result = pgt_receive_resource_offer(session_fd, &descriptor, &ownership);
    if (result != 0)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pgt_offer_receive_failed result=%d\n", result);
        goto failed;
    }

    result = pocketpc_external_image_fd_receive(
        session_fd, &descriptor, &ownership, &image_received);
    if (result != POCKETPC_EXTERNAL_IMAGE_FD_OK)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pvi1_receive_failed result=%d\n", result);
        goto failed;
    }

    result = pocketpc_guest_vulkan_import_external_image(device, &image_received, &image);
    pocketpc_external_image_fd_received_release(&image_received);
    if (result != POCKETPC_GUEST_VULKAN_IMPORT_OK)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pvi1_import_failed result=%d\n", result);
        goto failed;
    }
    image_imported = TRUE;

    result = pocketpc_external_timeline_semaphore_fd_receive(
        session_fd, &descriptor, &ownership, &timeline_received);
    if (result != POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_OK)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pvs1_receive_failed result=%d\n", result);
        goto failed;
    }

    result = pocketpc_guest_vulkan_timeline_import(device, &timeline_received, &timeline);
    pocketpc_external_timeline_semaphore_fd_received_release(&timeline_received);
    if (result != POCKETPC_GUEST_VULKAN_TIMELINE_OK)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pvs1_import_failed result=%d\n", result);
        goto failed;
    }
    timeline_imported = TRUE;

    if (image.resource_id != descriptor.resource_id ||
        image.generation != descriptor.generation ||
        timeline.resource_id != descriptor.resource_id ||
        timeline.generation != descriptor.generation)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=import_identity_mismatch\n");
        goto failed;
    }

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device != device || pocketpc_guest_resource.stopping)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        goto failed;
    }

    pocketpc_guest_resource.descriptor = descriptor;
    pocketpc_guest_resource.ownership = ownership;
    pocketpc_guest_resource.image = image;
    pocketpc_guest_resource.timeline = timeline;
    pocketpc_guest_resource.image_imported = TRUE;
    pocketpc_guest_resource.timeline_imported = TRUE;
    image_imported = FALSE;
    timeline_imported = FALSE;

    TRACE("POCKETPC_VULKAN_GUEST stage=resource_import_primitives_completed runtime_evidence=0 gpu_sync=0 visible_present=0 resource=%s generation=%s sequence=%s\n",
          wine_dbgstr_longlong(descriptor.resource_id),
          wine_dbgstr_longlong(descriptor.generation),
          wine_dbgstr_longlong(ownership.sequence));
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
    return NULL;

failed:
    pocketpc_external_image_fd_received_release(&image_received);
    pocketpc_external_timeline_semaphore_fd_received_release(&timeline_received);
    if (timeline_imported)
        pocketpc_guest_vulkan_timeline_release(device, &timeline);
    if (image_imported)
        pocketpc_guest_vulkan_import_release(device, &image);

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device == device && !pocketpc_guest_resource.stopping)
        pocketpc_graphics_session_close(&pocketpc_guest_resource.session);
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
    return NULL;
}

static void pocketpc_vulkan_device_created(struct vulkan_device *device)
{
    char session_error[128] = {0};
    int result;

    if (!device || !pocketpc_graphics_session_requested())
        return;

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);

    if (pocketpc_guest_resource.device)
    {
        WARN("POCKETPC_VULKAN_GUEST stage=device_created_rejected reason=active_device_exists active=%p new=%p\n",
             pocketpc_guest_resource.device, device);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    pocketpc_guest_resource_reset_records();
    pocketpc_guest_resource.session.fd = -1;
    pocketpc_guest_resource.stopping = FALSE;
    pocketpc_guest_resource.worker_started = FALSE;

    result = pocketpc_graphics_session_connect_from_environment(
        &pocketpc_guest_resource.session,
        PGS_DEFAULT_TIMEOUT_MILLIS,
        session_error,
        sizeof(session_error));
    if (result != PGS_OK)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=pgh1_connect_failed result=%d error=%s\n",
            result, session_error);
        pocketpc_graphics_session_close(&pocketpc_guest_resource.session);
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    pocketpc_guest_resource.device = device;
    result = pthread_create(
        &pocketpc_guest_resource.worker,
        NULL,
        pocketpc_vulkan_guest_resource_worker,
        device);
    if (result != 0)
    {
        ERR("POCKETPC_VULKAN_GUEST stage=resource_worker_create_failed result=%d\n", result);
        pocketpc_graphics_session_close(&pocketpc_guest_resource.session);
        pocketpc_guest_resource.device = NULL;
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }
    pocketpc_guest_resource.worker_started = TRUE;

    TRACE("POCKETPC_VULKAN_GUEST stage=pgh1_connected_worker_started device=%p runtime_evidence=0\n", device);
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
}

static void pocketpc_vulkan_device_destroyed(struct vulkan_device *device)
{
    pthread_t worker;
    BOOL join_worker = FALSE;

    if (!device) return;

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device != device)
    {
        pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
        return;
    }

    pocketpc_guest_resource.stopping = TRUE;
    if (pocketpc_guest_resource.session.fd >= 0)
        shutdown(pocketpc_guest_resource.session.fd, SHUT_RDWR);
    if (pocketpc_guest_resource.worker_started)
    {
        worker = pocketpc_guest_resource.worker;
        join_worker = TRUE;
    }
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);

    if (join_worker)
        pthread_join(worker, NULL);

    pthread_mutex_lock(&pocketpc_guest_resource.mutex);
    if (pocketpc_guest_resource.device == device)
    {
        TRACE("POCKETPC_VULKAN_GUEST stage=device_resource_cleanup device=%p\n", device);
        pocketpc_guest_resource_release_imports_locked(device);
        pocketpc_graphics_session_close(&pocketpc_guest_resource.session);
        pocketpc_guest_resource.device = NULL;
        pocketpc_guest_resource.worker_started = FALSE;
        pocketpc_guest_resource.stopping = FALSE;
    }
    pthread_mutex_unlock(&pocketpc_guest_resource.mutex);
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
    LONG count;
    (void)hdc;

    if (!pocketpc_headless_diagnostic_enabled())
        return;

    count = InterlockedIncrement(&pocketpc_headless_present_count);
    TRACE(
        "POCKETPC_VULKAN_WSI stage=headless_present_observed diagnostic_only=1 visible_present=0 count=%ld hwnd=%p\n",
        count,
        surface ? surface->hwnd : NULL
    );
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
    .p_vulkan_device_created = pocketpc_vulkan_device_created,
    .p_vulkan_device_destroyed = pocketpc_vulkan_device_destroyed,
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
        "POCKETPC_VULKAN_WSI stage=abi_initialized version=%u visible_surface_backend=blocked headless_diagnostic=%u external_handle_mapping=ready device_lifecycle_callbacks=ready\n",
        WINE_VULKAN_DRIVER_VERSION,
        pocketpc_headless_diagnostic_enabled()
    );

    return STATUS_SUCCESS;
}
