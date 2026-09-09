#ifndef POCKETPC_WINE_WINDOW_BRIDGE_H
#define POCKETPC_WINE_WINDOW_BRIDGE_H

#include "pocketpc_display_bridge.h"
#include "pocketpc_wine_window_map.h"

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct pdb_wine_window_bridge {
    struct pdb_connection *connection;
    struct pdb_wine_window_map windows;
};

void pdb_wine_window_bridge_init(
    struct pdb_wine_window_bridge *bridge,
    struct pdb_connection *connection
);

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
);

int pdb_wine_window_bridge_geometry(
    struct pdb_wine_window_bridge *bridge,
    uintptr_t native_handle,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    uint32_t visible,
    int32_t z_order,
    char *error,
    size_t error_bytes
);

int pdb_wine_window_bridge_destroy(
    struct pdb_wine_window_bridge *bridge,
    uintptr_t native_handle,
    char *error,
    size_t error_bytes
);

int pdb_wine_window_bridge_handle_for_id(
    const struct pdb_wine_window_bridge *bridge,
    uint64_t window_id,
    uintptr_t *native_handle
);

#ifdef __cplusplus
}
#endif

#endif
