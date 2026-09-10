#ifndef POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PROTOCOL_H
#define POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PROTOCOL_H

#include "pocketpc_graphics_transport.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_MAGIC 0x31535650u /* "PVS1" little-endian */
#define POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_VERSION 1u
#define POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PAYLOAD_BYTES 40u
#define POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_ROLE_FRAME_OWNERSHIP 1u

enum pocketpc_external_timeline_semaphore_fd_result {
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_OK = 0,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_INVALID_ARGUMENT = -1,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_WRONG_SOCKET_TYPE = -2,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_IO_FAILED = -3,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_TRUNCATED = -4,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_CONTROL = -5,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_PAYLOAD = -6,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_IDENTITY_MISMATCH = -7,
    POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_CLOEXEC_FAILED = -8,
};

struct pocketpc_external_timeline_semaphore_fd_metadata {
    uint64_t resource_id;
    uint64_t generation;
    uint64_t initial_value;
    uint32_t role;
};

struct pocketpc_external_timeline_semaphore_fd_received {
    int semaphore_fd;
    struct pocketpc_external_timeline_semaphore_fd_metadata metadata;
};

int pocketpc_external_timeline_semaphore_fd_receive(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_external_timeline_semaphore_fd_received *received
);

void pocketpc_external_timeline_semaphore_fd_received_release(
    struct pocketpc_external_timeline_semaphore_fd_received *received
);

#ifdef __cplusplus
}
#endif

#endif
