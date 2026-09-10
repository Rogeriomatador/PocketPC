#define _GNU_SOURCE

#include "pocketpc_fd_transport.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#define PFD_MAGIC_OFFSET 0u
#define PFD_VERSION_OFFSET 4u
#define PFD_RESERVED_OFFSET 6u
#define PFD_RESOURCE_ID_OFFSET 8u
#define PFD_GENERATION_OFFSET 16u
#define PFD_SEQUENCE_OFFSET 24u

static void pfd_put_u16_le(
    uint8_t *buffer,
    uint16_t value
) {
    buffer[0] = (uint8_t)(value & 0xffu);
    buffer[1] = (uint8_t)((value >> 8u) & 0xffu);
}

static void pfd_put_u32_le(
    uint8_t *buffer,
    uint32_t value
) {
    buffer[0] = (uint8_t)(value & 0xffu);
    buffer[1] = (uint8_t)((value >> 8u) & 0xffu);
    buffer[2] = (uint8_t)((value >> 16u) & 0xffu);
    buffer[3] = (uint8_t)((value >> 24u) & 0xffu);
}

static void pfd_put_u64_le(
    uint8_t *buffer,
    uint64_t value
) {
    unsigned int index;
    for (index = 0; index < 8u; ++index) {
        buffer[index] =
            (uint8_t)((value >> (index * 8u)) & 0xffu);
    }
}

static uint16_t pfd_get_u16_le(
    const uint8_t *buffer
) {
    return
        (uint16_t)buffer[0] |
        ((uint16_t)buffer[1] << 8u);
}

static uint32_t pfd_get_u32_le(
    const uint8_t *buffer
) {
    return
        (uint32_t)buffer[0] |
        ((uint32_t)buffer[1] << 8u) |
        ((uint32_t)buffer[2] << 16u) |
        ((uint32_t)buffer[3] << 24u);
}

static uint64_t pfd_get_u64_le(
    const uint8_t *buffer
) {
    uint64_t value = 0;
    unsigned int index;
    for (index = 0; index < 8u; ++index) {
        value |= ((uint64_t)buffer[index]) << (index * 8u);
    }
    return value;
}

static int pfd_token_valid(
    const struct pocketpc_fd_transport_token *token
) {
    return
        token != NULL &&
        token->resource_id != 0u &&
        token->generation != 0u &&
        token->sequence != 0u;
}

static void pfd_encode_token(
    uint8_t payload[POCKETPC_FD_TRANSPORT_TOKEN_BYTES],
    const struct pocketpc_fd_transport_token *token
) {
    memset(payload, 0, POCKETPC_FD_TRANSPORT_TOKEN_BYTES);
    pfd_put_u32_le(
        payload + PFD_MAGIC_OFFSET,
        POCKETPC_FD_TRANSPORT_MAGIC
    );
    pfd_put_u16_le(
        payload + PFD_VERSION_OFFSET,
        POCKETPC_FD_TRANSPORT_VERSION
    );
    pfd_put_u16_le(
        payload + PFD_RESERVED_OFFSET,
        0u
    );
    pfd_put_u64_le(
        payload + PFD_RESOURCE_ID_OFFSET,
        token->resource_id
    );
    pfd_put_u64_le(
        payload + PFD_GENERATION_OFFSET,
        token->generation
    );
    pfd_put_u64_le(
        payload + PFD_SEQUENCE_OFFSET,
        token->sequence
    );
}

static int pfd_decode_token(
    const uint8_t payload[POCKETPC_FD_TRANSPORT_TOKEN_BYTES],
    struct pocketpc_fd_transport_token *token
) {
    if (
        payload == NULL ||
        token == NULL ||
        pfd_get_u32_le(payload + PFD_MAGIC_OFFSET) !=
            POCKETPC_FD_TRANSPORT_MAGIC ||
        pfd_get_u16_le(payload + PFD_VERSION_OFFSET) !=
            POCKETPC_FD_TRANSPORT_VERSION ||
        pfd_get_u16_le(payload + PFD_RESERVED_OFFSET) != 0u
    ) {
        return 0;
    }

    token->resource_id =
        pfd_get_u64_le(payload + PFD_RESOURCE_ID_OFFSET);
    token->generation =
        pfd_get_u64_le(payload + PFD_GENERATION_OFFSET);
    token->sequence =
        pfd_get_u64_le(payload + PFD_SEQUENCE_OFFSET);
    return pfd_token_valid(token);
}

static int pfd_set_cloexec(
    int fd
) {
    int flags;

    if (fd < 0) {
        return 0;
    }
    flags = fcntl(fd, F_GETFD);
    if (flags < 0) {
        return 0;
    }
    if ((flags & FD_CLOEXEC) != 0) {
        return 1;
    }
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0;
}

static int pfd_socket_type(
    int socket_fd
) {
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);

    if (
        getsockopt(
            socket_fd,
            SOL_SOCKET,
            SO_TYPE,
            &socket_type,
            &length
        ) != 0 ||
        length != sizeof(socket_type)
    ) {
        return -1;
    }
    return socket_type;
}

static void pfd_close_received_rights(
    struct msghdr *message
) {
    struct cmsghdr *control_header;

    if (message == NULL) {
        return;
    }

    for (
        control_header = CMSG_FIRSTHDR(message);
        control_header != NULL;
        control_header = CMSG_NXTHDR(message, control_header)
    ) {
        size_t payload_bytes;
        size_t descriptor_count;
        size_t index;
        int *descriptors;

        if (
            control_header->cmsg_level != SOL_SOCKET ||
            control_header->cmsg_type != SCM_RIGHTS ||
            control_header->cmsg_len < CMSG_LEN(0)
        ) {
            continue;
        }

        payload_bytes =
            (size_t)control_header->cmsg_len - CMSG_LEN(0);
        descriptor_count = payload_bytes / sizeof(int);
        descriptors = (int *)CMSG_DATA(control_header);
        for (index = 0; index < descriptor_count; ++index) {
            if (descriptors[index] >= 0) {
                close(descriptors[index]);
            }
        }
    }
}

