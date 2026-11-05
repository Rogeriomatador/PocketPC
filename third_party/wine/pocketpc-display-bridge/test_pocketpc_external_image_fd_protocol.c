#define _GNU_SOURCE

#include "pocketpc_external_image_fd_protocol.h"

#include <assert.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#define PVI_BYTES 64u

static void put_u16_le(uint8_t *out, uint16_t value)
{
    out[0] = (uint8_t)(value & 0xffu);
    out[1] = (uint8_t)((value >> 8u) & 0xffu);
}

static void put_u32_le(uint8_t *out, uint32_t value)
{
    out[0] = (uint8_t)(value & 0xffu);
    out[1] = (uint8_t)((value >> 8u) & 0xffu);
    out[2] = (uint8_t)((value >> 16u) & 0xffu);
    out[3] = (uint8_t)((value >> 24u) & 0xffu);
}

static void put_u64_le(uint8_t *out, uint64_t value)
{
    unsigned int i;
    for (i = 0; i < 8u; ++i)
        out[i] = (uint8_t)((value >> (i * 8u)) & 0xffu);
}

static void encode_payload(
    uint8_t payload[PVI_BYTES],
    uint64_t resource_id,
    uint64_t generation,
    uint64_t sequence
) {
    memset(payload, 0, PVI_BYTES);
    put_u32_le(payload + 0, POCKETPC_EXTERNAL_IMAGE_FD_MAGIC);
    put_u16_le(payload + 4, POCKETPC_EXTERNAL_IMAGE_FD_VERSION);
    put_u16_le(payload + 6, 0u);
    put_u64_le(payload + 8, resource_id);
    put_u64_le(payload + 16, generation);
    put_u64_le(payload + 24, sequence);
    put_u32_le(payload + 32, 64u);
    put_u32_le(payload + 36, 64u);
    put_u32_le(payload + 40, POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM);
    put_u32_le(payload + 44, POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS);
    put_u64_le(payload + 48, 16384u);
    put_u32_le(payload + 56, 0x5u);
    put_u32_le(payload + 60, 2u);
}

static int send_one_fd(int socket_fd, int fd, const uint8_t payload[PVI_BYTES])
{
    struct iovec iov;
    struct msghdr message;
    struct cmsghdr *header;
    char control[CMSG_SPACE(sizeof(int))];
    ssize_t written;

    memset(&iov, 0, sizeof(iov));
    memset(&message, 0, sizeof(message));
    memset(control, 0, sizeof(control));

    iov.iov_base = (void *)payload;
    iov.iov_len = PVI_BYTES;
    message.msg_iov = &iov;
    message.msg_iovlen = 1;
    message.msg_control = control;
    message.msg_controllen = sizeof(control);

    header = CMSG_FIRSTHDR(&message);
    assert(header != NULL);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(header), &fd, sizeof(fd));
    message.msg_controllen = CMSG_SPACE(sizeof(int));

    written = sendmsg(socket_fd, &message, MSG_NOSIGNAL);
    return written == (ssize_t)PVI_BYTES ? 0 : -1;
}

static struct pgt_resource_descriptor valid_descriptor(void)
{
    struct pgt_resource_descriptor descriptor;
    memset(&descriptor, 0, sizeof(descriptor));
    descriptor.resource_id = 41u;
    descriptor.generation = 7u;
    descriptor.width = 64u;
    descriptor.height = 64u;
    descriptor.layers = 1u;
    descriptor.pixel_format = POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM;
    descriptor.usage = POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS;
    descriptor.producer_pid = 123u;
    descriptor.process_namespace = 456u;
    descriptor.sync_sequence = 9u;
    return descriptor;
}

static struct pgt_ownership_record valid_ownership(void)
{
    struct pgt_ownership_record ownership;
    memset(&ownership, 0, sizeof(ownership));
    ownership.resource_id = 41u;
    ownership.generation = 7u;
    ownership.sequence = 9u;
    ownership.state = PGT_STATE_OFFERED_TO_GUEST;
    return ownership;
}

int main(void)
{
    int sockets[2] = {-1, -1};
    int pipe_fds[2] = {-1, -1};
    uint8_t payload[PVI_BYTES];
    struct pgt_resource_descriptor descriptor = valid_descriptor();
    struct pgt_ownership_record ownership = valid_ownership();
    struct pocketpc_external_image_fd_received received;
    char byte = '\0';
    int flags;
    int result;

    assert(socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets) == 0);
    assert(pipe(pipe_fds) == 0);
    assert(write(pipe_fds[1], "Z", 1) == 1);

    encode_payload(payload, 41u, 7u, 9u);
    assert(send_one_fd(sockets[0], pipe_fds[0], payload) == 0);

    result = pocketpc_external_image_fd_receive(
        sockets[1], &descriptor, &ownership, &received);
    assert(result == POCKETPC_EXTERNAL_IMAGE_FD_OK);
    assert(received.resource_fd >= 0);
    assert(received.metadata.resource_id == 41u);
    assert(received.metadata.generation == 7u);
    assert(received.metadata.sequence == 9u);
    assert(received.metadata.width == 64u);
    assert(received.metadata.height == 64u);
    assert(received.metadata.format == POCKETPC_EXTERNAL_IMAGE_FORMAT_R8G8B8A8_UNORM);
    assert(received.metadata.usage == POCKETPC_EXTERNAL_IMAGE_USAGE_FLAGS);
    assert(received.metadata.memory_type_bits == 0x5u);
    assert(received.metadata.memory_type_index == 2u);

    flags = fcntl(received.resource_fd, F_GETFD);
    assert(flags >= 0);
    assert((flags & FD_CLOEXEC) != 0);
    assert(read(received.resource_fd, &byte, 1) == 1);
    assert(byte == 'Z');
    pocketpc_external_image_fd_received_release(&received);
    assert(received.resource_fd == -1);

    encode_payload(payload, 41u, 7u, 10u);
    assert(send_one_fd(sockets[0], pipe_fds[0], payload) == 0);
    result = pocketpc_external_image_fd_receive(
        sockets[1], &descriptor, &ownership, &received);
    assert(result == POCKETPC_EXTERNAL_IMAGE_FD_IDENTITY_MISMATCH);
    assert(received.resource_fd == -1);

    close(pipe_fds[0]);
    close(pipe_fds[1]);
    close(sockets[0]);
    close(sockets[1]);

    puts("POCKETPC_EXTERNAL_IMAGE_FD_PROTOCOL_SMOKE_OK");
    return 0;
}
