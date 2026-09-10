#include "pocketpc_graphics_handle_binding.h"

#include <stdio.h>
#include <string.h>

static int fail(const char *message) {
    fprintf(stderr, "GRAPHICS_HANDLE_BINDING_SMOKE_FAILED:%s\n", message);
    return 1;
}

int main(void) {
    struct pgt_resource_descriptor descriptor;
    struct pgt_ownership_record ownership;
    struct pocketpc_fd_transport_token token;
    struct pgt_ownership_record changed_ownership;
    struct pocketpc_fd_transport_token changed_token;

    memset(&descriptor, 0, sizeof(descriptor));
    descriptor.resource_id = 17u;
    descriptor.generation = 4u;
    descriptor.width = 1280u;
    descriptor.height = 720u;
    descriptor.layers = 1u;
    descriptor.pixel_format = 1u;
    descriptor.usage = 3u;
    descriptor.producer_pid = 100u;
    descriptor.process_namespace = 200u;
    descriptor.sync_sequence = 9u;

    memset(&ownership, 0, sizeof(ownership));
    ownership.resource_id = descriptor.resource_id;
    ownership.generation = descriptor.generation;
    ownership.sequence = descriptor.sync_sequence;
    ownership.state = PGT_STATE_OFFERED_TO_GUEST;

    token.resource_id = descriptor.resource_id;
    token.generation = descriptor.generation;
    token.sequence = descriptor.sync_sequence;

    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &ownership,
            &token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_OK
    ) {
        return fail("valid-offer-rejected");
    }

    changed_token = token;
    changed_token.resource_id += 1u;
    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &ownership,
            &changed_token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_RESOURCE_MISMATCH
    ) {
        return fail("resource-mismatch-not-rejected");
    }

    changed_token = token;
    changed_token.generation += 1u;
    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &ownership,
            &changed_token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_GENERATION_MISMATCH
    ) {
        return fail("generation-mismatch-not-rejected");
    }

    changed_token = token;
    changed_token.sequence += 1u;
    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &ownership,
            &changed_token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_SEQUENCE_MISMATCH
    ) {
        return fail("sequence-mismatch-not-rejected");
    }

    changed_ownership = ownership;
    changed_ownership.state = PGT_STATE_GUEST_IMPORTED;
    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &changed_ownership,
            &token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_WRONG_STATE
    ) {
        return fail("wrong-state-not-rejected");
    }

    descriptor.resource_id = 0u;
    if (
        pocketpc_graphics_handle_binding_validate_offer(
            &descriptor,
            &ownership,
            &token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_INVALID_DESCRIPTOR
    ) {
        return fail("invalid-descriptor-not-rejected");
    }

    puts("GRAPHICS_HANDLE_BINDING_SMOKE_OK");
    puts("resource_identity_guard=true");
    puts("generation_guard=true");
    puts("sequence_guard=true");
    puts("offered_state_required=true");
    puts("vulkan_import_executed=false");
    puts("gpu_synchronization_executed=false");
    return 0;
}
