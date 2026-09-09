#include "pocketpc_wine_window_map.h"

#include <stdint.h>
#include <stdio.h>

int main(void) {
    struct pdb_wine_window_map map;
    uint64_t parent_id = 0;
    uint64_t child_id = 0;
    uint64_t lookup = 0;
    uintptr_t handle = 0;
    uint32_t lifecycle_state = 0u;
    uint64_t last_sequence = 0u;

    pdb_wine_window_map_init(&map);

    if (
        pdb_wine_window_register(
            &map,
            (uintptr_t)0x1000u,
            (uintptr_t)0u,
            &parent_id
        ) != 0 ||
        parent_id != 1u
    ) {
        return 10;
    }

    if (
        pdb_wine_window_register(
            &map,
            (uintptr_t)0x2000u,
            (uintptr_t)0x1000u,
            &child_id
        ) != 0 ||
        child_id != 2u
    ) {
        return 11;
    }

    if (
        pdb_wine_window_mark_sent(
            &map,
            (uintptr_t)0x1000u,
            PDB_WINE_WINDOW_CREATE_SENT,
            1u
        ) != 0 ||
        pdb_wine_window_state(
            &map,
            (uintptr_t)0x1000u,
            &lifecycle_state,
            &last_sequence
        ) != 0 ||
        lifecycle_state !=
            PDB_WINE_WINDOW_CREATE_SENT ||
        last_sequence != 1u
    ) {
        return 18;
    }

    if (
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x1000u
        ) == 0
    ) {
        return 17;
    }

    if (
        pdb_wine_window_lookup(
            &map,
            (uintptr_t)0x2000u,
            &lookup
        ) != 0 ||
        lookup != child_id
    ) {
        return 18;
    }

    if (
        pdb_wine_window_handle_for_id(
            &map,
            parent_id,
            &handle
        ) != 0 ||
        handle != (uintptr_t)0x1000u
    ) {
        return 17;
    }

    if (
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x1000u
        ) == 0
    ) {
        return 18;
    }

    if (
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x2000u
        ) != 0 ||
        pdb_wine_window_mark_sent(
            &map,
            (uintptr_t)0x1000u,
            PDB_WINE_WINDOW_DESTROY_SENT,
            2u
        ) != 0 ||
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x1000u
        ) != 0 ||
        pdb_wine_window_count(&map) != 0u
    ) {
        return 17;
    }

    if (
        pdb_wine_window_register(
            &map,
            (uintptr_t)0x3000u,
            (uintptr_t)0u,
            &lookup
        ) != 0 ||
        lookup <= child_id
    ) {
        return 18;
    }

    printf(
        "POCKETPC_WINE_WINDOW_MAP_OK id=%llu\n",
        (unsigned long long)lookup
    );
    return 0;
}
