#include "pocketpc_wine_window_map.h"

#include <string.h>

static int find_handle(
    const struct pdb_wine_window_map *map,
    uintptr_t handle
) {
    size_t i;

    if (!map || handle == (uintptr_t)0) {
        return -1;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (
            map->entries[i].in_use &&
            map->entries[i].native_handle == handle
        ) {
            return (int)i;
        }
    }
    return -1;
}

static int find_window_id(
    const struct pdb_wine_window_map *map,
    uint64_t window_id
) {
    size_t i;

    if (!map || window_id == 0u) {
        return -1;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (
            map->entries[i].in_use &&
            map->entries[i].window_id == window_id
        ) {
            return (int)i;
        }
    }
    return -1;
}

void pdb_wine_window_map_init(
    struct pdb_wine_window_map *map
) {
    if (!map) {
        return;
    }

    memset(map, 0, sizeof(*map));
    map->window_namespace = 0u;
    map->next_local_window_id = 1u;
}

int pdb_wine_window_map_set_namespace(
    struct pdb_wine_window_map *map,
    uint32_t window_namespace
) {
    if (
        !map ||
        window_namespace == 0u ||
        pdb_wine_window_count(map) != 0u
    ) {
        return -1;
    }

    map->window_namespace =
        window_namespace;
    map->next_local_window_id = 1u;
    return 0;
}

int pdb_wine_window_register(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uintptr_t parent_handle,
    uint64_t *window_id
) {
    size_t i;
    uint64_t allocated;
    uint32_t local_id;

    if (
        !map ||
        !window_id ||
        native_handle == (uintptr_t)0 ||
        native_handle == parent_handle ||
        find_handle(map, native_handle) >= 0
    ) {
        return -1;
    }

    if (
        parent_handle != (uintptr_t)0 &&
        find_handle(map, parent_handle) < 0
    ) {
        return -1;
    }

    if (map->next_local_window_id == 0u) {
        return -1;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (!map->entries[i].in_use) {
            local_id =
                map->next_local_window_id;
            allocated =
                (
                    ((uint64_t)
                        map->window_namespace)
                    << 32
                ) |
                (uint64_t)local_id;

            if (local_id == UINT32_MAX) {
                map->next_local_window_id =
                    0u;
            } else {
                map->next_local_window_id =
                    local_id + 1u;
            }

            map->entries[i].native_handle =
                native_handle;
            map->entries[i].parent_handle =
                parent_handle;
            map->entries[i].window_id =
                allocated;
            map->entries[i].last_sent_sequence =
                0u;
            map->entries[i].lifecycle_state =
                PDB_WINE_WINDOW_ALLOCATED;
            map->entries[i].in_use = 1;
            *window_id = allocated;
            return 0;
        }
    }

    return -1;
}

int pdb_wine_window_lookup(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint64_t *window_id
) {
    int index;

    if (!window_id) {
        return -1;
    }

    index = find_handle(
        map,
        native_handle
    );
    if (index < 0) {
        return -1;
    }

    *window_id =
        map->entries[index].window_id;
    return 0;
}

int pdb_wine_window_handle_for_id(
    const struct pdb_wine_window_map *map,
    uint64_t window_id,
    uintptr_t *native_handle
) {
    int index;

    if (!native_handle) {
        return -1;
    }

    index = find_window_id(
        map,
        window_id
    );
    if (index < 0) {
        return -1;
    }

    *native_handle =
        map->entries[index].native_handle;
    return 0;
}

int pdb_wine_window_state(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint32_t *lifecycle_state,
    uint64_t *last_sent_sequence
) {
    int index;

    if (
        !lifecycle_state ||
        !last_sent_sequence
    ) {
        return -1;
    }

    index = find_handle(
        map,
        native_handle
    );
    if (index < 0) {
        return -1;
    }

    *lifecycle_state =
        map->entries[index].lifecycle_state;
    *last_sent_sequence =
        map->entries[index].last_sent_sequence;
    return 0;
}

int pdb_wine_window_mark_sent(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle,
    uint32_t lifecycle_state,
    uint64_t sequence
) {
    int index;
    uint32_t current;

    if (
        sequence == 0u ||
        lifecycle_state <
            PDB_WINE_WINDOW_CREATE_SENT ||
        lifecycle_state >
            PDB_WINE_WINDOW_DESTROY_SENT
    ) {
        return -1;
    }

    index = find_handle(
        map,
        native_handle
    );
    if (index < 0) {
        return -1;
    }

    current =
        map->entries[index].lifecycle_state;

    if (
        lifecycle_state ==
            PDB_WINE_WINDOW_CREATE_SENT
    ) {
        if (
            current !=
                PDB_WINE_WINDOW_ALLOCATED
        ) {
            return -1;
        }
    } else if (
        lifecycle_state ==
            PDB_WINE_WINDOW_GEOMETRY_SENT
    ) {
        if (
            current !=
                PDB_WINE_WINDOW_CREATE_SENT &&
            current !=
                PDB_WINE_WINDOW_GEOMETRY_SENT
        ) {
            return -1;
        }
    } else if (
        lifecycle_state ==
            PDB_WINE_WINDOW_DESTROY_SENT
    ) {
        if (
            current !=
                PDB_WINE_WINDOW_CREATE_SENT &&
            current !=
                PDB_WINE_WINDOW_GEOMETRY_SENT
        ) {
            return -1;
        }
    }

    if (
        map->entries[index]
            .last_sent_sequence >=
        sequence
    ) {
        return -1;
    }

    map->entries[index].lifecycle_state =
        lifecycle_state;
    map->entries[index].last_sent_sequence =
        sequence;
    return 0;
}

int pdb_wine_window_has_children(
    const struct pdb_wine_window_map *map,
    uintptr_t native_handle
) {
    size_t i;

    if (
        !map ||
        native_handle == (uintptr_t)0 ||
        find_handle(map, native_handle) < 0
    ) {
        return -1;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (
            map->entries[i].in_use &&
            map->entries[i].parent_handle ==
                native_handle
        ) {
            return 1;
        }
    }
    return 0;
}

int pdb_wine_window_unregister(
    struct pdb_wine_window_map *map,
    uintptr_t native_handle
) {
    int index;
    size_t i;

    index = find_handle(
        map,
        native_handle
    );
    if (index < 0) {
        return -1;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (
            map->entries[i].in_use &&
            map->entries[i].parent_handle ==
                native_handle
        ) {
            return -1;
        }
    }

    if (
        map->entries[index].lifecycle_state !=
            PDB_WINE_WINDOW_ALLOCATED &&
        map->entries[index].lifecycle_state !=
            PDB_WINE_WINDOW_DESTROY_SENT
    ) {
        return -1;
    }

    memset(
        &map->entries[index],
        0,
        sizeof(map->entries[index])
    );
    return 0;
}

size_t pdb_wine_window_count(
    const struct pdb_wine_window_map *map
) {
    size_t count = 0u;
    size_t i;

    if (!map) {
        return 0u;
    }

    for (i = 0; i < PDB_WINE_WINDOW_LIMIT; ++i) {
        if (map->entries[i].in_use) {
            count += 1u;
        }
    }
    return count;
}
