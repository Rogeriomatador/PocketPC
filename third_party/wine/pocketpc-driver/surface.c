#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "pocketpcdrv.h"
#include "pocketpc_surface_writer.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

struct pocketpc_window_surface
{
    struct window_surface header;
    struct pdb_surface_writer writer;
    int force_opaque;
    uint64_t in_flight_frame_id;
    BOOL frame_in_flight;
};

static pthread_mutex_t surface_generation_mutex =
    PTHREAD_MUTEX_INITIALIZER;
static uint64_t next_surface_generation = 1u;

static pthread_mutex_t surface_registry_mutex =
    PTHREAD_MUTEX_INITIALIZER;
static struct pocketpc_window_surface *
surface_registry[PDB_WINE_WINDOW_LIMIT];

static BOOL publish_surface(
    struct pocketpc_window_surface *previous,
    struct pocketpc_window_surface *created
)
{
    size_t i;
    size_t empty = PDB_WINE_WINDOW_LIMIT;
    uint64_t window_id;

    if (!created)
        return FALSE;

    window_id =
        created->writer.surface.window_id;
    if (!window_id)
        return FALSE;

    pthread_mutex_lock(
        &surface_registry_mutex
    );

    for (
        i = 0u;
        i < PDB_WINE_WINDOW_LIMIT;
        ++i
    ) {
        struct pocketpc_window_surface *current =
            surface_registry[i];

        if (!current) {
            if (
                empty ==
                PDB_WINE_WINDOW_LIMIT
            ) {
                empty = i;
            }
            continue;
        }

        if (
            current == previous ||
            current->writer.surface.window_id ==
                window_id
        ) {
            surface_registry[i] =
                created;
            pthread_mutex_unlock(
                &surface_registry_mutex
            );
            return TRUE;
        }
    }

    if (
        empty !=
        PDB_WINE_WINDOW_LIMIT
    ) {
        surface_registry[empty] =
            created;
        pthread_mutex_unlock(
            &surface_registry_mutex
        );
        return TRUE;
    }

    pthread_mutex_unlock(
        &surface_registry_mutex
    );
    return FALSE;
}

static void unpublish_surface(
    struct pocketpc_window_surface *surface
)
{
    size_t i;

    if (!surface)
        return;

    pthread_mutex_lock(
        &surface_registry_mutex
    );
    for (
        i = 0u;
        i < PDB_WINE_WINDOW_LIMIT;
        ++i
    ) {
        if (
            surface_registry[i] ==
            surface
        ) {
            surface_registry[i] =
                NULL;
            break;
        }
    }
    pthread_mutex_unlock(
        &surface_registry_mutex
    );
}

static BOOL begin_surface_frame(
    struct pocketpc_window_surface *surface,
    uint64_t frame_id
)
{
    size_t i;

    if (
        !surface ||
        !frame_id
    ) {
        return FALSE;
    }

    pthread_mutex_lock(
        &surface_registry_mutex
    );
    for (
        i = 0u;
        i < PDB_WINE_WINDOW_LIMIT;
        ++i
    ) {
        if (
            surface_registry[i] ==
            surface
        ) {
            if (
                surface->frame_in_flight
            ) {
                pthread_mutex_unlock(
                    &surface_registry_mutex
                );
                return FALSE;
            }
            surface->frame_in_flight =
                TRUE;
            surface->in_flight_frame_id =
                frame_id;
            pthread_mutex_unlock(
                &surface_registry_mutex
            );
            return TRUE;
        }
    }

    pthread_mutex_unlock(
        &surface_registry_mutex
    );
    return FALSE;
}

static void cancel_surface_frame(
    struct pocketpc_window_surface *surface,
    uint64_t frame_id
)
{
    if (!surface || !frame_id)
        return;

    pthread_mutex_lock(
        &surface_registry_mutex
    );
    if (
        surface->frame_in_flight &&
        surface->in_flight_frame_id ==
            frame_id
    ) {
        surface->frame_in_flight =
            FALSE;
        surface->in_flight_frame_id =
            0u;
    }
    pthread_mutex_unlock(
        &surface_registry_mutex
    );
}

