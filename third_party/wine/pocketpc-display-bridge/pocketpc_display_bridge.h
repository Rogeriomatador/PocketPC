#ifndef POCKETPC_DISPLAY_BRIDGE_H
#define POCKETPC_DISPLAY_BRIDGE_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
#define PDB_MAGIC 0x31424450u
#define PDB_VERSION 4u
#define PDB_HEADER_BYTES 20u
#define PDB_MAX_PAYLOAD_BYTES 1048576u
#define PDB_TOKEN_BYTES 32u
#define PDB_IDENTITY_BYTES 64u
#define PDB_HELLO_BYTES 100u
#define PDB_WINDOW_CREATE_BYTES 28u
#define PDB_WINDOW_GEOMETRY_BYTES 40u
#define PDB_WINDOW_DESTROY_BYTES 8u
#define PDB_WINDOW_COMMAND_BYTES 16u
#define PDB_SURFACE_REQUEST_BYTES 32u
#define PDB_SURFACE_AVAILABLE_BYTES 56u
#define PDB_FRAME_READY_BYTES 32u
#define PDB_POINTER_EVENT_BYTES 32u
#define PDB_KEY_EVENT_BYTES 28u
#define PDB_FRAME_PRESENTED_BYTES 36u
#define PDB_MSG_HELLO 1u
#define PDB_MSG_HELLO_ACK 2u
#define PDB_MSG_WINDOW_CREATE 10u
#define PDB_MSG_WINDOW_GEOMETRY 11u
#define PDB_MSG_WINDOW_DESTROY 12u
#define PDB_MSG_WINDOW_COMMAND 13u
#define PDB_MSG_SURFACE_REQUEST 19u
#define PDB_MSG_SURFACE_AVAILABLE 20u
#define PDB_MSG_FRAME_READY 21u
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
#define PDB_HOST_BASELINE (PDB_CAP_WINDOW_SURFACE|PDB_CAP_POINTER|PDB_CAP_KEYBOARD|PDB_CAP_FRAME_ACK)

#define PDB_POINTER_ACTION_MOVE 0u
#define PDB_POINTER_ACTION_DOWN 1u
#define PDB_POINTER_ACTION_UP 2u
#define PDB_POINTER_ACTION_SCROLL 3u
#define PDB_POINTER_BUTTON_PRIMARY (1u << 0)
#define PDB_POINTER_BUTTON_SECONDARY (1u << 1)
#define PDB_POINTER_BUTTON_MIDDLE (1u << 2)

#define PDB_KEY_ACTION_DOWN 1u
#define PDB_KEY_ACTION_UP 2u
#define PDB_KEY_ACTION_REPEAT 3u
#define PDB_MODIFIER_SHIFT (1u << 0)
#define PDB_MODIFIER_CONTROL (1u << 1)
#define PDB_MODIFIER_ALT (1u << 2)
#define PDB_MODIFIER_META (1u << 3)
#define PDB_MODIFIER_ALLOWED (PDB_MODIFIER_SHIFT|PDB_MODIFIER_CONTROL|PDB_MODIFIER_ALT|PDB_MODIFIER_META)
#define PDB_WINDOW_COMMAND_ACTIVATE 1u
#define PDB_WINDOW_COMMAND_MINIMIZE 2u
#define PDB_WINDOW_COMMAND_RESTORE 3u
#define PDB_WINDOW_COMMAND_MAXIMIZE 4u
#define PDB_WINDOW_COMMAND_CLOSE 5u


