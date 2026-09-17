#ifndef POCKETPC_GRAPHICS_TRANSPORT_H
#define POCKETPC_GRAPHICS_TRANSPORT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Cross-ABI metadata protocol for a graphics resource shared by PocketPC.
 *
 * This protocol NEVER serializes ANativeWindow pointers, Vulkan object
 * pointers, AHardwareBuffer pointers, or process-local file-descriptor
 * numbers as resource identity. A real OS handle must travel out-of-band
 * through a transport that preserves kernel ownership semantics.
 */
#define PGT_MAGIC 0x31544750u /* "PGT1" little-endian */
#define PGT_VERSION 1u
#define PGT_HEADER_BYTES 24u
#define PGT_RESOURCE_DESCRIPTOR_BYTES 64u
#define PGT_OWNERSHIP_RECORD_BYTES 32u
#define PGT_RESOURCE_OFFER_PAYLOAD_BYTES \
    (PGT_RESOURCE_DESCRIPTOR_BYTES + PGT_OWNERSHIP_RECORD_BYTES)
#define PGT_RESOURCE_OFFER_FRAME_BYTES \
    (PGT_HEADER_BYTES + PGT_RESOURCE_OFFER_PAYLOAD_BYTES)
#define PGT_MAX_FRAME_BYTES 4096u

/* Authenticated SOCK_SEQPACKET session handshake ("PGH1"). */
#define PGT_SESSION_MAGIC 0x31484750u
#define PGT_SESSION_VERSION 1u
#define PGT_SESSION_TOKEN_BYTES 32u
#define PGT_SESSION_TOKEN_HEX_BYTES 64u
#define PGT_SESSION_HANDSHAKE_BYTES 48u
#define PGT_SESSION_MAX_SOCKET_NAME_BYTES 80u
#define PGT_SESSION_ENV_SOCKET_NAME "POCKETPC_GRAPHICS_SOCKET_NAME"
#define PGT_SESSION_ENV_TOKEN "POCKETPC_GRAPHICS_SESSION_TOKEN"
#define PGT_SESSION_ENV_PROTOCOL "POCKETPC_GRAPHICS_SESSION_PROTOCOL"

#define PGT_MSG_RESOURCE_OFFER 1u
#define PGT_MSG_GUEST_IMPORTED 2u
#define PGT_MSG_GUEST_RENDER_BEGIN 3u
#define PGT_MSG_GUEST_RENDER_COMPLETE 4u
#define PGT_MSG_HOST_PRESENT_BEGIN 5u
#define PGT_MSG_HOST_PRESENT_COMPLETE 6u
#define PGT_MSG_RESOURCE_RETIRE 7u
#define PGT_MSG_ERROR 255u

#define PGT_STATE_HOST_AVAILABLE 1u
#define PGT_STATE_OFFERED_TO_GUEST 2u
#define PGT_STATE_GUEST_IMPORTED 3u
#define PGT_STATE_GUEST_RENDERING 4u
#define PGT_STATE_GUEST_RENDER_COMPLETE 5u
#define PGT_STATE_HOST_PRESENTING 6u
#define PGT_STATE_RETIRED 7u

#define PGT_MAX_DIMENSION 16384u
#define PGT_MAX_LAYERS 256u

struct pgt_frame_header {
    uint32_t magic;
    uint16_t version;
    uint16_t type;
    uint32_t payload_bytes;
    uint32_t reserved;
    uint64_t sequence;
};

struct pgt_resource_descriptor {
    uint64_t resource_id;
    uint64_t generation;
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t pixel_format;
    uint64_t usage;
    uint32_t producer_pid;
    uint32_t reserved;
    uint64_t process_namespace;
    uint64_t sync_sequence;
};

struct pgt_ownership_record {
    uint64_t resource_id;
    uint64_t generation;
    uint64_t sequence;
    uint32_t state;
    uint32_t reserved;
};

int pgt_validate_header(
    const struct pgt_frame_header *header,
    uint16_t expected_type,
    uint32_t expected_payload_bytes
);

int pgt_validate_resource_descriptor(
    const struct pgt_resource_descriptor *descriptor
);

int pgt_next_ownership_state(
    uint32_t current_state,
    uint16_t event_type,
    uint32_t *next_state
);

int pgt_validate_ownership_transition(
    const struct pgt_ownership_record *current,
    uint16_t event_type,
    uint64_t next_sequence,
    struct pgt_ownership_record *next
);

/*
 * Receive the canonical descriptor + ownership offer sent by the Android host
 * before PVI1/PVS1. The packet contains no file descriptor or process-local
 * pointer. Both records are fully validated and must identify the same resource
 * in OFFERED_TO_GUEST state at the frame sequence.
 */
int pgt_receive_resource_offer(
    int socket_fd,
    struct pgt_resource_descriptor *descriptor,
    struct pgt_ownership_record *ownership
);

/*
 * Connect to the Android-hosted abstract AF_UNIX/SOCK_SEQPACKET endpoint and
 * authenticate the current process. The returned FD is CLOEXEC and owned by
 * the caller. No graphics capability is implied by a successful handshake.
 */
int pgt_connect_authenticated_session(
    const char *socket_name,
    const char *token_hex
);

int pgt_connect_authenticated_session_from_environment(void);

#ifdef __cplusplus
}
#endif

#endif
