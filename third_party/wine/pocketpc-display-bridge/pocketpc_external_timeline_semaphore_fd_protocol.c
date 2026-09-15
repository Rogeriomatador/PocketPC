#define _GNU_SOURCE

#include "pocketpc_external_timeline_semaphore_fd_protocol.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#define PVS_MAGIC_OFFSET 0u
#define PVS_VERSION_OFFSET 4u
#define PVS_RESERVED_OFFSET 6u
#define PVS_RESOURCE_ID_OFFSET 8u
#define PVS_GENERATION_OFFSET 16u
#define PVS_INITIAL_VALUE_OFFSET 24u
#define PVS_ROLE_OFFSET 32u
#define PVS_RESERVED2_OFFSET 36u

static uint16_t pvs_get_u16_le(const uint8_t *buffer)
{
    return (uint16_t)buffer[0] | ((uint16_t)buffer[1] << 8u);
}

static uint32_t pvs_get_u32_le(const uint8_t *buffer)
{
    return
        (uint32_t)buffer[0] |
        ((uint32_t)buffer[1] << 8u) |
        ((uint32_t)buffer[2] << 16u) |
        ((uint32_t)buffer[3] << 24u);
}

static uint64_t pvs_get_u64_le(const uint8_t *buffer)
{
    uint64_t value = 0;
    unsigned int index;
    for (index = 0; index < 8u; ++index)
        value |= ((uint64_t)buffer[index]) << (index * 8u);
    return value;
}

static int pvs_socket_is_seqpacket(int socket_fd)
{
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);
    if (socket_fd < 0) return 0;
    if (getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) != 0)
        return 0;
    return socket_type == SOCK_SEQPACKET;
}

static int pvs_set_cloexec(int fd)
{
    int flags;
    if (fd < 0) return 0;
    flags = fcntl(fd, F_GETFD);
    if (flags < 0) return 0;
    if ((flags & FD_CLOEXEC) != 0) return 1;
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0;
}

static void pvs_close_rights(const struct msghdr *message)
{
    const struct cmsghdr *header;
    if (!message) return;
    for (
        header = CMSG_FIRSTHDR((struct msghdr *)message);
        header != NULL;
        header = CMSG_NXTHDR((struct msghdr *)message, (struct cmsghdr *)header)
    ) {
        size_t data_bytes;
        size_t count;
        size_t i;
        const int *fds;
        if (
            header->cmsg_level != SOL_SOCKET ||
            header->cmsg_type != SCM_RIGHTS ||
            header->cmsg_len < CMSG_LEN(sizeof(int))
        ) {
            continue;
        }
        data_bytes = header->cmsg_len - CMSG_LEN(0);
        count = data_bytes / sizeof(int);
        fds = (const int *)CMSG_DATA((struct cmsghdr *)header);
        for (i = 0; i < count; ++i)
            if (fds[i] >= 0) close(fds[i]);
    }
}

static int pvs_decode(
    const uint8_t payload[POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PAYLOAD_BYTES],
    struct pocketpc_external_timeline_semaphore_fd_metadata *metadata
) {
    if (!payload || !metadata) return 0;
    if (pvs_get_u32_le(payload + PVS_MAGIC_OFFSET) != POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_MAGIC)
        return 0;
    if (pvs_get_u16_le(payload + PVS_VERSION_OFFSET) != POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_VERSION)
        return 0;
    if (pvs_get_u16_le(payload + PVS_RESERVED_OFFSET) != 0u)
        return 0;
    if (pvs_get_u32_le(payload + PVS_RESERVED2_OFFSET) != 0u)
        return 0;

    memset(metadata, 0, sizeof(*metadata));
    metadata->resource_id = pvs_get_u64_le(payload + PVS_RESOURCE_ID_OFFSET);
    metadata->generation = pvs_get_u64_le(payload + PVS_GENERATION_OFFSET);
    metadata->initial_value = pvs_get_u64_le(payload + PVS_INITIAL_VALUE_OFFSET);
    metadata->role = pvs_get_u32_le(payload + PVS_ROLE_OFFSET);

    return
        metadata->resource_id != 0u &&
        metadata->generation != 0u &&
        metadata->initial_value == 0u &&
        metadata->role == POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_ROLE_FRAME_OWNERSHIP;
}

