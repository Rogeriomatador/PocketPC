#ifndef POCKETPC_GUEST_GRAPHICS_RECEIVE_H
#define POCKETPC_GUEST_GRAPHICS_RECEIVE_H

#include "pocketpc_fd_transport.h"
#include "pocketpc_graphics_handle_binding.h"
#include "pocketpc_graphics_transport.h"

#ifdef __cplusplus
extern "C" {
#endif

enum pocketpc_guest_graphics_receive_result {
    POCKETPC_GUEST_GRAPHICS_RECEIVE_OK = 0,
    POCKETPC_GUEST_GRAPHICS_RECEIVE_INVALID_ARGUMENT = -1,
    POCKETPC_GUEST_GRAPHICS_RECEIVE_TRANSPORT_FAILED = -2,
    POCKETPC_GUEST_GRAPHICS_RECEIVE_BINDING_FAILED = -3,
};

struct pocketpc_guest_graphics_received_offer {
    int resource_fd;
    struct pocketpc_fd_transport_token token;
};

/*
 * Receive exactly one kernel-owned graphics-resource FD and bind it to one
 * exact metadata/ownership offer. This is a guest-side receive primitive only.
 * It does not import the descriptor into Vulkan, does not create a surface,
 * does not synchronize GPU ownership and does not prove DXVK/Roblox Present.
 */
int pocketpc_guest_graphics_receive_offer(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_guest_graphics_received_offer *received
);

/*
 * Release ownership of an offer returned by pocketpc_guest_graphics_receive_offer.
 * Safe to call repeatedly; resource_fd becomes -1.
 */
void pocketpc_guest_graphics_received_offer_release(
    struct pocketpc_guest_graphics_received_offer *received
);

#ifdef __cplusplus
}
#endif

#endif
