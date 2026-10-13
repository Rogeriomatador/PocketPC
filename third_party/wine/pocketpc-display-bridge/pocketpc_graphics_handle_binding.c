#include "pocketpc_graphics_handle_binding.h"

int pocketpc_graphics_handle_binding_validate_offer(
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    const struct pocketpc_fd_transport_token *token
) {
    if (!descriptor || !ownership || !token) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_ARGUMENT;
    }
    if (pgt_validate_resource_descriptor(descriptor) != 0) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_DESCRIPTOR;
    }
    if (
        ownership->resource_id == 0u ||
        ownership->generation == 0u ||
        ownership->sequence == 0u ||
        ownership->reserved != 0u
    ) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_OWNERSHIP;
    }
    if (ownership->state != PGT_STATE_OFFERED_TO_GUEST) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_WRONG_STATE;
    }
    if (
        descriptor->resource_id != ownership->resource_id ||
        descriptor->resource_id != token->resource_id
    ) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_RESOURCE_MISMATCH;
    }
    if (
        descriptor->generation != ownership->generation ||
        descriptor->generation != token->generation
    ) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_GENERATION_MISMATCH;
    }
    if (
        descriptor->sync_sequence != ownership->sequence ||
        descriptor->sync_sequence != token->sequence
    ) {
        return POCKETPC_GRAPHICS_HANDLE_BINDING_SEQUENCE_MISMATCH;
    }
    return POCKETPC_GRAPHICS_HANDLE_BINDING_OK;
}
