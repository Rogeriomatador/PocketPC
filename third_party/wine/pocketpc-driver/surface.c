#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "pocketpcdrv.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

struct pocketpc_window_surface
{
    struct window_surface header;
    uint64_t window_id;
    uint64_t surface_id;
    uint64_t generation;
    uint64_t next_frame_id;
    int fd;
    void *mapped;
    size_t mapped_bytes;
    int width;
    int height;
    int stride_bytes;
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

static BOOL copy_bgra_frame(
    struct pocketpc_window_surface *surface,
    const RECT *rect,
    const BITMAPINFO *color_info,
    const void *color_bits
) {
    const BITMAPINFOHEADER *header;
    const unsigned char *source;
    unsigned char *destination;
    int source_width;
    int source_height;
    int copy_width;
    int copy_height;
    int y;

    if (
        !surface ||
        !rect ||
        !color_info ||
        !color_bits ||
        !surface->mapped
    ) {
        return FALSE;
    }

    header =
        &color_info->bmiHeader;

    if (
        header->biBitCount != 32 ||
        header->biCompression != BI_RGB ||
        header->biWidth <= 0 ||
        header->biHeight == 0
    ) {
        WARN(
            "unsupported surface bitmap format width=%ld height=%ld bpp=%u compression=%lu\n",
            header->biWidth,
            header->biHeight,
            header->biBitCount,
            header->biCompression
        );
        return FALSE;
    }

    source_width =
        header->biWidth;
    source_height =
        header->biHeight < 0
            ? -header->biHeight
            : header->biHeight;

    copy_width =
        min(
            surface->width,
            min(
                source_width,
                rect->right -
                    rect->left
            )
        );
    copy_height =
        min(
            surface->height,
            min(
                source_height,
                rect->bottom -
                    rect->top
            )
        );

    if (
        copy_width <= 0 ||
        copy_height <= 0
    ) {
        return FALSE;
    }

    source =
        (const unsigned char *)
            color_bits;
    destination =
        (unsigned char *)
            surface->mapped;

    for (y = 0; y < copy_height; ++y)
    {
        int source_y =
            header->biHeight < 0
                ? y
                : source_height -
                    1 -
                    y;
        const unsigned char *src_row =
            source +
            (size_t)source_y *
                (size_t)source_width *
                4u;
        unsigned char *dst_row =
            destination +
            (size_t)y *
                (size_t)surface
                    ->stride_bytes;
        int x;

        memcpy(
            dst_row,
            src_row,
            (size_t)copy_width *
                4u
        );

        for (
            x = 0;
            x < copy_width;
            ++x
        ) {
            dst_row[
                (size_t)x *
                    4u +
                3u
            ] = 0xffu;
        }

        if (
            surface->stride_bytes >
            copy_width * 4
        ) {
            memset(
                dst_row +
                    (size_t)copy_width *
                        4u,
                0,
                (size_t)
                    (
                        surface
                            ->stride_bytes -
                        copy_width *
                            4
                    )
            );
        }
    }

    for (
        y = copy_height;
        y < surface->height;
        ++y
    ) {
        memset(
            destination +
                (size_t)y *
                    (size_t)surface
                        ->stride_bytes,
            0,
            (size_t)surface
                ->stride_bytes
        );
    }

    return TRUE;
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
    struct pdb_frame_ready ready;
    struct pdb_frame_presented presented;
    char error[160] = {0};

    (void)dirty;
    (void)shape_changed;
    (void)shape_info;
    (void)shape_bits;

    if (
        !copy_bgra_frame(
            surface,
            rect,
            color_info,
            color_bits
        )
    ) {
        return FALSE;
    }

    if (msync(
            surface->mapped,
            surface->mapped_bytes,
            MS_SYNC
        ) != 0
    ) {
        ERR(
            "surface msync failed hwnd=%p\n",
            window_surface->hwnd
        );
        return FALSE;
    }

    memset(
        &ready,
        0,
        sizeof(ready)
    );
    ready.window_id =
        surface->window_id;
    ready.surface_id =
        surface->surface_id;
    ready.generation =
        surface->generation;
    ready.frame_id =
        surface->next_frame_id;

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        pdb_send_frame_ready(
            &pocketpc_connection,
            &ready,
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
            ready.window_id ||
        presented.frame_id !=
            ready.frame_id ||
        presented.status != 0u
    ) {
        ERR(
            "frame ack mismatch hwnd=%p window=%llu/%llu frame=%llu/%llu status=%u\n",
            window_surface->hwnd,
            (unsigned long long)
                presented.window_id,
            (unsigned long long)
                ready.window_id,
            (unsigned long long)
                presented.frame_id,
            (unsigned long long)
                ready.frame_id,
            presented.status
        );
        return FALSE;
    }

    if (
        surface->next_frame_id ==
        UINT64_MAX
    ) {
        ERR(
            "frame id exhausted hwnd=%p\n",
            window_surface->hwnd
        );
        return FALSE;
    }

    surface->next_frame_id += 1u;
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
            surface->surface_id,
        (unsigned long long)
            surface->generation
    );

