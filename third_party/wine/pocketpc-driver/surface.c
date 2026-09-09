#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdint.h>
#include <string.h>

#include "pocketpcdrv.h"
#include "pocketpc_surface_writer.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

#define POCKETPC_SURFACE_MAX_BYTES     (256u * 1024u * 1024u)

struct pocketpc_window_surface
{
    struct window_surface header;
    struct pdb_surface_writer writer;
    BOOL layered;
    uint64_t generation;
};

static uint64_t next_surface_generation = 1u;

static struct pocketpc_window_surface *
get_pocketpc_surface(
    struct window_surface *surface
) {
    return (
        struct pocketpc_window_surface *
    )surface;
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
    struct pdb_surface_rect copy_rect;
    const BITMAPINFOHEADER *header;
    int width;
    int height;
    int stride;
    int top_down;
    uint64_t frame_id = 0u;
    char error[160] = {0};

    (void)rect;
    (void)shape_changed;
    (void)shape_info;

    if (
        !pocketpc_bridge_ready ||
        !dirty ||
        !color_info ||
        !color_bits
    ) {
        return FALSE;
    }

    if (shape_bits) {
        WARN(
            "shaped surface unsupported hwnd=%p\n",
            window_surface->hwnd
        );
        return FALSE;
    }

    header =
        &color_info->bmiHeader;
    width = header->biWidth;
    height = header->biHeight;
    top_down = height < 0;
    if (height < 0) height = -height;

    if (
        width <= 0 ||
        height <= 0 ||
        header->biPlanes != 1 ||
        header->biBitCount != 32 ||
        header->biCompression != BI_RGB ||
        width > INT32_MAX / 4
    ) {
        WARN(
            "unsupported surface bitmap hwnd=%p width=%ld height=%ld bpp=%u compression=%lu\n",
            window_surface->hwnd,
            header->biWidth,
            header->biHeight,
            header->biBitCount,
            header->biCompression
        );
        return FALSE;
    }

    if (
        dirty->right <= dirty->left ||
        dirty->bottom <= dirty->top
    ) {
        return TRUE;
    }

    stride = width * 4;
    copy_rect.left = dirty->left;
    copy_rect.top = dirty->top;
    copy_rect.right = dirty->right;
    copy_rect.bottom = dirty->bottom;

    if (
        pdb_surface_writer_copy_bgra(
            &surface->writer,
            color_bits,
            width,
            height,
            stride,
            top_down,
            &copy_rect,
            surface->layered ? 0 : 1,
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
            "surface commit failed hwnd=%p: %s\n",
            window_surface->hwnd,
            error
        );
        return FALSE;
    }
    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

    TRACE(
        "surface committed hwnd=%p generation=%llu frame=%llu\n",
        window_surface->hwnd,
        (unsigned long long)
            surface->generation,
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

BOOL POCKETPC_CreateWindowSurface(
    HWND hwnd,
    BOOL layered,
    const RECT *surface_rect,
    struct window_surface **surface
) {
    struct window_surface *previous;
    struct window_surface *created;
    struct pocketpc_window_surface *created_surface;
    struct pdb_surface_request request;
    struct pdb_surface_available available;
    struct pdb_surface_writer writer;
    BITMAPINFO info;
    uint64_t window_id = 0u;
    uint64_t generation;
    uint64_t image_bytes;
    int width;
    int height;
    char error[160] = {0};

    if (
        !surface ||
        !surface_rect ||
        !pocketpc_bridge_ready
    ) {
        return FALSE;
    }

    previous = *surface;
    if (
        previous &&
        previous->funcs ==
            &pocketpc_surface_funcs &&
        EqualRect(
            &previous->rect,
            surface_rect
        ) &&
        get_pocketpc_surface(previous)
            ->layered == layered
    ) {
        return TRUE;
    }

    width =
        surface_rect->right -
        surface_rect->left;
    height =
        surface_rect->bottom -
        surface_rect->top;
    image_bytes =
        (uint64_t)width *
        (uint64_t)height *
        4u;

    if (
        width <= 0 ||
        height <= 0 ||
        width > 16384 ||
        height > 16384 ||
        image_bytes == 0u ||
        image_bytes >
            POCKETPC_SURFACE_MAX_BYTES
    ) {
        return FALSE;
    }

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)hwnd,
            &window_id
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return FALSE;
    }

    generation =
        next_surface_generation;
    if (!generation) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return FALSE;
    }
    next_surface_generation =
        generation == UINT64_MAX
            ? 0u
            : generation + 1u;

    memset(
        &request,
        0,
        sizeof(request)
    );
    request.window_id = window_id;
    request.generation = generation;
    request.width = width;
    request.height = height;
    request.pixel_format = 1u;
    request.flags = 0u;

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
            window_id ||
        available.generation !=
            generation ||
        available.width != width ||
        available.height != height ||
        available.pixel_format != 1u
    ) {
        ERR(
            "surface response mismatch hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    pdb_surface_writer_init(
        &writer
    );
    if (
        pdb_surface_writer_open(
            &writer,
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

    memset(
        &info,
        0,
        sizeof(info)
    );
    info.bmiHeader.biSize =
        sizeof(info.bmiHeader);
    info.bmiHeader.biWidth =
        width;
    info.bmiHeader.biHeight =
        -height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biSizeImage =
        (DWORD)image_bytes;
    info.bmiHeader.biCompression =
        BI_RGB;

    created =
        window_surface_create(
            sizeof(
                struct pocketpc_window_surface
            ),
            &pocketpc_surface_funcs,
            hwnd,
            surface_rect,
            &info,
            0
        );
    if (!created) {
        pdb_surface_writer_close(
            &writer
        );
        return FALSE;
    }

    created_surface =
        get_pocketpc_surface(
            created
        );
    created_surface->writer =
        writer;
    created_surface->layered =
        layered;
    created_surface->generation =
        generation;

    if (previous) {
        window_surface_release(
            previous
        );
    }
    *surface = created;

    TRACE(
        "created surface hwnd=%p window=%llu generation=%llu size=%dx%d\n",
        hwnd,
        (unsigned long long)
            window_id,
        (unsigned long long)
            generation,
        width,
        height
    );
    return TRUE;
}
