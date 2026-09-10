#ifndef POCKETPC_FD_TRANSPORT_H
#define POCKETPC_FD_TRANSPORT_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define POCKETPC_FD_TRANSPORT_MAGIC 0x31444650u /* "PFD1" little-endian */
#define POCKETPC_FD_TRANSPORT_VERSION 1u
#define POCKETPC_FD_TRANSPORT_TOKEN_BYTES 32u

struct pocketpc_fd_transport_token {
    uint64_t resource_id;
    uint64_t generation;
    uint64_t sequence;
};

enum pocketpc_fd_transport_result {
    POCKETPC_FD_TRANSPORT_OK = 0,
    POCKETPC_FD_TRANSPORT_INVALID_ARGUMENT = -1,
    POCKETPC_FD_TRANSPORT_IO_FAILED = -2,
    POCKETPC_FD_TRANSPORT_TRUNCATED = -3,
    POCKETPC_FD_TRANSPORT_BAD_CONTROL = -4,
    POCKETPC_FD_TRANSPORT_BAD_TOKEN = -5,
    POCKETPC_FD_TRANSPORT_CLOEXEC_FAILED = -6,
};

/*
 * Send exactly one kernel-owned file descriptor out-of-band with SCM_RIGHTS.
 * The descriptor number itself is never serialized in the token payload.
 */
int pocketpc_fd_transport_send(
    int socket_fd,
    int fd_to_send,
    const struct pocketpc_fd_transport_token *token
);

/*
 * Receive exactly one SCM_RIGHTS descriptor and its versioned identity token.
 * On success, ownership of *received_fd is transferred to the caller and the
 * descriptor is guaranteed to have FD_CLOEXEC set. On failure no descriptor
 * ownership is transferred and *received_fd remains -1.
 */
int pocketpc_fd_transport_receive(
    int socket_fd,
    int *received_fd,
    struct pocketpc_fd_transport_token *token
);

#ifdef __cplusplus
}
#endif

#endif
