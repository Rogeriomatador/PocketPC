#ifndef POCKETPC_GRAPHICS_ACK_H
#define POCKETPC_GRAPHICS_ACK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PGA_MAGIC 0x31414750u /* "PGA1" little-endian */
#define PGA_VERSION 1u
#define PGA_PACKET_BYTES 48u

#define PGA_STAGE_RESOURCE_OFFER_RECEIVED 1u
#define PGA_STAGE_IMAGE_IMPORTED 2u
#define PGA_STAGE_SYNC_IMPORTED 3u
#define PGA_STAGE_READY 4u
#define PGA_STAGE_GPU_SIGNAL_SUBMITTED 5u

#define PGA_STATUS_OK 0

struct pocketpc_graphics_ack {
    uint16_t stage;
    int32_t status;
    uint64_t resource_id;
    uint64_t generation;
    uint64_t sequence;
    uint32_t detail;
};

/*
 * Send one acknowledgement over the already authenticated PGH1 SOCK_SEQPACKET
 * connection. This packet carries identity and stage only; it never carries a
 * pointer or file descriptor. Successful send is not proof the host received
 * or persisted the acknowledgement.
 */
int pocketpc_graphics_ack_send(
    int socket_fd,
    const struct pocketpc_graphics_ack *ack
);

#ifdef __cplusplus
}
#endif

#endif
