#define _GNU_SOURCE
#include "pocketpc_display_bridge.h"

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static int draw_smoke_frame(
    const struct pdb_surface_available *surface,
    char *error,
    size_t error_bytes
) {
    char path[128];
    struct stat st;
    unsigned char *pixels;
    size_t bytes;
    int fd;
    int x;
    int y;

    if (pdb_surface_guest_path(surface, path, sizeof(path)) != 0) {
        snprintf(error, error_bytes, "surface_path");
        return -1;
    }

    bytes =
        (size_t)surface->stride_bytes *
        (size_t)surface->height;

    fd = open(path, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        snprintf(error, error_bytes, "surface_open");
        return -1;
    }
    if (
        fstat(fd, &st) != 0 ||
        st.st_size < 0 ||
        (size_t)st.st_size != bytes
    ) {
        close(fd);
        snprintf(error, error_bytes, "surface_size");
        return -1;
    }

    pixels = mmap(
        NULL,
        bytes,
        PROT_READ | PROT_WRITE,
        MAP_SHARED,
        fd,
        0
    );
    if (pixels == MAP_FAILED) {
        close(fd);
        snprintf(error, error_bytes, "surface_mmap");
        return -1;
    }

    memset(pixels, 0, bytes);
    for (y = 0; y < surface->height; ++y) {
        unsigned char *row =
            pixels +
            (size_t)y *
                (size_t)surface->stride_bytes;
        for (x = 0; x < surface->width; ++x) {
            unsigned char *pixel =
                row + (size_t)x * 4u;
            pixel[0] =
                (unsigned char)(x & 0xff);
            pixel[1] =
                (unsigned char)(y & 0xff);
            pixel[2] =
                (unsigned char)(
                    (x ^ y) & 0xff
                );
            pixel[3] = 0xff;
        }
    }

    if (msync(pixels, bytes, MS_SYNC) != 0) {
        munmap(pixels, bytes);
        close(fd);
        snprintf(error, error_bytes, "surface_msync");
        return -1;
    }

    munmap(pixels, bytes);
    close(fd);
    return 0;
}

int main(void) {
    struct pdb_connection c;
    struct pdb_surface_available surface;
    struct pdb_frame_ready ready;
    struct pdb_pointer_event pointer;
    struct pdb_key_event key;
    struct pdb_frame_presented frame;
    char error[160] = {0};

    if (
        pdb_connect_from_environment(
            &c,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED connect=%s\n",
            error
        );
        return 80;
    }

    if (
        pdb_send_window_create(
            &c,
            1,
            0,
            0,
            640,
            360,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED create=%s\n",
            error
        );
        pdb_close(&c);
        return 81;
    }

    if (
        pdb_send_window_geometry(
            &c,
            1,
            20,
            30,
            640,
            360,
            1,
            PDB_ZORDER_NO_CHANGE,
            0u,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED geometry=%s\n",
            error
        );
        pdb_close(&c);
        return 82;
    }

    printf(
        "POCKETPC_DISPLAY_BRIDGE_WINDOW_OK id=1\n"
    );

    if (
        pdb_receive_surface_available(
            &c,
            &surface,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED surface=%s\n",
            error
        );
        pdb_close(&c);
        return 83;
    }

    if (
        surface.window_id != 1 ||
        surface.width != 64 ||
        surface.height != 64 ||
        surface.pixel_format != 1
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED surface_values\n"
        );
        pdb_close(&c);
        return 84;
    }

    if (
        draw_smoke_frame(
            &surface,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED draw=%s\n",
            error
        );
        pdb_close(&c);
        return 85;
    }

    ready.window_id =
        surface.window_id;
    ready.surface_id =
        surface.surface_id;
    ready.generation =
        surface.generation;
    ready.frame_id = 1;

    if (
        pdb_send_frame_ready(
            &c,
            &ready,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED frame_ready=%s\n",
            error
        );
        pdb_close(&c);
        return 86;
    }

    printf(
        "POCKETPC_DISPLAY_BRIDGE_FRAME_WRITTEN_OK\n"
    );

    if (
        pdb_receive_pointer_event(
            &c,
            &pointer,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED pointer=%s\n",
            error
        );
        pdb_close(&c);
        return 87;
    }
    if (
        pointer.window_id != 1 ||
        pointer.action != 1 ||
        pointer.x != 100 ||
        pointer.y != 80
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED pointer_values\n"
        );
        pdb_close(&c);
        return 88;
    }
    printf(
        "POCKETPC_DISPLAY_BRIDGE_POINTER_OK\n"
    );

    if (
        pdb_receive_key_event(
            &c,
            &key,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED key=%s\n",
            error
        );
        pdb_close(&c);
        return 89;
    }
    if (
        key.window_id != 1 ||
        key.action != 1 ||
        key.key_code != 65
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED key_values\n"
        );
        pdb_close(&c);
        return 90;
    }
    printf(
        "POCKETPC_DISPLAY_BRIDGE_KEY_OK\n"
    );

    if (
        pdb_receive_frame_presented(
            &c,
            &frame,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED frame=%s\n",
            error
        );
        pdb_close(&c);
        return 91;
    }
    if (
        frame.window_id != 1 ||
        frame.frame_id != 1 ||
        frame.status != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED frame_values\n"
        );
        pdb_close(&c);
        return 92;
    }
    printf(
        "POCKETPC_DISPLAY_BRIDGE_FRAME_ACK_OK\n"
    );

    if (
        pdb_send_window_destroy(
            &c,
            1,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED destroy=%s\n",
            error
        );
        pdb_close(&c);
        return 93;
    }

    printf(
        "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK caps=%u\n",
        c.negotiated_capabilities
    );
    pdb_close(&c);
    return 0;
}
