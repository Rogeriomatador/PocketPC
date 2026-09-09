#include "pocketpc_wine_window_map.h"

#include <stdint.h>
#include <stdio.h>

int main(void) {
    struct pdb_wine_window_map map;
    uint64_t parent_id = 0;
    uint64_t child_id = 0;
    uint64_t lookup = 0;
    uintptr_t handle = 0;

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
        pdb_wine_window_lookup(
            &map,
            (uintptr_t)0x2000u,
            &lookup
        ) != 0 ||
        lookup != child_id
    ) {
        return 12;
    }

    if (
        pdb_wine_window_handle_for_id(
            &map,
            parent_id,
            &handle
        ) != 0 ||
        handle != (uintptr_t)0x1000u
    ) {
        return 13;
    }

    if (
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x1000u
        ) == 0
    ) {
        return 14;
    }

    if (
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x2000u
        ) != 0 ||
        pdb_wine_window_unregister(
            &map,
            (uintptr_t)0x1000u
        ) != 0 ||
        pdb_wine_window_count(&map) != 0u
    ) {
        return 15;
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
        return 16;
    }

    printf(
        "POCKETPC_WINE_WINDOW_MAP_OK id=%llu\n",
        (unsigned long long)lookup
    );
    return 0;
}
