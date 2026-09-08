#ifndef POCKETPC_DISPLAY_BRIDGE_H
#define POCKETPC_DISPLAY_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PDB_MAGIC 0x31424450u
#define PDB_VERSION 1u
#define PDB_HEADER_BYTES 20u
#define PDB_MAX_PAYLOAD_BYTES 1048576u
#define PDB_TOKEN_BYTES 32u
#define PDB_IDENTITY_BYTES 64u
#define PDB_HELLO_BYTES 100u
#define PDB_WINDOW_CREATE_BYTES 28u
#define PDB_WINDOW_GEOMETRY_BYTES 32u
#define PDB_WINDOW_DESTROY_BYTES 8u
#define PDB_POINTER_EVENT_BYTES 32u
#define PDB_KEY_EVENT_BYTES 28u
#define PDB_FRAME_PRESENTED_BYTES 20u

#define PDB_MSG_HELLO 1u
#define PDB_MSG_HELLO_ACK 2u
#define PDB_MSG_WINDOW_CREATE 10u
#define PDB_MSG_WINDOW_GEOMETRY 11u
#define PDB_MSG_WINDOW_DESTROY 12u
#define PDB_MSG_SURFACE_AVAILABLE 20u
#define PDB_MSG_POINTER_EVENT 30u
#define PDB_MSG_KEY_EVENT 31u
#define PDB_MSG_GAMEPAD_EVENT 32u
#define PDB_MSG_FRAME_PRESENTED 40u
#define PDB_MSG_ERROR 255u

#define PDB_CAP_WINDOW_SURFACE (1u << 0)
#define PDB_CAP_POINTER (1u << 1)
#define PDB_CAP_KEYBOARD (1u << 2)
#define PDB_CAP_GAMEPAD (1u << 3)
#define PDB_CAP_FRAME_ACK (1u << 4)
#define PDB_HOST_BASELINE     (PDB_CAP_WINDOW_SURFACE | PDB_CAP_POINTER | PDB_CAP_KEYBOARD | PDB_CAP_FRAME_ACK)

struct pdb_connection {
    int fd;
    uint32_t negotiated_capabilities;
    uint64_t next_sequence;
};

struct pdb_frame {
    uint16_t type;
    uint64_t sequence;
    uint32_t payload_bytes;
    unsigned char *payload;
};

int pdb_connect_from_environment(
    struct pdb_connection *connection,
    char *error,
    size_t error_bytes
);

int pdb_send_frame(
    struct pdb_connection *connection,
    uint16_t type,
    const void *payload,
    uint32_t payload_bytes,
    char *error,
    size_t error_bytes
);

int pdb_receive_frame(
    struct pdb_connection *connection,
    struct pdb_frame *frame,
    char *error,
    size_t error_bytes
);

int pdb_send_window_create(
    struct pdb_connection *connection,
    uint64_t window_id,
    uint64_t parent_id,
    uint32_t flags,
    int32_t width,
    int32_t height,
    char *error,
    size_t error_bytes
);

int pdb_send_window_geometry(
    struct pdb_connection *connection,
    uint64_t window_id,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    uint32_t visible,
    int32_t z_order,
    char *error,
    size_t error_bytes
);

int pdb_send_window_destroy(
    struct pdb_connection *connection,
    uint64_t window_id,
    char *error,
    size_t error_bytes
);

void pdb_release_frame(struct pdb_frame *frame);
void pdb_close(struct pdb_connection *connection);

#ifdef __cplusplus
}
#endif

#endif