    if (
        surface->mapped &&
        surface->mapped != MAP_FAILED
    ) {
        munmap(
            surface->mapped,
            surface->mapped_bytes
        );
        surface->mapped = NULL;
    }
    if (surface->fd >= 0)
    {
        close(surface->fd);
        surface->fd = -1;
    }
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
    struct stat status;
    uint64_t window_id = 0u;
    uint64_t generation;
    uint64_t expected_bytes;
    char path[128] = {0};
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

    if (
        next_surface_generation ==
        0u ||
        next_surface_generation ==
        UINT64_MAX
    ) {
        ERR(
            "surface generation exhausted\n"
        );
        return FALSE;
    }
    generation =
        next_surface_generation++;

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
            request.pixel_format ||
        available.surface_id == 0u ||
        available.stride_bytes <
            available.width * 4
    ) {
        ERR(
            "surface identity mismatch hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    if (
        pdb_surface_guest_path(
            &available,
            path,
            sizeof(path)
        ) != 0
    ) {
        ERR(
            "surface path invalid hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    expected_bytes =
        (uint64_t)
            available.stride_bytes *
        (uint64_t)
            available.height;

    if (
        expected_bytes == 0u ||
        expected_bytes >
            64u *
            1024u *
            1024u
    ) {
        ERR(
            "surface bytes invalid %llu\n",
            (unsigned long long)
                expected_bytes
        );
        return FALSE;
    }

    surface->fd =
        open(
            path,
            O_RDWR |
                O_CLOEXEC
        );
    if (surface->fd < 0)
    {
        ERR(
            "surface open failed path=%s\n",
            path
        );
        return FALSE;
    }

    if (
        fstat(
            surface->fd,
            &status
        ) != 0 ||
        status.st_size !=
            (off_t)expected_bytes
    ) {
        ERR(
            "surface file size mismatch path=%s\n",
            path
        );
        close(surface->fd);
        surface->fd = -1;
        return FALSE;
    }

    surface->mapped =
        mmap(
            NULL,
            (size_t)expected_bytes,
            PROT_READ |
                PROT_WRITE,
            MAP_SHARED,
            surface->fd,
            0
        );
    if (surface->mapped == MAP_FAILED)
    {
        ERR(
            "surface mmap failed path=%s\n",
            path
        );
        surface->mapped = NULL;
        close(surface->fd);
        surface->fd = -1;
        return FALSE;
    }

    surface->window_id =
        available.window_id;
    surface->surface_id =
        available.surface_id;
    surface->generation =
        available.generation;
    surface->next_frame_id = 1u;
    surface->mapped_bytes =
        (size_t)expected_bytes;
    surface->width =
        available.width;
    surface->height =
        available.height;
    surface->stride_bytes =
        available.stride_bytes;

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

    (void)layered;

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
        EqualRect(
            &previous->rect,
            surface_rect
        )
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
    created->fd = -1;
    created->mapped = NULL;

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
        "created surface hwnd=%p window=%llu surface=%llu generation=%llu size=%dx%d stride=%d\n",
        hwnd,
        (unsigned long long)
            created->window_id,
        (unsigned long long)
            created->surface_id,
        (unsigned long long)
            created->generation,
        created->width,
        created->height,
        created->stride_bytes
    );

    return TRUE;
}
