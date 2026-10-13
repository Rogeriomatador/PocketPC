#define _GNU_SOURCE

#include "pocketpc_external_image_fd_protocol.h"
#include "pocketpc_fd_transport.h"
#include "pocketpc_graphics_handle_binding.h"

#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#define PVI_MAGIC_OFFSET 0u
#define PVI_VERSION_OFFSET 4u
#define PVI_RESERVED_OFFSET 6u
#define PVI_RESOURCE_ID_OFFSET 8u
#define PVI_GENERATION_OFFSET 16u
#define PVI_SEQUENCE_OFFSET 24u
#define PVI_WIDTH_OFFSET 32u
#define PVI_HEIGHT_OFFSET 36u
#define PVI_FORMAT_OFFSET 40u
#define PVI_USAGE_OFFSET 44u
#define PVI_ALLOCATION_SIZE_OFFSET 48u
#define PVI_MEMORY_TYPE_BITS_OFFSET 56u
#define PVI_MEMORY_TYPE_INDEX_OFFSET 60u

static uint16_t pvi_get_u16_le(const uint8_t *buffer)
{
    return (uint16_t)buffer[0] | ((uint16_t)buffer[1] << 8u);
}

static uint32_t pvi_get_u32_le(const uint8_t *buffer)
{
    return
        (uint32_t)buffer[0] |
        ((uint32_t)buffer[1] << 8u) |
        ((uint32_t)buffer[2] << 16u) |
        ((uint32_t)buffer[3] << 24u);
}

static uint64_t pvi_get_u64_le(const uint8_t *buffer)
{
    uint64_t value = 0;
    unsigned int index;
    for (index = 0; index < 8u; ++index)
        value |= ((uint64_t)buffer[index]) << (index * 8u);
    return value;
}

static int pvi_socket_is_seqpacket(int socket_fd)
{
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);

    if (socket_fd < 0) return 0;
    if (getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) != 0)
        return 0;
    return socket_type == SOCK_SEQPACKET;
}

static int pvi_set_cloexec(int fd)
{
    int flags;

    if (fd < 0) return 0;
    flags = fcntl(fd, F_GETFD);
    if (flags < 0) return 0;
    if ((flags & FD_CLOEXEC) != 0) return 1;
    return fcntl(fd, F_SETFD, flags | FD_CLOEXEC) == 0;
}

static void pvi_close_rights(const struct msghdr *message)
{
    const struct cmsghdr *header;

    if (!message) return;
    for (
        header = CMSG_FIRSTHDR((struct msghdr *)message);
        header != NULL;
        header = CMSG_NXTHDR((struct msghdr *)message, (struct cmsghdr *)header)
    ) {
        size_t data_bytes;
        size_t descriptor_count;
        size_t index;
        const int *fds;

        if (
            header->cmsg_level != SOL_SOCKET ||
            header->cmsg_type != SCM_RIGHTS ||
            header->cmsg_len < CMSG_LEN(sizeof(int))
        ) {
            continue;
        }

        data_bytes = header->cmsg_len - CMSG_LEN(0);
        descriptor_count = data_bytes / sizeof(int);
        fds = (const int *)CMSG_DATA((struct cmsghdr *)header);
        for (index = 0; index < descriptor_count; ++index) {
            if (fds[index] >= 0) close(fds[index]);
        }
    }
}

static int pvi_decode(
    const uint8_t payload[POCKETPC_EXTERNAL_IMAGE_FD_PAYLOAD_BYTES],
    struct pocketpc_external_image_fd_metadata *metadata
) {
    if (!payload || !metadata) return 0;
    if (pvi_get_u32_le(payload + PVI_MAGIC_OFFSET) != POCKETPC_EXTERNAL_IMAGE_FD_MAGIC)
        return 0;
    if (pvi_get_u16_le(payload + PVI_VERSION_OFFSET) != POCKETPC_EXTERNAL_IMAGE_FD_VERSION)
        return 0;
    if (pvi_get_u16_le(payload + PVI_RESERVED_OFFSET) != 0u)
        return 0;

    memset(metadata, 0, sizeof(*metadata));
    metadata->resource_id = pvi_get_u64_le(payload + PVI_RESOURCE_ID_OFFSET);
    metadata->generation = pvi_get_u64_le(payload + PVI_GENERATION_OFFSET);
    metadata->sequence = pvi_get_u64_le(payload + PVI_SEQUENCE_OFFSET);
    metadata->width = pvi_get_u32_le(payload + PVI_WIDTH_OFFSET);
    metadata->height = pvi_get_u32_le(payload + PVI_HEIGHT_OFFSET);
    metadata->format = pvi_get_u32_le(payload + PVI_FORMAT_OFFSET);
    metadata->usage = pvi_get_u32_le(payload + PVI_USAGE_OFFSET);
    metadata->allocation_size = pvi_get_u64_le(payload + PVI_ALLOCATION_SIZE_OFFSET);
    metadata->memory_type_bits = pvi_get_u32_le(payload + PVI_MEMORY_TYPE_BITS_OFFSET);
    metadata->memory_type_index = pvi_get_u32_le(payload + PVI_MEMORY_TYPE_INDEX_OFFSET);

    if (
        metadata->resource_id == 0u ||
        metadata->generation == 0u ||
        metadata->sequence == 0u ||
        metadata->width == 0u ||
        metadata->width > POCKETPC_EXTERNAL_IMAGE_MAX_DIMENSION ||
        metadata->height == 0u ||
        metadata->height > POCKETPC_EXTERNAL_IMAGE_MAX_DIMENSION ||
        metadata->format != POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM ||
        metadata->usage != POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS ||
        metadata->allocation_size == 0u ||
        metadata->memory_type_bits == 0u ||
        metadata->memory_type_index >= 32u ||
        (metadata->memory_type_bits & (1u << metadata->memory_type_index)) == 0u
    ) {
        return 0;
    }
    return 1;
}

