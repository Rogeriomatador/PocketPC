#ifndef POCKETPC_WINE_WINDOW_MAP_H
#define POCKETPC_WINE_WINDOW_MAP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PDB_WINE_WINDOW_LIMIT 64u

#define PDB_WINE_WINDOW_UNUSED 0u
#define PDB_WINE_WINDOW_ALLOCATED 1u
#define PDB_WINE_WINDOW_CREATE_SENT 2u
#define PDB_WINE_WINDOW_GEOMETRY_SENT 3u
#define PDB_WINE_WINDOW_DESTROY_SENT 4u

struct pdb_wine_window_entry {
    uintptr_t native_handle;
    uintptr_t parent_handle;
    uint64_t window_id;
    uint64_t last_sent_sequence;
    uint32_t lifecycle_state;
    int in_use;
};

struct pdb_wine_window_map {
    struct pdb_wine_window_entry entries[PDB_WINE_WINDOW_LIMIT];
    uint64_t next_window_id;
};

void pdb_wine_window_map_init(
    struct pdb_wine_window_map *map
);

int pdb_wine_window_register(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uintptr_t parent_handle,
    uint64_t *window_id
);

int pdb_wine_window_lookup(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint64_t *window_id
);

int pdb_wine_window_handle_for_id(
    const struct pdb_wine_window_map *map,
    uint64_t window_id,
    uintptr_t *native_handle
);

int pdb_wine_window_state(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint32_t *lifecycle_state,
    uint64_t *last_sent_sequence
);

int pdb_wine_window_mark_sent(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint32_t lifecycle_state,
    uint64_t sequence
);

int pdb_wine_window_has_children(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle
);

int pdb_wine_window_unregister(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle
);

size_t pdb_wine_window_count(
    const struct pdb_wine_window_map *map
);

#ifdef __cplusplus
}
#endif

#endif