BOOL POCKETPC_HandleFramePresented(
    const struct pdb_frame_presented *event
)
{
    size_t i;

    if (
        !event ||
        !event->window_id ||
        !event->frame_id
    ) {
        return FALSE;
    }

    pthread_mutex_lock(
        &surface_registry_mutex
    );
    for (
        i = 0u;
        i < PDB_WINE_WINDOW_LIMIT;
        ++i
    ) {
        struct pocketpc_window_surface *surface =
            surface_registry[i];

        if (
            !surface ||
            surface->writer.surface.window_id !=
                event->window_id ||
            surface->writer.surface.surface_id !=
                event->surface_id ||
            surface->writer.surface.generation !=
                event->generation
        ) {
            continue;
        }

        if (
            !surface->frame_in_flight ||
            surface->in_flight_frame_id !=
                event->frame_id
        ) {
            pthread_mutex_unlock(
                &surface_registry_mutex
            );
            return FALSE;
        }

        surface->frame_in_flight =
            FALSE;
        surface->in_flight_frame_id =
            0u;
        pthread_mutex_unlock(
            &surface_registry_mutex
        );
        return event->status == 0u;
    }

    /*
     * A late ACK after a window/surface was destroyed is harmless.
     * There is no shared backing left to release.
     */
    pthread_mutex_unlock(
        &surface_registry_mutex
    );
    return TRUE;
}

static struct pocketpc_window_surface *
get_pocketpc_surface(
    struct window_surface *surface
) {
    return (
        struct pocketpc_window_surface *
    )surface;
}

static uint64_t allocate_surface_generation(
    void
) {
    uint64_t generation = 0u;

    pthread_mutex_lock(
        &surface_generation_mutex
    );
    if (
        next_surface_generation != 0u &&
        next_surface_generation != UINT64_MAX
    ) {
        generation =
            next_surface_generation;
        next_surface_generation += 1u;
    }
    pthread_mutex_unlock(
        &surface_generation_mutex
    );

    return generation;
}

static void pocketpc_surface_set_clip(
    struct window_surface *surface,
    const RECT *rects,
    UINT count
) {
    (void)surface;
    (void)rects;
    (void)count;
}

