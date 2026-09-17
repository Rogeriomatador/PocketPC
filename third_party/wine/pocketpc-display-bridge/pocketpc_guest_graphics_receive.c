#include "pocketpc_guest_graphics_receive.h"

#include <string.h>
#include <unistd.h>

int pocketpc_guest_graphics_receive_offer(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_guest_graphics_received_offer *received
) {
    struct pocketpc_fd_transport_token token;
    int resource_fd = -1;
    int transport_result;
    int binding_result;

    if (
        socket_fd < 0 ||
        descriptor == NULL ||
        ownership == NULL ||
        received == NULL
    ) {
        return POCKETPC_GUEST_GRAPHICS_RECEIVE_INVALID_ARGUMENT;
    }

    received->resource_fd = -1;
    memset(&received->token, 0, sizeof(received->token));
    memset(&token, 0, sizeof(token));

    transport_result =
        pocketpc_fd_transport_receive(
            socket_fd,
            &resource_fd,
            &token
        );
    if (transport_result != POCKETPC_FD_TRANSPORT_OK) {
        return POCKETPC_GUEST_GRAPHICS_RECEIVE_TRANSPORT_FAILED;
    }

    binding_result =
        pocketpc_graphics_handle_binding_validate_offer(
            descriptor,
            ownership,
            &token
        );
    if (binding_result != POCKETPC_GRAPHICS_HANDLE_BINDING_OK) {
        close(resource_fd);
        return POCKETPC_GUEST_GRAPHICS_RECEIVE_BINDING_FAILED;
    }

    received->resource_fd = resource_fd;
    received->token = token;
    return POCKETPC_GUEST_GRAPHICS_RECEIVE_OK;
}

void pocketpc_guest_graphics_received_offer_release(
    struct pocketpc_guest_graphics_received_offer *received
) {
    if (received == NULL) {
        return;
    }
    if (received->resource_fd >= 0) {
        close(received->resource_fd);
        received->resource_fd = -1;
    }
    memset(&received->token, 0, sizeof(received->token));
}