#define PDB_ZORDER_NO_CHANGE (1u << 0)
#define PDB_ZORDER_TOP (1u << 1)
#define PDB_ZORDER_BOTTOM (1u << 2)
#define PDB_ZORDER_TOPMOST (1u << 3)
#define PDB_ZORDER_NOTOPMOST (1u << 4)
#define PDB_ZORDER_AFTER_WINDOW (1u << 5)
#define PDB_ZORDER_ALLOWED (PDB_ZORDER_NO_CHANGE|PDB_ZORDER_TOP|PDB_ZORDER_BOTTOM|PDB_ZORDER_TOPMOST|PDB_ZORDER_NOTOPMOST|PDB_ZORDER_AFTER_WINDOW)
struct pdb_connection { int fd; uint32_t negotiated_capabilities; uint64_t next_sequence; uint64_t expected_inbound_sequence; };
struct pdb_frame { uint16_t type; uint64_t sequence; uint32_t payload_bytes; unsigned char *payload; };
struct pdb_surface_request {
    uint64_t window_id;
    uint64_t generation;
    int32_t width;
    int32_t height;
    uint32_t pixel_format;
    uint32_t flags;
};
struct pdb_surface_available {
    uint64_t window_id;
    uint64_t surface_id;
    uint64_t generation;
    int32_t width;
    int32_t height;
    int32_t stride_bytes;
    uint32_t pixel_format;
    char token_hex[33];
};
struct pdb_frame_ready {
    uint64_t window_id;
    uint64_t surface_id;
    uint64_t generation;
    uint64_t frame_id;
};
struct pdb_pointer_event { uint64_t window_id; uint32_t action; int32_t x; int32_t y; uint32_t buttons; int32_t vertical_scroll; uint32_t modifiers; };
struct pdb_key_event { uint64_t window_id; uint32_t action; uint32_t key_code; uint32_t scan_code; uint32_t modifiers; uint32_t repeat_count; };
struct pdb_frame_presented { uint64_t window_id; uint64_t surface_id; uint64_t generation; uint64_t frame_id; uint32_t status; };
struct pdb_window_command { uint64_t window_id; uint32_t command; uint32_t flags; };
struct pdb_host_event {
    uint16_t type;
    union {
        struct pdb_surface_available surface;
        struct pdb_pointer_event pointer;
        struct pdb_key_event key;
        struct pdb_frame_presented frame_presented;
        struct pdb_window_command window_command;
    } data;
};
int pdb_connect_from_environment(struct pdb_connection*,char*,size_t);
int pdb_send_frame(struct pdb_connection*,uint16_t,const void*,uint32_t,char*,size_t);
int pdb_receive_frame(struct pdb_connection*,struct pdb_frame*,char*,size_t);
int pdb_connection_has_input(struct pdb_connection*,char*,size_t);
int pdb_peek_message_type(struct pdb_connection*,uint16_t*,char*,size_t);
int pdb_receive_host_event(struct pdb_connection*,struct pdb_host_event*,char*,size_t);
int pdb_send_surface_request(struct pdb_connection*,const struct pdb_surface_request*,char*,size_t);
int pdb_receive_surface_available(struct pdb_connection*,struct pdb_surface_available*,char*,size_t);
int pdb_surface_guest_path(const struct pdb_surface_available*,char*,size_t);
int pdb_send_frame_ready(struct pdb_connection*,const struct pdb_frame_ready*,char*,size_t);
int pdb_receive_pointer_event(struct pdb_connection*,struct pdb_pointer_event*,char*,size_t);
int pdb_receive_key_event(struct pdb_connection*,struct pdb_key_event*,char*,size_t);
int pdb_receive_frame_presented(struct pdb_connection*,struct pdb_frame_presented*,char*,size_t);
int pdb_send_window_create(struct pdb_connection*,uint64_t,uint64_t,uint32_t,int32_t,int32_t,char*,size_t);
int pdb_send_window_geometry(struct pdb_connection*,uint64_t,int32_t,int32_t,int32_t,int32_t,uint32_t,uint32_t,uint64_t,char*,size_t);
int pdb_send_window_destroy(struct pdb_connection*,uint64_t,char*,size_t);
void pdb_release_frame(struct pdb_frame*);
void pdb_close(struct pdb_connection*);
#ifdef __cplusplus
}
#endif
#endif
