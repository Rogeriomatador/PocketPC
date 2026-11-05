#define _GNU_SOURCE
#include "pocketpc_surface_writer.h"

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

static uint64_t read_u64(
    const unsigned char *p
) {
    uint64_t value = 0u;
    int i;
    for (i = 0; i < 8; ++i) {
        value |=
            ((uint64_t)p[i]) <<
            (8 * i);
    }
    return value;
}

static void make_token(
    char token[33],
    unsigned long salt
) {
    snprintf(
        token,
        33,
        "%016lx%016lx",
        (unsigned long)getpid(),
        salt
    );
}

static int create_surface_file(
    const struct pdb_surface_available *surface,
    char path[128]
) {
    int fd;
    off_t bytes =
        (off_t)surface->stride_bytes *
        (off_t)surface->height;

    if (
        pdb_surface_guest_path(
            surface,
            path,
            128
        ) != 0
    ) {
        return -1;
    }

    fd = open(
        path,
        O_CREAT |
            O_EXCL |
            O_RDWR |
            O_CLOEXEC,
        0600
    );
    if (fd < 0) return -1;
    if (ftruncate(fd, bytes) != 0) {
        close(fd);
        unlink(path);
        return -1;
    }
    close(fd);
    return 0;
}

int main(void) {
    struct pdb_surface_available surface;
    struct pdb_surface_writer writer;
    struct pdb_surface_rect dirty;
    struct pdb_connection sender;
    struct pdb_connection receiver;
    struct pdb_frame frame;
    unsigned char source[48];
    unsigned char actual[48];
    char path[128];
    char symlink_path[128];
    char target_path[128];
    char error[160] = {0};
    uint64_t frame_id = 0u;
    int sockets[2] = {-1, -1};
    int fd;
    int x;
    int y;

    memset(&surface, 0, sizeof(surface));
    surface.window_id = 7u;
    surface.surface_id = 11u;
    surface.generation = 3u;
    surface.width = 4;
    surface.height = 3;
    surface.stride_bytes = 16;
    surface.pixel_format = 1u;
    make_token(surface.token_hex, 0x1234ul);

    if (create_surface_file(&surface, path) != 0) {
        return 10;
    }

    memset(source, 0, sizeof(source));
    for (y = 0; y < 3; ++y) {
        for (x = 0; x < 4; ++x) {
            unsigned char *pixel =
                source +
                (size_t)y * 16u +
                (size_t)x * 4u;
            pixel[0] = (unsigned char)(10 + x);
            pixel[1] = (unsigned char)(20 + y);
            pixel[2] = (unsigned char)(30 + x + y);
            pixel[3] = (unsigned char)(40 + x + y);
        }
    }

    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        unlink(path);
        return 11;
    }

    memset(&sender, 0, sizeof(sender));
    sender.fd = sockets[0];
    sender.negotiated_capabilities =
        PDB_CAP_WINDOW_SURFACE;
    sender.next_sequence = 1u;
    sender.expected_inbound_sequence = 1u;

    memset(&receiver, 0, sizeof(receiver));
    receiver.fd = sockets[1];
    receiver.negotiated_capabilities =
        PDB_CAP_WINDOW_SURFACE;
    receiver.next_sequence = 1u;
    receiver.expected_inbound_sequence = 1u;

    pdb_surface_writer_init(&writer);
    if (
        pdb_surface_writer_open(
            &writer,
            &surface,
            error,
            sizeof(error)
        ) != 0
    ) {
        unlink(path);
        return 12;
    }

    dirty.left = 0;
    dirty.top = 0;
    dirty.right = 4;
    dirty.bottom = 3;

    if (
        pdb_surface_writer_copy_bgra(
            &writer,
            source,
            4,
            3,
            16,
            0,
            &dirty,
            1,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 13;
    }

    if (
        pdb_surface_writer_commit(
            &writer,
            &sender,
            &frame_id,
            error,
            sizeof(error)
        ) != 0 ||
        frame_id != 1u
    ) {
        return 14;
    }

    if (
        pdb_receive_frame(
            &receiver,
            &frame,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 15;
    }

    if (
        frame.type != PDB_MSG_FRAME_READY ||
        frame.payload_bytes !=
            PDB_FRAME_READY_BYTES ||
        read_u64(frame.payload) != 7u ||
        read_u64(frame.payload + 8) != 11u ||
        read_u64(frame.payload + 16) != 3u ||
        read_u64(frame.payload + 24) != 1u
    ) {
        pdb_release_frame(&frame);
        return 16;
    }
    pdb_release_frame(&frame);

    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 17;
    if (
        read(fd, actual, sizeof(actual)) !=
        (ssize_t)sizeof(actual)
    ) {
        close(fd);
        return 18;
    }
    close(fd);

    for (y = 0; y < 3; ++y) {
        int source_y = 2 - y;
        for (x = 0; x < 4; ++x) {
            unsigned char *expected =
                source +
                (size_t)source_y * 16u +
                (size_t)x * 4u;
            unsigned char *pixel =
                actual +
                (size_t)y * 16u +
                (size_t)x * 4u;

            if (
                pixel[0] != expected[0] ||
                pixel[1] != expected[1] ||
                pixel[2] != expected[2] ||
                pixel[3] != 0xffu
            ) {
                return 19;
            }
        }
    }

    pdb_surface_writer_close(&writer);
    pdb_close(&sender);
    pdb_close(&receiver);
    unlink(path);

    memset(&surface, 0, sizeof(surface));
    surface.window_id = 9u;
    surface.surface_id = 12u;
    surface.generation = 1u;
    surface.width = 1;
    surface.height = 1;
    surface.stride_bytes = 4;
    surface.pixel_format = 1u;
    make_token(surface.token_hex, 0x5678ul);

    if (
        pdb_surface_guest_path(
            &surface,
            symlink_path,
            sizeof(symlink_path)
        ) != 0
    ) {
        return 20;
    }

    snprintf(
        target_path,
        sizeof(target_path),
        "/tmp/pocketpc-surface-target-%ld",
        (long)getpid()
    );
    fd = open(
        target_path,
        O_CREAT |
            O_EXCL |
            O_RDWR |
            O_CLOEXEC,
        0600
    );
    if (fd < 0) return 21;
    if (ftruncate(fd, 4) != 0) {
        close(fd);
        unlink(target_path);
        return 22;
    }
    close(fd);

    if (
        symlink(
            target_path,
            symlink_path
        ) != 0
    ) {
        unlink(target_path);
        return 23;
    }

    pdb_surface_writer_init(&writer);
    if (
        pdb_surface_writer_open(
            &writer,
            &surface,
            error,
            sizeof(error)
        ) == 0
    ) {
        pdb_surface_writer_close(&writer);
        unlink(symlink_path);
        unlink(target_path);
        return 24;
    }

    unlink(symlink_path);
    unlink(target_path);

    printf(
        "POCKETPC_SURFACE_WRITER_SMOKE_OK\n"
    );
    return 0;
}
