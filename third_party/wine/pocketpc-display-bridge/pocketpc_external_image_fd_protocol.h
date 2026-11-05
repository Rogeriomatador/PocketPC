#ifndef POCKETPC_EXTERNAL_IMAGE_FD_PROTOCOL_H
#define POCKETPC_EXTERNAL_IMAGE_FD_PROTOCOL_H

#include "pocketpc_graphics_transport.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define POCKETPC_EXTERNAL_IMAGE_FD_MAGIC 0x31495650u /* "PVI1" little-endian */
#define POCKETPC_EXTERNAL_IMAGE_FD_VERSION 1u
#define POCKETPC_EXTERNAL_IMAGE_FD_PAYLOAD_BYTES 64u
#define POCKETPC_EXTERNAL_IMAGE_MAX_DIMENSION 4096u
#define POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM 37u
#define POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS 0x17u

enum pocketpc_external_image_fd_result {
    POCKETPC_EXTERNAL_IMAGE_FD_OK = 0,
    POCKETPC_EXTERNAL_IMAGE_FD_INVALID_ARGUMENT = -1,
    POCKETPC_EXTERNAL_IMAGE_FD_WRONG_SOCKET_TYPE = -2,
    POCKETPC_EXTERNAL_IMAGE_FD_IO_FAILED = -3,
    POCKETPC_EXTERNAL_IMAGE_FD_TRUNCATED = -4,
    POCKETPC_EXTERNAL_IMAGE_FD_BAD_CONTROL = -5,
    POCKETPC_EXTERNAL_IMAGE_FD_BAD_PAYLOAD = -6,
    POCKETPC_EXTERNAL_IMAGE_FD_IDENTITY_MISMATCH = -7,
    POCKETPC_EXTERNAL_IMAGE_FD_CLOEXEC_FAILED = -8,
};

struct pocketpc_external_image_fd_metadata {
    uint64_t resource_id;
    uint64_t generation;
    uint64_t sequence;
    uint32_t width;
    uint32_t height;
    uint32_t format;
    uint32_t usage;
    uint64_t allocation_size;
    uint32_t memory_type_bits;
    uint32_t memory_type_index;
};

struct pocketpc_external_image_fd_received {
    int resource_fd;
    struct pocketpc_external_image_fd_metadata metadata;
};

/*
 * Receive one image-memory FD and the complete immutable import metadata in a
 * single SOCK_SEQPACKET message. The metadata is bound to the already
 * authenticated graphics resource descriptor and ownership offer. No Vulkan
 * import happens here.
 */
int pocketpc_external_image_fd_receive(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_external_image_fd_received *received
);

void pocketpc_external_image_fd_received_release(
    struct pocketpc_external_image_fd_received *received
);

#ifdef __cplusplus
}
#endif

#endif