static int pvi_matches_offer(
    const struct pocketpc_external_image_fd_metadata *metadata,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership
) {
    struct pocketpc_fd_transport_token token;

    if (!metadata || !descriptor || !ownership) return 0;
    if (pgt_validate_resource_descriptor(descriptor) != 0) return 0;

    memset(&token, 0, sizeof(token));
    token.resource_id = metadata->resource_id;
    token.generation = metadata->generation;
    token.sequence = metadata->sequence;

    if (
        pocketpc_graphics_handle_binding_validate_offer(
            descriptor,
            ownership,
            &token
        ) != POCKETPC_GRAPHICS_HANDLE_BINDING_OK
    ) {
        return 0;
    }

    return
        descriptor->width == metadata->width &&
        descriptor->height == metadata->height &&
        descriptor->layers == 1u &&
        descriptor->pixel_format == metadata->format &&
        descriptor->usage == (uint64_t)metadata->usage;
}

int pocketpc_external_image_fd_receive(
    int socket_fd,
    const struct pgt_resource_descriptor *descriptor,
    const struct pgt_ownership_record *ownership,
    struct pocketpc_external_image_fd_received *received
) {
    uint8_t payload[POCKETPC_EXTERNAL_IMAGE_FD_PAYLOAD_BYTES];
    char control[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr message;
    struct cmsghdr *header;
    ssize_t byte_count;
    int receive_flags = 0;
    int candidate_fd = -1;
    unsigned int rights_count = 0u;
    struct pocketpc_external_image_fd_metadata metadata;

    if (received) {
        received->resource_fd = -1;
        memset(&received->metadata, 0, sizeof(received->metadata));
    }
    if (
        socket_fd < 0 ||
        descriptor == NULL ||
        ownership == NULL ||
        received == NULL
    ) {
        return POCKETPC_EXTERNAL_IMAGE_FD_INVALID_ARGUMENT;
    }
    if (!pvi_socket_is_seqpacket(socket_fd))
        return POCKETPC_EXTERNAL_IMAGE_FD_WRONG_SOCKET_TYPE;

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
    receive_flags |= MSG_CMSG_CLOEXEC;
#endif

    do {
        byte_count = recvmsg(socket_fd, &message, receive_flags);
    } while (byte_count < 0 && errno == EINTR);

    if (byte_count < 0)
        return POCKETPC_EXTERNAL_IMAGE_FD_IO_FAILED;
    if (
        (size_t)byte_count != sizeof(payload) ||
        (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0
    ) {
        pvi_close_rights(&message);
        return POCKETPC_EXTERNAL_IMAGE_FD_TRUNCATED;
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
            pvi_close_rights(&message);
            return POCKETPC_EXTERNAL_IMAGE_FD_BAD_CONTROL;
        }
        ++rights_count;
        if (rights_count != 1u) {
            pvi_close_rights(&message);
            return POCKETPC_EXTERNAL_IMAGE_FD_BAD_CONTROL;
        }
        memcpy(&candidate_fd, CMSG_DATA(header), sizeof(candidate_fd));
    }

    if (rights_count != 1u || candidate_fd < 0) {
        pvi_close_rights(&message);
        return POCKETPC_EXTERNAL_IMAGE_FD_BAD_CONTROL;
    }

    if (!pvi_decode(payload, &metadata)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_IMAGE_FD_BAD_PAYLOAD;
    }
    if (!pvi_matches_offer(&metadata, descriptor, ownership)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_IMAGE_FD_IDENTITY_MISMATCH;
    }
    if (!pvi_set_cloexec(candidate_fd)) {
        close(candidate_fd);
        return POCKETPC_EXTERNAL_IMAGE_FD_CLOEXEC_FAILED;
    }

    received->resource_fd = candidate_fd;
    received->metadata = metadata;
    return POCKETPC_EXTERNAL_IMAGE_FD_OK;
}

void pocketpc_external_image_fd_received_release(
    struct pocketpc_external_image_fd_received *received
) {
    if (!received) return;
    if (received->resource_fd >= 0) {
        close(received->resource_fd);
        received->resource_fd = -1;
    }
    memset(&received->metadata, 0, sizeof(received->metadata));
}
