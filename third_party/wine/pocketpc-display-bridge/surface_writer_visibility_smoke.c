#define _GNU_SOURCE
#include "pocketpc_surface_writer.h"

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/wait.h>
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

int main(void) {
    struct pdb_surface_available surface;
    struct pdb_connection receiver;
    struct pdb_frame frame;
    unsigned char actual[256];
    char path[128];
    int sockets[2] = {-1, -1};
    int fd = -1;
    pid_t child;
    int status = 0;
    int x;
    int y;
    char error[160] = {0};

    memset(&surface, 0, sizeof(surface));
    surface.window_id = 41u;
    surface.surface_id = 17u;
    surface.generation = 5u;
    surface.width = 8;
    surface.height = 8;
    surface.stride_bytes = 32;
    surface.pixel_format = 1u;
    snprintf(
        surface.token_hex,
        sizeof(surface.token_hex),
        "%016lx%016lx",
        (unsigned long)getpid(),
        0xa11ce55ul
    );

    if (
        pdb_surface_guest_path(
            &surface,
            path,
            sizeof(path)
        ) != 0
    ) {
        return 10;
    }

    fd = open(
        path,
        O_CREAT |
            O_EXCL |
            O_RDWR |
            O_CLOEXEC,
        0600
    );
    if (fd < 0)
        return 11;
    if (ftruncate(fd, 256) != 0) {
        close(fd);
        unlink(path);
        return 12;
    }
    close(fd);
    fd = -1;

    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        unlink(path);
        return 13;
    }

    child = fork();
    if (child < 0) {
        close(sockets[0]);
        close(sockets[1]);
        unlink(path);
        return 14;
    }

    if (child == 0) {
        struct pdb_connection sender;
        struct pdb_surface_writer writer;
        struct pdb_surface_rect dirty;
        unsigned char source[256];
        uint64_t frame_id = 0u;

        close(sockets[1]);
        memset(&sender, 0, sizeof(sender));
        sender.fd = sockets[0];
        sender.negotiated_capabilities =
            PDB_CAP_WINDOW_SURFACE;
        sender.next_sequence = 1u;
        sender.expected_inbound_sequence = 1u;

        for (y = 0; y < 8; ++y) {
            for (x = 0; x < 8; ++x) {
                unsigned char *pixel =
                    source +
                    (size_t)y * 32u +
                    (size_t)x * 4u;
                pixel[0] =
                    (unsigned char)(x + 1);
                pixel[1] =
                    (unsigned char)(y + 11);
                pixel[2] =
                    (unsigned char)(x ^ y);
                pixel[3] = 0x55u;
            }
        }

        dirty.left = 0;
        dirty.top = 0;
        dirty.right = 8;
        dirty.bottom = 8;

        pdb_surface_writer_init(&writer);
        if (
            pdb_surface_writer_open(
                &writer,
                &surface,
                error,
                sizeof(error)
            ) != 0
        ) {
            _exit(20);
        }

        if (
            pdb_surface_writer_copy_bgra(
                &writer,
                source,
                8,
                8,
                32,
                1,
                &dirty,
                1,
                error,
                sizeof(error)
            ) != 0
        ) {
            pdb_surface_writer_close(&writer);
            _exit(21);
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
            pdb_surface_writer_close(&writer);
            _exit(22);
        }

        pdb_surface_writer_close(&writer);
        pdb_close(&sender);
        _exit(0);
    }

    close(sockets[0]);
    memset(&receiver, 0, sizeof(receiver));
    receiver.fd = sockets[1];
    receiver.negotiated_capabilities =
        PDB_CAP_WINDOW_SURFACE;
    receiver.next_sequence = 1u;
    receiver.expected_inbound_sequence = 1u;

    if (
        pdb_receive_frame(
            &receiver,
            &frame,
            error,
            sizeof(error)
        ) != 0
    ) {
        pdb_close(&receiver);
        waitpid(child, &status, 0);
        unlink(path);
        return 30;
    }

    if (
        frame.type != PDB_MSG_FRAME_READY ||
        frame.payload_bytes !=
            PDB_FRAME_READY_BYTES ||
        read_u64(frame.payload) !=
            surface.window_id ||
        read_u64(frame.payload + 8) !=
            surface.surface_id ||
        read_u64(frame.payload + 16) !=
            surface.generation ||
        read_u64(frame.payload + 24) != 1u
    ) {
        pdb_release_frame(&frame);
        pdb_close(&receiver);
        waitpid(child, &status, 0);
        unlink(path);
        return 31;
    }
    pdb_release_frame(&frame);
    pdb_close(&receiver);

    if (
        waitpid(
            child,
            &status,
            0
        ) != child ||
        !WIFEXITED(status) ||
        WEXITSTATUS(status) != 0
    ) {
        unlink(path);
        return 32;
    }

    fd = open(
        path,
        O_RDONLY |
            O_CLOEXEC
    );
    if (fd < 0) {
        unlink(path);
        return 33;
    }
    if (
        read(
            fd,
            actual,
            sizeof(actual)
        ) !=
            (ssize_t)sizeof(actual)
    ) {
        close(fd);
        unlink(path);
        return 34;
    }
    close(fd);
    unlink(path);

    for (y = 0; y < 8; ++y) {
        for (x = 0; x < 8; ++x) {
            const unsigned char *pixel =
                actual +
                (size_t)y * 32u +
                (size_t)x * 4u;

            if (
                pixel[0] !=
                    (unsigned char)(x + 1) ||
                pixel[1] !=
                    (unsigned char)(y + 11) ||
                pixel[2] !=
                    (unsigned char)(x ^ y) ||
                pixel[3] != 0xffu
            ) {
                return 35;
            }
        }
    }

    printf(
        "POCKETPC_SURFACE_VISIBILITY_SMOKE_OK\n"
    );
    return 0;
}
