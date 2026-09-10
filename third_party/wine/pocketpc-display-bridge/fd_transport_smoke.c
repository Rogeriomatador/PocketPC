#include "pocketpc_fd_transport.h"

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int fail(const char *message) {
    fprintf(stderr, "FD_TRANSPORT_SMOKE_FAILED:%s\n", message);
    return 1;
}

int main(void) {
    int sockets[2] = {-1, -1};
    int pipe_fds[2] = {-1, -1};
    int received_fd = -1;
    char byte = '\0';
    const char marker = 'P';
    struct pocketpc_fd_transport_token sent = {
        .resource_id = 41u,
        .generation = 3u,
        .sequence = 7u,
    };
    struct pocketpc_fd_transport_token received = {0};
    struct pocketpc_fd_transport_token invalid = sent;

    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets) != 0) {
        return fail("socketpair");
    }
    if (pipe(pipe_fds) != 0) {
        close(sockets[0]);
        close(sockets[1]);
        return fail("pipe");
    }
    if (write(pipe_fds[1], &marker, 1) != 1) {
        return fail("pipe-write");
    }

    invalid.sequence = 0u;
    if (
        pocketpc_fd_transport_send(
            sockets[0],
            pipe_fds[0],
            &invalid
        ) != POCKETPC_FD_TRANSPORT_INVALID_ARGUMENT
    ) {
        return fail("zero-sequence-accepted");
    }

    if (
        pocketpc_fd_transport_send(
            sockets[0],
            pipe_fds[0],
            &sent
        ) != POCKETPC_FD_TRANSPORT_OK
    ) {
        return fail("send");
    }

    if (
        pocketpc_fd_transport_receive(
            sockets[1],
            &received_fd,
            &received
        ) != POCKETPC_FD_TRANSPORT_OK
    ) {
        return fail("receive");
    }

    if (
        received.resource_id != sent.resource_id ||
        received.generation != sent.generation ||
        received.sequence != sent.sequence
    ) {
        return fail("token-mismatch");
    }
    if (received_fd < 0 || received_fd == pipe_fds[0]) {
        return fail("descriptor-not-duplicated");
    }
    if ((fcntl(received_fd, F_GETFD) & FD_CLOEXEC) == 0) {
        return fail("cloexec-missing");
    }
    if (read(received_fd, &byte, 1) != 1 || byte != marker) {
        return fail("descriptor-content-mismatch");
    }

    close(received_fd);
    close(pipe_fds[0]);
    close(pipe_fds[1]);
    close(sockets[0]);
    close(sockets[1]);

    puts("FD_TRANSPORT_SMOKE_OK");
    puts("fd_number_serialized=false");
    puts("single_scm_rights_descriptor=true");
    puts("cloexec=true");
    puts("vulkan_resource_import_executed=false");
    return 0;
}