static BOOL pocketpc_surface_flush(
    struct window_surface *window_surface,
    const RECT *rect,
    const RECT *dirty,
    const BITMAPINFO *color_info,
    const void *color_bits,
    BOOL shape_changed,
    const BITMAPINFO *shape_info,
    const void *shape_bits
) {
    struct pocketpc_window_surface *surface =
        get_pocketpc_surface(window_surface);
    struct pdb_surface_rect dirty_rect;
    int32_t source_height;
    int32_t source_stride;
    uint64_t frame_id = 0u;
    uint64_t frame_slot_id = 0u;
    char error[160] = {0};

    (void)shape_changed;
    (void)shape_info;

    if (!surface || !rect || !color_info || !color_bits)
        return FALSE;

    if (shape_bits)
    {
        WARN(
            "shaped surface unsupported hwnd=%p\n",
            window_surface->hwnd
        );
        return FALSE;
    }

    if (
        color_info->bmiHeader.biBitCount != 32 ||
        color_info->bmiHeader.biCompression != BI_RGB ||
        color_info->bmiHeader.biWidth !=
            surface->writer.surface.width ||
        (
            color_info->bmiHeader.biHeight !=
                surface->writer.surface.height &&
            color_info->bmiHeader.biHeight !=
                -surface->writer.surface.height
        )
    ) {
        WARN(
            "unsupported surface format width=%ld height=%ld bpp=%u compression=%lu expected=%dx%d\n",
            color_info->bmiHeader.biWidth,
            color_info->bmiHeader.biHeight,
            color_info->bmiHeader.biBitCount,
            color_info->bmiHeader.biCompression,
            surface->writer.surface.width,
            surface->writer.surface.height
        );
        return FALSE;
    }

    /*
     * The descriptor must match the already-attested surface before
     * stride/height arithmetic. This also avoids negating an arbitrary
     * LONG height (for example INT32_MIN) on the frame hot path.
     */
    source_height =
        surface->writer.surface.height;
    source_stride =
        surface->writer.surface.width * 4;

    if (
        dirty &&
        dirty->right > dirty->left &&
        dirty->bottom > dirty->top
    ) {
        /*
         * Wine 11 win32u offsets the dirty rectangle to the surface
         * origin before invoking driver->flush(). The accompanying
         * rect remains in window coordinates, so subtracting rect.left
         * or rect.top here would offset the dirty region twice.
         */
        dirty_rect.left =
            max(dirty->left, 0);
        dirty_rect.top =
            max(dirty->top, 0);
        dirty_rect.right =
            min(
                dirty->right,
                surface->writer.surface.width
            );
        dirty_rect.bottom =
            min(
                dirty->bottom,
                surface->writer.surface.height
            );
    } else {
        dirty_rect.left = 0;
        dirty_rect.top = 0;
        dirty_rect.right =
            surface->writer.surface.width;
        dirty_rect.bottom =
            surface->writer.surface.height;
    }

    if (
        dirty_rect.right <= dirty_rect.left ||
        dirty_rect.bottom <= dirty_rect.top
    ) {
        return TRUE;
    }

    frame_slot_id =
        surface->writer
            .next_frame_id;
    if (!frame_slot_id)
        return FALSE;

    if (
        !begin_surface_frame(
            surface,
            frame_slot_id
        )
    ) {
        TRACE(
            "coalescing frame while ACK pending hwnd=%p window=%llu; Wine dirty bounds retained\n",
            window_surface->hwnd,
            (unsigned long long)
                surface->writer
                    .surface.window_id
        );
        /*
         * win32u resets surface->bounds only when a driver flush returns
         * TRUE. Keep the dirty bounds live while the single shared buffer
         * is owned by the host; the normal Wine flush/idle path will retry
         * after FRAME_PRESENTED clears frame_in_flight.
         */
        return FALSE;
    }

    if (
        pdb_surface_writer_copy_bgra(
            &surface->writer,
            color_bits,
            color_info->bmiHeader.biWidth,
            source_height,
            source_stride,
            color_info->bmiHeader.biHeight < 0,
            &dirty_rect,
            surface->force_opaque,
            error,
            sizeof(error)
        ) != 0
    ) {
        cancel_surface_frame(
            surface,
            frame_slot_id
        );
        ERR(
            "surface copy failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }

    pthread_mutex_lock(&pocketpc_bridge_mutex);

    if (
        pdb_surface_writer_commit(
            &surface->writer,
            &pocketpc_connection,
            &frame_id,
            error,
            sizeof(error)
        ) != 0
    ) {
        pthread_mutex_unlock(&pocketpc_bridge_mutex);
        cancel_surface_frame(
            surface,
            frame_slot_id
        );
        ERR(
            "FRAME_READY failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

    if (
        frame_id !=
        frame_slot_id
    ) {
        cancel_surface_frame(
            surface,
            frame_slot_id
        );
        ERR(
            "PDB_FRAME_SLOT_IDENTITY_MISMATCH expected=%llu actual=%llu\n",
            (unsigned long long)
                frame_slot_id,
            (unsigned long long)
                frame_id
        );
        return FALSE;
    }

    TRACE(
        "FRAME_READY published hwnd=%p window=%llu frame=%llu; presentation ACK is asynchronous\n",
        window_surface->hwnd,
        (unsigned long long)
            surface->writer.surface.window_id,
        (unsigned long long)
            frame_id
    );

    return TRUE;
}

static void pocketpc_surface_destroy(
    struct window_surface *window_surface
) {
    struct pocketpc_window_surface *surface =
        get_pocketpc_surface(
            window_surface
        );

    unpublish_surface(
        surface
    );

    TRACE(
        "destroy surface hwnd=%p id=%llu generation=%llu\n",
        window_surface->hwnd,
        (unsigned long long)
            surface->writer
                .surface.surface_id,
        (unsigned long long)
            surface->writer
                .surface.generation
    );

    pdb_surface_writer_close(
        &surface->writer
    );
}

static const struct window_surface_funcs
pocketpc_surface_funcs =
{
    pocketpc_surface_set_clip,
    pocketpc_surface_flush,
    pocketpc_surface_destroy
};

static BOOL request_surface(
    HWND hwnd,
    const RECT *surface_rect,
    struct pocketpc_window_surface *surface
) {
    struct pdb_surface_request request;
    struct pdb_surface_available available;
    unsigned int received = 0u;
    uint64_t window_id = 0u;
    uint64_t generation;
    BOOL got_surface = FALSE;
    char error[160] = {0};

    memset(&available, 0, sizeof(available));

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)hwnd,
            &window_id
        ) != 0
    ) {
        ERR(
            "surface requested for unbridged hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    generation =
        allocate_surface_generation();
    if (generation == 0u)
    {
        ERR("surface generation exhausted\n");
        return FALSE;
    }

    memset(&request, 0, sizeof(request));
    request.window_id = window_id;
    request.generation = generation;
    request.width =
        surface_rect->right -
        surface_rect->left;
    request.height =
        surface_rect->bottom -
        surface_rect->top;
    request.pixel_format = 1u;

    if (
        request.width <= 0 ||
        request.height <= 0 ||
        request.width > 16384 ||
        request.height > 16384
    ) {
        ERR(
            "surface dimensions invalid %dx%d\n",
            request.width,
            request.height
        );
        return FALSE;
    }

    pthread_mutex_lock(&pocketpc_bridge_mutex);

    if (
        pdb_send_surface_request(
            &pocketpc_connection,
            &request,
            error,
            sizeof(error)
        ) != 0
    ) {
        POCKETPC_FailBridgeLocked(
            error[0]
                ? error
                : "PDB_SURFACE_REQUEST_SEND_FAILED"
        );
        pthread_mutex_unlock(&pocketpc_bridge_mutex);
        ERR(
            "surface request send failed hwnd=%p: %s\n",
            hwnd,
            error
        );
        return FALSE;
    }

    while (
        received <
        POCKETPC_HOST_EVENT_QUEUE_LIMIT
    ) {
        struct pdb_host_event event;

        if (
            pdb_receive_host_event(
                &pocketpc_connection,
                &event,
                error,
                sizeof(error)
            ) != 0
        ) {
            break;
        }
        received += 1u;

        if (
            event.type ==
            PDB_MSG_SURFACE_AVAILABLE
        ) {
            available =
                event.data.surface;
            got_surface = TRUE;
            break;
        }

        if (
            event.type ==
                PDB_MSG_POINTER_EVENT ||
            event.type ==
                PDB_MSG_KEY_EVENT ||
            event.type ==
                PDB_MSG_WINDOW_COMMAND
        ) {
            if (
                !POCKETPC_QueueHostEventLocked(
                    &event
                )
            ) {
                snprintf(
                    error,
                    sizeof(error),
                    "PDB_HOST_EVENT_QUEUE_FULL"
                );
                break;
            }
            continue;
        }

        if (
            event.type ==
                PDB_MSG_FRAME_PRESENTED
        ) {
            const BOOL accepted =
                POCKETPC_HandleFramePresented(
                    &event.data
                        .frame_presented
                );

            if (!accepted) {
                WARN(
                    "frame ACK rejected or unmatched during surface handshake window=%llu frame=%llu status=%u\n",
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .window_id,
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .frame_id,
                    event.data
                        .frame_presented
                        .status
                );
            }
            continue;
        }

        snprintf(
            error,
            sizeof(error),
            "PDB_UNEXPECTED_EVENT_DURING_SURFACE:%u",
            event.type
        );
        break;
    }

    if (!got_surface) {
        POCKETPC_FailBridgeLocked(
            error[0]
                ? error
                : "PDB_SURFACE_RESPONSE_MISSING"
        );
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

    if (!got_surface)
    {
        ERR(
            "surface response missing hwnd=%p: %s\n",
            hwnd,
            error
        );
        return FALSE;
    }

    if (
        available.window_id !=
            request.window_id ||
        available.generation !=
            request.generation ||
        available.width !=
            request.width ||
        available.height !=
            request.height ||
        available.pixel_format !=
            request.pixel_format
    ) {
        pthread_mutex_lock(
            &pocketpc_bridge_mutex
        );
        POCKETPC_FailBridgeLocked(
            "PDB_SURFACE_IDENTITY_MISMATCH"
        );
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        ERR(
            "surface identity mismatch hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    pdb_surface_writer_init(
        &surface->writer
    );

    if (
        pdb_surface_writer_open(
            &surface->writer,
            &available,
            error,
            sizeof(error)
        ) != 0
    ) {
        pthread_mutex_lock(
            &pocketpc_bridge_mutex
        );
        POCKETPC_FailBridgeLocked(
            error[0]
                ? error
                : "PDB_SURFACE_WRITER_OPEN_FAILED"
        );
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        ERR(
            "surface writer open failed hwnd=%p: %s\n",
            hwnd,
            error
        );
        return FALSE;
    }

    return TRUE;
}

BOOL POCKETPC_CreateWindowSurface(
    HWND hwnd,
    BOOL layered,
    const RECT *surface_rect,
    struct window_surface **surface
) {
    struct pocketpc_window_surface *created;
    struct window_surface *window_surface;
    struct window_surface *previous;
    char info_buffer[
        FIELD_OFFSET(
            BITMAPINFO,
            bmiColors[256]
        )
    ];
    BITMAPINFO *info =
        (BITMAPINFO *)info_buffer;
    int width;
    int height;

    if (
        !pocketpc_bridge_ready ||
        !surface_rect ||
        !surface
    ) {
        return FALSE;
    }

    previous = *surface;
    if (
        previous &&
        previous->funcs ==
            &pocketpc_surface_funcs &&
        previous->rect.left ==
            surface_rect->left &&
        previous->rect.top ==
            surface_rect->top &&
        previous->rect.right ==
            surface_rect->right &&
        previous->rect.bottom ==
            surface_rect->bottom
    ) {
        return TRUE;
    }

    width =
        surface_rect->right -
        surface_rect->left;
    height =
        surface_rect->bottom -
        surface_rect->top;
    if (
        width <= 0 ||
        height <= 0 ||
        width > 16384 ||
        height > 16384
    ) {
        return FALSE;
    }

    memset(
        info,
        0,
        sizeof(info_buffer)
    );
    info->bmiHeader.biSize =
        sizeof(
            info->bmiHeader
        );
    info->bmiHeader.biWidth =
        width;
    info->bmiHeader.biHeight =
        -height;
    info->bmiHeader.biPlanes = 1;
    info->bmiHeader.biBitCount = 32;
    info->bmiHeader.biCompression =
        BI_RGB;
    info->bmiHeader.biSizeImage =
        (DWORD)
            (
                (uint64_t)width *
                (uint64_t)height *
                4u
            );

    window_surface =
        window_surface_create(
            sizeof(*created),
            &pocketpc_surface_funcs,
            hwnd,
            surface_rect,
            info,
            0
        );
    if (!window_surface)
        return FALSE;

    created =
        get_pocketpc_surface(
            window_surface
        );
    pdb_surface_writer_init(
        &created->writer
    );
    created->force_opaque =
        layered ? 0 : 1;
    created->in_flight_frame_id =
        0u;
    created->frame_in_flight =
        FALSE;

    if (
        !request_surface(
            hwnd,
            surface_rect,
            created
        )
    ) {
        window_surface_release(
            window_surface
        );
        return FALSE;
    }

    if (
        !publish_surface(
            previous
                ? get_pocketpc_surface(
                    previous
                )
                : NULL,
            created
        )
    ) {
        ERR(
            "surface registry full hwnd=%p\n",
            hwnd
        );
        window_surface_release(
            window_surface
        );
        return FALSE;
    }

    if (previous)
    {
        window_surface_release(
            previous
        );
    }

    *surface = window_surface;

    TRACE(
        "created surface hwnd=%p window=%llu surface=%llu generation=%llu size=%dx%d stride=%d layered=%u\n",
        hwnd,
        (unsigned long long)
            created->writer
                .surface.window_id,
        (unsigned long long)
            created->writer
                .surface.surface_id,
        (unsigned long long)
            created->writer
                .surface.generation,
        created->writer
            .surface.width,
        created->writer
            .surface.height,
        created->writer
            .surface.stride_bytes,
        layered
    );

    return TRUE;
}
