#include "pocketpc_wine_window_bridge.h"

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static uint64_t read_u64le(
    const unsigned char *p
) {
    uint64_t value = 0u;
    int i;

    for (i = 0; i < 8; ++i) {
        value |=
            ((uint64_t)p[i]) <<
            (8 * i);
    }
    return value;
}

int main(void) {
    int sockets[2] = {-1, -1};
    struct pdb_connection guest;
    struct pdb_connection host;
    struct pdb_wine_window_bridge bridge;
    struct pdb_frame frame;
    uint64_t parent_id = 0u;
    uint64_t child_id = 0u;
    uintptr_t handle = 0u;
    uint32_t lifecycle_state = 0u;
    uint64_t last_sequence = 0u;
    char error[160] = {0};

    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        return 20;
    }

    memset(&guest, 0, sizeof(guest));
    guest.fd = sockets[0];
    guest.negotiated_capabilities =
        PDB_CAP_WINDOW_SURFACE;
    guest.next_sequence = 1u;
    guest.expected_inbound_sequence = 1u;

    memset(&host, 0, sizeof(host));
    host.fd = sockets[1];
    host.negotiated_capabilities =
        PDB_CAP_WINDOW_SURFACE;
    host.next_sequence = 1u;
    host.expected_inbound_sequence = 1u;

    pdb_wine_window_bridge_init(
        &bridge,
        &guest
    );

    if (
        pdb_wine_window_bridge_create(
            &bridge,
            (uintptr_t)0x1000u,
            (uintptr_t)0u,
            7u,
            800,
            600,
            &parent_id,
            error,
            sizeof(error)
        ) != 0 ||
        parent_id != 1u
    ) {
        return 21;
    }

    if (
        pdb_wine_window_state(
            &bridge.windows,
            (uintptr_t)0x1000u,
            &lifecycle_state,
            &last_sequence
        ) != 0 ||
        lifecycle_state !=
            PDB_WINE_WINDOW_CREATE_SENT ||
        last_sequence != 1u
    ) {
        return 22;
    }

    if (
        pdb_receive_frame(
            &host,
            &frame,
            error,
            sizeof(error)
        ) != 0 ||
        frame.type !=
            PDB_MSG_WINDOW_CREATE ||
        frame.payload_bytes !=
            PDB_WINDOW_CREATE_BYTES ||
        read_u64le(frame.payload) !=
            parent_id
    ) {
        return 22;
    }
    pdb_release_frame(&frame);

    if (
        pdb_wine_window_bridge_geometry(
            &bridge,
            (uintptr_t)0x1000u,
            25,
            30,
            640,
            480,
            1u,
            2,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 23;
    }

    if (
        pdb_receive_frame(
            &host,
            &frame,
            error,
            sizeof(error)
        ) != 0 ||
        frame.type !=
            PDB_MSG_WINDOW_GEOMETRY
    ) {
        return 24;
    }
    pdb_release_frame(&frame);

    if (
        pdb_wine_window_state(
            &bridge.windows,
            (uintptr_t)0x1000u,
            &lifecycle_state,
            &last_sequence
        ) != 0 ||
        lifecycle_state !=
            PDB_WINE_WINDOW_GEOMETRY_SENT ||
        last_sequence != 2u
    ) {
        return 25;
    }

    if (
        pdb_wine_window_bridge_create(
            &bridge,
            (uintptr_t)0x2000u,
            (uintptr_t)0x1000u,
            0u,
            320,
            200,
            &child_id,
            error,
            sizeof(error)
        ) != 0 ||
        child_id != 2u
    ) {
        return 25;
    }

    if (
        pdb_receive_frame(
            &host,
            &frame,
            error,
            sizeof(error)
        ) != 0 ||
        frame.type !=
            PDB_MSG_WINDOW_CREATE ||
        frame.payload_bytes !=
            PDB_WINDOW_CREATE_BYTES ||
        read_u64le(
            frame.payload + 8
        ) != parent_id
    ) {
        return 26;
    }
    pdb_release_frame(&frame);

    if (
        pdb_wine_window_bridge_handle_for_id(
            &bridge,
            child_id,
            &handle
        ) != 0 ||
        handle !=
            (uintptr_t)0x2000u
    ) {
        return 27;
    }

    if (
        pdb_wine_window_bridge_destroy(
            &bridge,
            (uintptr_t)0x1000u,
            error,
            sizeof(error)
        ) == 0
    ) {
        return 28;
    }

    if (
        pdb_wine_window_bridge_destroy(
            &bridge,
            (uintptr_t)0x2000u,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 29;
    }
    if (
        pdb_receive_frame(
            &host,
            &frame,
            error,
            sizeof(error)
        ) != 0 ||
        frame.type !=
            PDB_MSG_WINDOW_DESTROY ||
        read_u64le(frame.payload) !=
            child_id
    ) {
        return 30;
    }
    pdb_release_frame(&frame);

    if (
        pdb_wine_window_bridge_destroy(
            &bridge,
            (uintptr_t)0x1000u,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 31;
    }
    if (
        pdb_receive_frame(
            &host,
            &frame,
            error,
            sizeof(error)
        ) != 0 ||
        frame.type !=
            PDB_MSG_WINDOW_DESTROY ||
        read_u64le(frame.payload) !=
            parent_id
    ) {
        return 32;
    }
    pdb_release_frame(&frame);

    if (
        pdb_wine_window_count(
            &bridge.windows
        ) != 0u
    ) {
        return 33;
    }

    close(sockets[0]);
    close(sockets[1]);

    printf(
        "POCKETPC_WINE_WINDOW_BRIDGE_OK\n"
    );
    return 0;
}