int pocketpc_fd_transport_send(
    int socket_fd,
    int fd_to_send,
    const struct pocketpc_fd_transport_token *token
) {
    uint8_t payload[POCKETPC_FD_TRANSPORT_TOKEN_BYTES];
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr message;
    struct cmsghdr *control_header;
    ssize_t written;
    int socket_type;

    if (
        socket_fd < 0 ||
        fd_to_send < 0 ||
        !pfd_token_valid(token)
    ) {
        return POCKETPC_FD_TRANSPORT_INVALID_ARGUMENT;
    }

    socket_type = pfd_socket_type(socket_fd);
    if (socket_type < 0) {
        return POCKETPC_FD_TRANSPORT_IO_FAILED;
    }
    if (socket_type != SOCK_SEQPACKET) {
        return POCKETPC_FD_TRANSPORT_WRONG_SOCKET_TYPE;
    }

    pfd_encode_token(payload, token);
    memset(control, 0, sizeof(control));
    memset(&message, 0, sizeof(message));

    iov.iov_base = payload;
    iov.iov_len = sizeof(payload);
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    control_header = CMSG_FIRSTHDR(&message);
    if (control_header == NULL) {
        return POCKETPC_FD_TRANSPORT_BAD_CONTROL;
    }
    control_header->cmsg_level = SOL_SOCKET;
    control_header->cmsg_type = SCM_RIGHTS;
    control_header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(control_header), &fd_to_send, sizeof(fd_to_send));
    message.msg_controllen = CMSG_SPACE(sizeof(int));

    do {
        written = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    } while (written < 0 && errno == EINTR);

    if (written < 0) {
        return POCKETPC_FD_TRANSPORT_IO_FAILED;
    }
    if ((size_t)written != sizeof(payload)) {
        return POCKETPC_FD_TRANSPORT_TRUNCATED;
    }
    return POCKETPC_FD_TRANSPORT_OK;
}

int pocketpc_fd_transport_receive(
    int socket_fd,
    int *received_fd,
    struct pocketpc_fd_transport_token *token
) {
    uint8_t payload[POCKETPC_FD_TRANSPORT_TOKEN_BYTES];
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr message;
    struct cmsghdr *control_header;
    ssize_t received;
    int candidate_fd = -1;
    unsigned int rights_count = 0u;
    int receive_flags = 0;
    int socket_type;
    int invalid_control = 0;

    if (received_fd != NULL) {
        *received_fd = -1;
    }
    if (
        socket_fd < 0 ||
        received_fd == NULL ||
        token == NULL
    ) {
        return POCKETPC_FD_TRANSPORT_INVALID_ARGUMENT;
    }

    socket_type = pfd_socket_type(socket_fd);
    if (socket_type < 0) {
        return POCKETPC_FD_TRANSPORT_IO_FAILED;
    }
    if (socket_type != SOCK_SEQPACKET) {
        return POCKETPC_FD_TRANSPORT_WRONG_SOCKET_TYPE;
    }

    memset(payload, 0, sizeof(payload));
    memset(control, 0, sizeof(control));
    memset(&message, 0, sizeof(message));

    iov.iov_base = payload;
    iov.iov_len = sizeof(payload);
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

#ifdef MSG_CMSG_CLOEXEC
    receive_flags |= MSG_CMSG_CLOEXEC;
#endif

    do {
        received = recvmsg(socket_fd, &message, receive_flags);
    } while (received < 0 && errno == EINTR);

    if (received < 0) {
        return POCKETPC_FD_TRANSPORT_IO_FAILED;
    }
    if (
        (size_t)received != sizeof(payload) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0
    ) {
        pfd_close_received_rights(&message);
        return POCKETPC_FD_TRANSPORT_TRUNCATED;
    }

    for (
        control_header = CMSG_FIRSTHDR(&message);
        control_header != NULL;
        control_header = CMSG_NXTHDR(&message, control_header)
    ) {
        if (
            control_header->cmsg_level != SOL_SOCKET ||
            control_header->cmsg_type != SCM_RIGHTS ||
            control_header->cmsg_len != CMSG_LEN(sizeof(int))
        ) {
            invalid_control = 1;
            continue;
        }

        ++rights_count;
        if (rights_count != 1u) {
            invalid_control = 1;
            continue;
        }
        memcpy(
            &candidate_fd,
            CMSG_DATA(control_header),
            sizeof(candidate_fd)
        );
    }

    if (
        invalid_control ||
        rights_count != 1u ||
        candidate_fd < 0
    ) {
        pfd_close_received_rights(&message);
        return POCKETPC_FD_TRANSPORT_BAD_CONTROL;
    }

    if (!pfd_decode_token(payload, token)) {
        close(candidate_fd);
        return POCKETPC_FD_TRANSPORT_BAD_TOKEN;
    }

    if (!pfd_set_cloexec(candidate_fd)) {
        close(candidate_fd);
        return POCKETPC_FD_TRANSPORT_CLOEXEC_FAILED;
    }

    *received_fd = candidate_fd;
    return POCKETPC_FD_TRANSPORT_OK;
}