static int pvs_matches_offer(
    const struct pocketpc_external_timeline_semaphore_fd_metadata *metadata,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership
) {
    if (!metadata || !descriptor || !ownership) return 0;
    if (pgt_validate_resource_descriptor(descriptor) != 0) return 0;
    return
        ownership->reserved == 0u &&
        ownership->state == PGT_STATE_OFFERED_TO_GUEST &&
        ownership->resource_id == descriptor->resource_id &&
        ownership->generation == descriptor->generation &&
        ownership->sequence == descriptor->sync_sequence &&
        metadata->resource_id == descriptor->resource_id &&
        metadata->generation == descriptor->generation;
}

int pocketpc_external_timeline_semaphore_fd_receive(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_external_timeline_semaphore_fd_received *received
) {
    uint8_t payload[POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_PAYLOAD_BYTES];
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr message;
    struct cmsghdr *header;
    struct pocketpc_external_timeline_semaphore_fd_metadata metadata;
    ssize_t byte_count;
    int flags = 0;
    int candidate_fd = -1;
    unsigned int rights_count = 0u;

    if (received) {
        received->semaphore_fd = -1;
        memset(&received->metadata, 0, sizeof(received->metadata));
    }
    if (!descriptor || !ownership || !received || socket_fd < 0)
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_INVALID_ARGUMENT;
    if (!pvs_socket_is_seqpacket(socket_fd))
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_WRONG_SOCKET_TYPE;

    memset(payload, 0, sizeof(payload));
    memset(control, 0, sizeof(control));
    memset(&iov, 0, sizeof(iov));
    memset(&message, 0, sizeof(message));
    memset(&metadata, 0, sizeof(metadata));

    iov.iov_base = payload;
    iov.iov_len = sizeof(payload);
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);
#ifdef MSG_CMSG_CLOEXEC
    flags |= MSG_CMSG_CLOEXEC;
#endif

    do {
        byte_count = recvmsg(socket_fd, &message, flags);
    } while (byte_count < 0 && errno == EINTR);

    if (byte_count < 0)
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_IO_FAILED;
    if (
        (size_t)byte_count != sizeof(payload) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0
    ) {
        pvs_close_rights(&message);
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_TRUNCATED;
    }

    for (
        header = CMSG_FIRSTHDR(&message);
        header != NULL;
        header = CMSG_NXTHDR(&message, header)
    ) {
        if (
            header->cmsg_level != SOL_SOCKET ||
            header->cmsg_type != SCM_RIGHTS ||
            header->cmsg_len != CMSG_LEN(sizeof(int))
        ) {
            pvs_close_rights(&message);
            return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_CONTROL;
        }
        ++rights_count;
        if (rights_count != 1u) {
            pvs_close_rights(&message);
            return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_CONTROL;
        }
        memcpy(&candidate_fd, CMSG_DATA(header), sizeof(candidate_fd));
    }

    if (rights_count != 1u || candidate_fd < 0) {
        pvs_close_rights(&message);
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_CONTROL;
    }
    if (!pvs_decode(payload, &metadata)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_BAD_PAYLOAD;
    }
    if (!pvs_matches_offer(&metadata, descriptor, ownership)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_IDENTITY_MISMATCH;
    }
    if (!pvs_set_cloexec(candidate_fd)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_CLOEXEC_FAILED;
    }

    received->semaphore_fd = candidate_fd;
    received->metadata = metadata;
    return POCKETPC_EXTERNAL_TIMELINE_SEMAPHORE_FD_OK;
}

void pocketpc_external_timeline_semaphore_fd_received_release(
    struct pocketpc_external_timeline_semaphore_fd_received *received
) {
    if (!received) return;
    if (received->semaphore_fd >= 0) {
        close(received->semaphore_fd);
        received->semaphore_fd = -1;
    }
    memset(&received->metadata, 0, sizeof(received->metadata));
}
