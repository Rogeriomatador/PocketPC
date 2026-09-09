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
};

static pthread_mutex_t surface_generation_mutex =
    PTHREAD_MUTEX_INITIALIZER;
static uint64_t next_surface_generation = 1u;

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
        get_pocketpc_surface(
            window_surface
        );
    struct pdb_surface_rect dirty_rect;
    struct pdb_frame_presented presented;
    int32_t source_height;
    int32_t source_stride;
    uint64_t frame_id = 0u;
    char error[160] = {0};

    (void)shape_changed;
    (void)shape_info;
    (void)shape_bits;

    if (
        !surface ||
        !rect ||
        !color_info ||
        !color_bits
    ) {
        return FALSE;
    }

    if (
        color_info->bmiHeader.biBitCount != 32 ||
        color_info->bmiHeader.biCompression != BI_RGB ||
        color_info->bmiHeader.biWidth <= 0 ||
        color_info->bmiHeader.biHeight == 0
    ) {
        WARN(
            "unsupported surface format width=%ld height=%ld bpp=%u compression=%lu\n",
            color_info->bmiHeader.biWidth,
            color_info->bmiHeader.biHeight,
            color_info->bmiHeader.biBitCount,
            color_info->bmiHeader.biCompression
        );
        return FALSE;
    }

    source_height =
        color_info->bmiHeader.biHeight < 0
            ? -color_info->bmiHeader.biHeight
            : color_info->bmiHeader.biHeight;
    source_stride =
        color_info->bmiHeader.biWidth * 4;

    if (
        dirty &&
        dirty->right > dirty->left &&
        dirty->bottom > dirty->top
    ) {
        dirty_rect.left =
            dirty->left -
            rect->left;
        dirty_rect.top =
            dirty->top -
            rect->top;
        dirty_rect.right =
            dirty->right -
            rect->left;
        dirty_rect.bottom =
            dirty->bottom -
            rect->top;
    } else {
        dirty_rect.left = 0;
        dirty_rect.top = 0;
        dirty_rect.right =
            surface->writer
                .surface.width;
        dirty_rect.bottom =
            surface->writer
                .surface.height;
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
        ERR(
            "surface copy failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        pdb_surface_writer_commit(
            &surface->writer,
            &pocketpc_connection,
            &frame_id,
            error,
            sizeof(error)
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        ERR(
            "FRAME_READY failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }

    if (
        pdb_receive_frame_presented(
            &pocketpc_connection,
            &presented,
            error,
            sizeof(error)
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        ERR(
            "FRAME_PRESENTED failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

    if (
        presented.window_id !=
            surface->writer
                .surface.window_id ||
        presented.frame_id !=
            frame_id ||
        presented.status != 0u
    ) {
        ERR(
            "frame ack mismatch hwnd=%p window=%llu/%llu frame=%llu/%llu status=%u\n",
            window_surface->hwnd,
            (unsigned long long)
                presented.window_id,
            (unsigned long long)
                surface->writer
                    .surface.window_id,
            (unsigned long long)
                presented.frame_id,
            (unsigned long long)
                frame_id,
            presented.status
        );
        return FALSE;
    }

    return TRUE;
}

static void pocketpc_surface_destroy(
    struct window_surface *window_surface
) {
    struct pocketpc_window_surface *surface =
        get_pocketpc_surface(
            window_surface
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
    uint64_t window_id = 0u;
    uint64_t generation;
    char error[160] = {0};

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
        ERR(
            "surface generation exhausted\n"
        );
        return FALSE;
    }

    memset(
        &request,
        0,
        sizeof(request)
    );
    request.window_id =
        window_id;
    request.generation =
        generation;
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

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        pdb_send_surface_request(
            &pocketpc_connection,
            &request,
            error,
            sizeof(error)
        ) != 0 ||
        pdb_receive_surface_available(
            &pocketpc_connection,
            &available,
            error,
            sizeof(error)
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        ERR(
            "surface handshake failed hwnd=%p: %s\n",
            hwnd,
            error
        );
        return FALSE;
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

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
