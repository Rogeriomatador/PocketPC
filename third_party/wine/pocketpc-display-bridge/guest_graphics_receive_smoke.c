#include "pocketpc_guest_graphics_receive.h"

#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static int fail(const char *message) {
    fprintf(stderr, "GUEST_GRAPHICS_RECEIVE_SMOKE_FAILED:%s\n", message);
    return 1;
}

static void fill_offer(
    struct pgt_resource_descriptor *descriptor,
    struct pgt_ownership_record *ownership,
    uint64_t resource_id,
    uint64_t generation,
    uint64_t sequence
) {
    memset(descriptor, 0, sizeof(*descriptor));
    descriptor->resource_id = resource_id;
    descriptor->generation = generation;
    descriptor->width = 64u;
    descriptor->height = 64u;
    descriptor->layers = 1u;
    descriptor->pixel_format = 1u;
    descriptor->usage = 0u;
    descriptor->producer_pid = 123u;
    descriptor->process_namespace = 77u;
    descriptor->sync_sequence = sequence;

    memset(ownership, 0, sizeof(*ownership));
    ownership->resource_id = resource_id;
    ownership->generation = generation;
    ownership->sequence = sequence;
    ownership->state = PGT_STATE_OFFERED_TO_GUEST;
}

int main(void) {
    int sockets[2] = {-1, -1};
    int pipe_fds[2] = {-1, -1};
    char byte = '\0';
    const char marker = 'G';
    struct pgt_resource_descriptor descriptor;
    struct pgt_ownership_record ownership;
    struct pocketpc_fd_transport_token token = {
        .resource_id = 101u,
        .generation = 4u,
        .sequence = 9u,
    };
    struct pocketpc_guest_graphics_received_offer received = {
        .resource_fd = -1,
    };

    fill_offer(
        &descriptor,
        &ownership,
        token.resource_id,
        token.generation,
        token.sequence
    );

    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets) != 0) {
        return fail("socketpair");
    }
    if (pipe(pipe_fds) != 0) {
        return fail("pipe");
    }
    if (write(pipe_fds[1], &marker, 1) != 1) {
        return fail("pipe-write");
    }

    if (
        pocketpc_fd_transport_send(
            sockets[0],
            pipe_fds[0],
            &token
        ) != POCKETPC_FD_TRANSPORT_OK
    ) {
        return fail("send-valid");
    }

    if (
        pocketpc_guest_graphics_receive_offer(
            sockets[1],
            &descriptor,
            &ownership,
            &received
        ) != POCKETPC_GUEST_GRAPHICS_RECEIVE_OK
    ) {
        return fail("receive-valid");
    }

    if (
        received.resource_fd < 0 ||
        received.token.resource_id != token.resource_id ||
        received.token.generation != token.generation ||
        received.token.sequence != token.sequence
    ) {
        return fail("received-identity");
    }
    if ((fcntl(received.resource_fd, F_GETFD) & FD_CLOEXEC) == 0) {
        return fail("received-cloexec");
    }
    if (read(received.resource_fd, &byte, 1) != 1 || byte != marker) {
        return fail("received-content");
    }

    pocketpc_guest_graphics_received_offer_release(&received);
    if (received.resource_fd != -1) {
        return fail("release-idempotence-state");
    }
    pocketpc_guest_graphics_received_offer_release(&received);

    /* Mismatched metadata must reject a valid received fd instead of exposing it. */
    if (write(pipe_fds[1], &marker, 1) != 1) {
        return fail("pipe-write-mismatch");
    }
    if (
        pocketpc_fd_transport_send(
            sockets[0],
            pipe_fds[0],
            &token
        ) != POCKETPC_FD_TRANSPORT_OK
    ) {
        return fail("send-mismatch");
    }
    descriptor.generation += 1u;
    if (
        pocketpc_guest_graphics_receive_offer(
            sockets[1],
            &descriptor,
            &ownership,
            &received
        ) != POCKETPC_GUEST_GRAPHICS_RECEIVE_BINDING_FAILED
    ) {
        return fail("generation-mismatch-accepted");
    }
    if (received.resource_fd != -1) {
        return fail("rejected-fd-leaked-to-caller");
    }

    close(pipe_fds[0]);
    close(pipe_fds[1]);
    close(sockets[0]);
    close(sockets[1]);

    puts("GUEST_GRAPHICS_RECEIVE_SMOKE_OK");
    puts("fd_transport=SCM_RIGHTS");
    puts("identity_binding=resource_generation_sequence");
    puts("rejected_handle_exposed=false");
    puts("vulkan_import_executed=false");
    puts("gpu_synchronization_executed=false");
    puts("roblox_executed=false");
    return 0;
}
