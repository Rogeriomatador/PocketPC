#define _GNU_SOURCE
#include "pocketpc_surface_writer.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdatomic.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#define PDB_SURFACE_MAX_BYTES (256u * 1024u * 1024u)

static void set_error(
    char *error,
    size_t error_bytes,
    const char *message
) {
    if (error && error_bytes) {
        snprintf(error, error_bytes, "%s", message);
    }
}

static int valid_rect(
    const struct pdb_surface_rect *rect,
    int32_t width,
    int32_t height
) {
    return rect &&
        rect->left >= 0 &&
        rect->top >= 0 &&
        rect->right > rect->left &&
        rect->bottom > rect->top &&
        rect->right <= width &&
        rect->bottom <= height;
}

void pdb_surface_writer_init(
    struct pdb_surface_writer *writer
) {
    if (!writer) return;
    memset(writer, 0, sizeof(*writer));
    writer->fd = -1;
}

int pdb_surface_writer_open(
    struct pdb_surface_writer *writer,
    const struct pdb_surface_available *surface,
    char *error,
    size_t error_bytes
) {
    char path[128];
    struct stat st;
    uint64_t expected_bytes;

    if (!writer || !surface || writer->fd >= 0 || writer->mapping) {
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_STATE_INVALID");
        return -1;
    }

    if (
        surface->window_id == 0u ||
        surface->surface_id == 0u ||
        surface->generation == 0u ||
        surface->width <= 0 ||
        surface->height <= 0 ||
        surface->width > 16384 ||
        surface->height > 16384 ||
        surface->stride_bytes < surface->width * 4 ||
        surface->stride_bytes > 65536 ||
        surface->pixel_format != 1u
    ) {
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_DESCRIPTOR_INVALID");
        return -1;
    }

    expected_bytes =
        (uint64_t)surface->stride_bytes *
        (uint64_t)surface->height;
    if (
        expected_bytes == 0u ||
        expected_bytes > PDB_SURFACE_MAX_BYTES ||
        expected_bytes > SIZE_MAX
    ) {
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_BYTES_INVALID");
        return -1;
    }

    if (pdb_surface_guest_path(surface, path, sizeof(path)) != 0) {
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_PATH_INVALID");
        return -1;
    }

    writer->fd = open(path, O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    if (writer->fd < 0) {
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_OPEN_FAILED");
        return -1;
    }

    if (
        fstat(writer->fd, &st) != 0 ||
        !S_ISREG(st.st_mode) ||
        st.st_nlink != 1 ||
        st.st_size < 0 ||
        (uint64_t)st.st_size != expected_bytes
    ) {
        pdb_surface_writer_close(writer);
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_FILE_INVALID");
        return -1;
    }

    writer->mapping = mmap(
        NULL,
        (size_t)expected_bytes,
        PROT_READ | PROT_WRITE,
        MAP_SHARED,
        writer->fd,
        0
    );
    if (writer->mapping == MAP_FAILED) {
        writer->mapping = NULL;
        pdb_surface_writer_close(writer);
        set_error(error, error_bytes, "PDB_SURFACE_WRITER_MMAP_FAILED");
        return -1;
    }

    writer->mapped_bytes = (size_t)expected_bytes;
    writer->surface = *surface;
    writer->next_frame_id = 1u;
    return 0;
}

int pdb_surface_writer_copy_bgra(
    struct pdb_surface_writer *writer,
    const void *source,
    int32_t source_width,
    int32_t source_height,
    int32_t source_stride_bytes,
    int source_top_down,
    const struct pdb_surface_rect *dirty,
    int force_opaque,
    char *error,
    size_t error_bytes
) {
    struct pdb_surface_rect full;
    const unsigned char *source_bytes;
    int32_t y;

    if (
        !writer ||
        writer->fd < 0 ||
        !writer->mapping ||
        !source ||
        source_width != writer->surface.width ||
        source_height != writer->surface.height ||
        source_stride_bytes < source_width * 4 ||
        source_stride_bytes > 65536
    ) {
        set_error(error, error_bytes, "PDB_SURFACE_COPY_ARGUMENT_INVALID");
        return -1;
    }

    full.left = 0;
    full.top = 0;
    full.right = source_width;
    full.bottom = source_height;
    if (!dirty) dirty = &full;

    if (!valid_rect(dirty, source_width, source_height)) {
        set_error(error, error_bytes, "PDB_SURFACE_COPY_DIRTY_INVALID");
        return -1;
    }

    source_bytes = (const unsigned char *)source;

    for (y = dirty->top; y < dirty->bottom; ++y) {
        int32_t source_y =
            source_top_down ? y : source_height - 1 - y;
        const unsigned char *src =
            source_bytes +
            (size_t)source_y * (size_t)source_stride_bytes +
            (size_t)dirty->left * 4u;
        unsigned char *dst =
            writer->mapping +
            (size_t)y * (size_t)writer->surface.stride_bytes +
            (size_t)dirty->left * 4u;
        size_t pixels = (size_t)(dirty->right - dirty->left);
        size_t x;

        memcpy(dst, src, pixels * 4u);

        if (force_opaque) {
            for (x = 0; x < pixels; ++x) {
                dst[x * 4u + 3u] = 0xffu;
            }
        }
    }

    return 0;
}

int pdb_surface_writer_commit(
    struct pdb_surface_writer *writer,
    struct pdb_connection *connection,
    uint64_t *frame_id,
    char *error,
    size_t error_bytes
) {
    struct pdb_frame_ready ready;
    uint64_t current;

    if (!writer || writer->fd < 0 || !writer->mapping || !connection) {
        set_error(error, error_bytes, "PDB_SURFACE_COMMIT_ARGUMENT_INVALID");
        return -1;
    }

    current = writer->next_frame_id;
    if (current == 0u) {
        set_error(error, error_bytes, "PDB_SURFACE_FRAME_ID_EXHAUSTED");
        return -1;
    }

    /*
     * MAP_SHARED updates are visible through the page cache without
     * msync(). We only need ordering before FRAME_READY; forcing
     * MS_SYNC here would turn every presented frame into synchronous
     * backing-file I/O.
     */
    atomic_thread_fence(memory_order_release);

    ready.window_id = writer->surface.window_id;
    ready.surface_id = writer->surface.surface_id;
    ready.generation = writer->surface.generation;
    ready.frame_id = current;

    if (
        pdb_send_frame_ready(
            connection,
            &ready,
            error,
            error_bytes
        ) != 0
    ) {
        return -1;
    }

    writer->next_frame_id =
        current == UINT64_MAX ? 0u : current + 1u;

    if (frame_id) *frame_id = current;
    return 0;
}

void pdb_surface_writer_close(
    struct pdb_surface_writer *writer
) {
    if (!writer) return;

    if (writer->mapping) {
        munmap(writer->mapping, writer->mapped_bytes);
    }
    if (writer->fd >= 0) {
        close(writer->fd);
    }

    memset(writer, 0, sizeof(*writer));
    writer->fd = -1;
}
