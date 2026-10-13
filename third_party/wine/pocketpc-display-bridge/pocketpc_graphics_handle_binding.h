#ifndef POCKETPC_GRAPHICS_HANDLE_BINDING_H
#define POCKETPC_GRAPHICS_HANDLE_BINDING_H

#include "pocketpc_fd_transport.h"
#include "pocketpc_graphics_transport.h"

#ifdef __cplusplus
extern "C" {
#endif

enum pocketpc_graphics_handle_binding_result {
    POCKETPC_GRAPHICS_HANDLE_BINDING_OK = 0,
    POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_ARGUMENT = -1,
    POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_DESCRIPTOR = -2,
    POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_OWNERSHIP = -3,
    POCKETPC_GRAPHICS_HANDLE_BINDING_RESOURCE_MISMATCH = -4,
    POCKETPC_GRAPHICS_HANDLE_BINDING_GENERATION_MISMATCH = -5,
    POCKETPC_GRAPHICS_HANDLE_BINDING_SEQUENCE_MISMATCH = -6,
    POCKETPC_GRAPHICS_HANDLE_BINDING_WRONG_STATE = -7,
};

/*
 * Bind one out-of-band SCM_RIGHTS handle to one exact graphics-resource offer.
 * This validates identity only; it does not import memory into Vulkan and does
 * not prove GPU synchronization or presentation.
 */
int pocketpc_graphics_handle_binding_validate_offer(
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    const struct pocketpc_fd_transport_token *token
);

#ifdef __cplusplus
}
#endif

#endif
