#include "pocketpc_wine_window_bridge.h"

#include <stdio.h>
#include <string.h>

static void bridge_error(
    char *error,
    size_t error_bytes,
    const char *message
) {
    if (error && error_bytes) {
        snprintf(
            error,
            error_bytes,
            "%s",
            message ? message : "PDB_WINE_WINDOW_BRIDGE_ERROR"
        );
    }
}

void pdb_wine_window_bridge_init(
    struct pdb_wine_window_bridge *bridge,
    struct pdb_connection *connection
) {
    if (!bridge) {
        return;
    }

    memset(
        bridge,
        0,
        sizeof(*bridge)
    );
    bridge->connection = connection;
    pdb_wine_window_map_init(
        &bridge->windows
    );
}

int pdb_wine_window_bridge_create(
    struct pdb_wine_window_bridge *bridge,
    uintptr_t native_handle,
    uintptr_t parent_handle,
    uint32_t flags,
    int32_t width,
    int32_t height,
    uint64_t *window_id,
    char *error,
    size_t error_bytes
) {
    uint64_t allocated = 0u;
    uint64_t parent_id = 0u;
    uint64_t create_sequence = 0u;

    if (
        !bridge ||
        !bridge->connection ||
        !window_id
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_BRIDGE_ARGUMENT_INVALID"
        );
        return -1;
    }

    if (
        parent_handle != (uintptr_t)0 &&
        pdb_wine_window_lookup(
            &bridge->windows,
            parent_handle,
            &parent_id
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_PARENT_UNKNOWN"
        );
        return -1;
    }

    if (
        pdb_wine_window_register(
            &bridge->windows,
            native_handle,
            parent_handle,
            &allocated
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_REGISTER_FAILED"
        );
        return -1;
    }

    create_sequence =
        bridge->connection->next_sequence;

    if (
        pdb_send_window_create(
            bridge->connection,
            allocated,
            parent_id,
            flags,
            width,
            height,
            error,
            error_bytes
        ) != 0
    ) {
        (void)pdb_wine_window_unregister(
            &bridge->windows,
            native_handle
        );
        return -1;
    }

    if (
        pdb_wine_window_mark_sent(
            &bridge->windows,
            native_handle,
            PDB_WINE_WINDOW_CREATE_SENT,
            create_sequence
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_CREATE_STATE_FAILED"
        );
        /*
         * WINDOW_CREATE is already visible to the host. If the
         * local lifecycle state cannot record that fact, continuing
         * would make host and guest disagree about the window.
         */
        pdb_close(bridge->connection);
        return -1;
    }

    *window_id = allocated;
    return 0;
}

int pdb_wine_window_bridge_geometry(
    struct pdb_wine_window_bridge *bridge,
    uintptr_t native_handle,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    uint32_t visible,
    uint32_t z_order_flags,
    uint64_t insert_after_window_id,
    char *error,
    size_t error_bytes
) {
    uint64_t window_id = 0u;
    uint64_t geometry_sequence = 0u;
    uint32_t lifecycle_state = 0u;
    uint64_t last_sent_sequence = 0u;

    if (
        !bridge ||
        !bridge->connection ||
        pdb_wine_window_lookup(
            &bridge->windows,
            native_handle,
            &window_id
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_UNKNOWN"
        );
        return -1;
    }

    if (
        pdb_wine_window_state(
            &bridge->windows,
            native_handle,
            &lifecycle_state,
            &last_sent_sequence
        ) != 0 ||
        (
            lifecycle_state !=
                PDB_WINE_WINDOW_CREATE_SENT &&
            lifecycle_state !=
                PDB_WINE_WINDOW_GEOMETRY_SENT
        )
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_NOT_CREATED"
        );
        return -1;
    }

    geometry_sequence =
        bridge->connection->next_sequence;

    if (
        pdb_send_window_geometry(
            bridge->connection,
            window_id,
            x,
            y,
            width,
            height,
            visible,
            z_order_flags,
            insert_after_window_id,
            error,
            error_bytes
        ) != 0
    ) {
        return -1;
    }

    if (
        pdb_wine_window_mark_sent(
            &bridge->windows,
            native_handle,
            PDB_WINE_WINDOW_GEOMETRY_SENT,
            geometry_sequence
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_GEOMETRY_STATE_FAILED"
        );
        /*
         * Geometry was already sent. Fail the transport rather than
         * keep a local lifecycle state that no longer matches host.
         */
        pdb_close(bridge->connection);
        return -1;
    }

    return 0;
}

int pdb_wine_window_bridge_destroy(
    struct pdb_wine_window_bridge *bridge,
    uintptr_t native_handle,
    char *error,
    size_t error_bytes
) {
    uint64_t window_id = 0u;
    uint64_t destroy_sequence = 0u;
    uint32_t lifecycle_state = 0u;
    uint64_t last_sent_sequence = 0u;
    int child_state;

    if (
        !bridge ||
        !bridge->connection ||
        pdb_wine_window_lookup(
            &bridge->windows,
            native_handle,
            &window_id
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_UNKNOWN"
        );
        return -1;
    }

    if (
        pdb_wine_window_state(
            &bridge->windows,
            native_handle,
            &lifecycle_state,
            &last_sent_sequence
        ) != 0 ||
        (
            lifecycle_state !=
                PDB_WINE_WINDOW_CREATE_SENT &&
            lifecycle_state !=
                PDB_WINE_WINDOW_GEOMETRY_SENT
        )
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_NOT_DESTROYABLE"
        );
        return -1;
    }

    child_state =
        pdb_wine_window_has_children(
            &bridge->windows,
            native_handle
        );
    if (child_state != 0) {
        bridge_error(
            error,
            error_bytes,
            child_state > 0
                ? "PDB_WINE_WINDOW_HAS_CHILDREN"
                : "PDB_WINE_WINDOW_CHILD_CHECK_FAILED"
        );
        return -1;
    }

    destroy_sequence =
        bridge->connection->next_sequence;

    if (
        pdb_send_window_destroy(
            bridge->connection,
            window_id,
            error,
            error_bytes
        ) != 0
    ) {
        return -1;
    }

    if (
        pdb_wine_window_mark_sent(
            &bridge->windows,
            native_handle,
            PDB_WINE_WINDOW_DESTROY_SENT,
            destroy_sequence
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_DESTROY_STATE_FAILED"
        );
        /*
         * WINDOW_DESTROY is already committed on the wire, so a
         * local state failure is unrecoverable for this session.
         */
        pdb_close(bridge->connection);
        return -1;
    }

    if (
        pdb_wine_window_unregister(
            &bridge->windows,
            native_handle
        ) != 0
    ) {
        bridge_error(
            error,
            error_bytes,
            "PDB_WINE_WINDOW_UNREGISTER_FAILED_AFTER_SEND"
        );
        /*
         * Host has destroyed the window but the guest map still
         * retained it. Close the session instead of reusing a stale
         * window identity.
         */
        pdb_close(bridge->connection);
        return -1;
    }

    return 0;
}

int pdb_wine_window_bridge_handle_for_id(
    const struct pdb_wine_window_bridge *bridge,
    uint64_t window_id,
    uintptr_t *native_handle
) {
    if (!bridge) {
        return -1;
    }

    return pdb_wine_window_handle_for_id(
        &bridge->windows,
        window_id,
        native_handle
    );
}
