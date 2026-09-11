#define _GNU_SOURCE

#include "pocketpc_graphics_ack.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>

static void pga_put_u16_le(unsigned char *out, uint16_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
}

static void pga_put_u32_le(unsigned char *out, uint32_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
    out[2] = (unsigned char)((value >> 16u) & 0xffu);
    out[3] = (unsigned char)((value >> 24u) & 0xffu);
}

static void pga_put_u64_le(unsigned char *out, uint64_t value)
{
    unsigned int index;
    for (index = 0; index < 8u; ++index)
        out[index] = (unsigned char)((value >> (index * 8u)) & 0xffu);
}

static int pga_stage_valid(uint16_t stage)
{
    return stage >= PGA_STAGE_RESOURCE_OFFER_RECEIVED &&
        stage <= PGA_STAGE_GPU_SIGNAL_SUBMITTED;
}

static int pga_socket_is_seqpacket(int socket_fd)
{
    int socket_type = 0;
    socklen_t length = sizeof(socket_type);

    if (socket_fd < 0) return 0;
    if (getsockopt(socket_fd, SOL_SOCKET, SO_TYPE, &socket_type, &length) != 0)
        return 0;
    return socket_type == SOCK_SEQPACKET;
}

int pocketpc_graphics_ack_send(
    int socket_fd,
    const struct pocketpc_graphics_ack *ack)
{
    unsigned char packet[PGA_PACKET_BYTES];
    ssize_t sent;

    if (!ack || !pga_socket_is_seqpacket(socket_fd)) return -1;
    if (!pga_stage_valid(ack->stage)) return -2;
    if (ack->resource_id == 0u || ack->generation == 0u || ack->sequence == 0u)
        return -3;

    memset(packet, 0, sizeof(packet));
    pga_put_u32_le(packet + 0, PGA_MAGIC);
    pga_put_u16_le(packet + 4, PGA_VERSION);
    pga_put_u16_le(packet + 6, ack->stage);
    pga_put_u32_le(packet + 8, (uint32_t)ack->status);
    pga_put_u32_le(packet + 12, 0u);
    pga_put_u64_le(packet + 16, ack->resource_id);
    pga_put_u64_le(packet + 24, ack->generation);
    pga_put_u64_le(packet + 32, ack->sequence);
    pga_put_u32_le(packet + 40, ack->detail);
    pga_put_u32_le(packet + 44, 0u);

    do
    {
        sent = send(socket_fd, packet, sizeof(packet), MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);

    return sent == (ssize_t)sizeof(packet) ? 0 : -4;
}
