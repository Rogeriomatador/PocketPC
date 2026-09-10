#include "pocketpc_graphics_transport.h"

#include <limits.h>
#include <string.h>

typedef char pgt_header_size_must_be_24[
    sizeof(struct pgt_frame_header) == PGT_HEADER_BYTES ? 1 : -1
];
typedef char pgt_descriptor_size_must_be_64[
    sizeof(struct pgt_resource_descriptor) == PGT_RESOURCE_DESCRIPTOR_BYTES ? 1 : -1
];

static int pgt_state_valid(uint32_t state)
{
    return state >= PGT_STATE_HOST_AVAILABLE &&
        state <= PGT_STATE_RETIRED;
}

int pgt_validate_header(
    const struct pgt_frame_header *header,
    uint16_t expected_type,
    uint32_t expected_payload_bytes
) {
    if (!header) return -1;
    if (header->magic != PGT_MAGIC) return -2;
    if (header->version != PGT_VERSION) return -3;
    if (header->type != expected_type) return -4;
    if (header->payload_bytes != expected_payload_bytes) return -5;
    if (header->payload_bytes > PGT_MAX_FRAME_BYTES) return -6;
    if (header->reserved != 0u) return -7;
    if (header->sequence == 0u) return -8;
    return 0;
}

int pgt_validate_resource_descriptor(
    const struct pgt_resource_descriptor *descriptor
) {
    uint64_t pixels;

    if (!descriptor) return -1;
    if (descriptor->resource_id == 0u) return -2;
    if (descriptor->generation == 0u) return -3;
    if (
        descriptor->width == 0u ||
        descriptor->width > PGT_MAX_DIMENSION ||
        descriptor->height == 0u ||
        descriptor->height > PGT_MAX_DIMENSION
    ) {
        return -4;
    }
    if (
        descriptor->layers == 0u ||
        descriptor->layers > PGT_MAX_LAYERS
    ) {
        return -5;
    }
    if (descriptor->pixel_format == 0u) return -6;
    if (descriptor->producer_pid == 0u) return -7;
    if (descriptor->reserved != 0u) return -8;
    if (descriptor->process_namespace == 0u) return -9;

    pixels = (uint64_t)descriptor->width * descriptor->height;
    if (
        descriptor->layers != 0u &&
        pixels > UINT64_MAX / descriptor->layers
    ) {
        return -10;
    }
    pixels *= descriptor->layers;
    if (pixels == 0u) return -11;

    return 0;
}

int pgt_next_ownership_state(
    uint32_t current_state,
    uint16_t event_type,
    uint32_t *next_state
) {
    uint32_t next;

    if (!next_state) return -1;
    if (!pgt_state_valid(current_state)) return -2;

    if (
        current_state == PGT_STATE_HOST_AVAILABLE &&
        event_type == PGT_MSG_RESOURCE_OFFER
    ) {
        next = PGT_STATE_OFFERED_TO_GUEST;
    } else if (
        current_state == PGT_STATE_OFFERED_TO_GUEST &&
        event_type == PGT_MSG_GUEST_IMPORTED
    ) {
        next = PGT_STATE_GUEST_IMPORTED;
    } else if (
        current_state == PGT_STATE_GUEST_IMPORTED &&
        event_type == PGT_MSG_GUEST_RENDER_BEGIN
    ) {
        next = PGT_STATE_GUEST_RENDERING;
    } else if (
        current_state == PGT_STATE_GUEST_RENDERING &&
        event_type == PGT_MSG_GUEST_RENDER_COMPLETE
    ) {
        next = PGT_STATE_GUEST_RENDER_COMPLETE;
    } else if (
        current_state == PGT_STATE_GUEST_RENDER_COMPLETE &&
        event_type == PGT_MSG_HOST_PRESENT_BEGIN
    ) {
        next = PGT_STATE_HOST_PRESENTING;
    } else if (
        current_state == PGT_STATE_HOST_PRESENTING &&
        event_type == PGT_MSG_HOST_PRESENT_COMPLETE
    ) {
        next = PGT_STATE_HOST_AVAILABLE;
    } else if (
        current_state == PGT_STATE_HOST_AVAILABLE &&
        event_type == PGT_MSG_RESOURCE_RETIRE
    ) {
        next = PGT_STATE_RETIRED;
    } else {
        return -3;
    }

    *next_state = next;
    return 0;
}

int pgt_validate_ownership_transition(
    const struct pgt_ownership_record *current,
    uint16_t event_type,
    uint64_t next_sequence,
    struct pgt_ownership_record *next
) {
    uint32_t next_state;
    int state_result;

    if (!current || !next) return -1;
    if (
        current->resource_id == 0u ||
        current->generation == 0u ||
        current->reserved != 0u ||
        !pgt_state_valid(current->state)
    ) {
        return -2;
    }
    if (current->sequence == UINT64_MAX) return -3;
    if (next_sequence != current->sequence + 1u) return -4;

    state_result = pgt_next_ownership_state(
        current->state,
        event_type,
        &next_state
    );
    if (state_result != 0) return -5;

    memset(next, 0, sizeof(*next));
    next->resource_id = current->resource_id;
    next->generation = current->generation;
    next->sequence = next_sequence;
    next->state = next_state;
    return 0;
}
