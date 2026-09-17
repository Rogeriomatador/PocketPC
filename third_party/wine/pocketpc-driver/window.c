#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdint.h>

#include "pocketpcdrv.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static int window_width(
    const struct window_rects *rects
) {
    int width =
        rects->window.right -
        rects->window.left;
    return width > 0 ? width : 1;
}

static int window_height(
    const struct window_rects *rects
) {
    int height =
        rects->window.bottom -
        rects->window.top;
    return height > 0 ? height : 1;
}

static uintptr_t bridged_owner(
    HWND hwnd
) {
    HWND owner =
        NtUserGetWindowRelative(
            hwnd,
            GW_OWNER
        );
    uint64_t ignored = 0u;

    if (!owner)
        return (uintptr_t)0;

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)owner,
            &ignored
        ) != 0
    ) {
        return (uintptr_t)0;
    }

    return (uintptr_t)owner;
}

static void fail_closed_if_connection_invalid_locked(
    const char *error
) {
    if (pocketpc_connection.fd >= 0)
        return;

    POCKETPC_FailBridgeLocked(
        error && error[0]
            ? error
            : "PDB_WINDOW_TRANSPORT_INVALID"
    );
}

static void z_order_for(
    HWND insert_after,
    UINT swp_flags,
    uint32_t *flags,
    uint64_t *after_id
) {
    uint64_t sibling_id = 0u;

    *flags = PDB_ZORDER_NO_CHANGE;
    *after_id = 0u;

    if (swp_flags & SWP_NOZORDER)
        return;

    if (insert_after == HWND_TOP)
    {
        *flags = PDB_ZORDER_TOP;
        return;
    }
    if (insert_after == HWND_BOTTOM)
    {
        *flags = PDB_ZORDER_BOTTOM;
        return;
    }
    if (insert_after == HWND_TOPMOST)
    {
        *flags = PDB_ZORDER_TOPMOST;
        return;
    }
    if (insert_after == HWND_NOTOPMOST)
    {
        *flags = PDB_ZORDER_NOTOPMOST;
        return;
    }

    if (
        insert_after &&
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)insert_after,
            &sibling_id
        ) == 0
    ) {
        *flags =
            PDB_ZORDER_AFTER_WINDOW;
        *after_id = sibling_id;
    }
}

BOOL POCKETPC_CreateWindow(
    HWND hwnd
) {
    TRACE("hwnd %p\n", hwnd);
    return TRUE;
}

BOOL POCKETPC_WindowPosChanging(
    HWND hwnd,
    UINT swp_flags,
    BOOL shaped,
    const struct window_rects *rects
) {
    HWND parent;
    uint64_t existing = 0u;
    uint64_t window_id = 0u;
    uintptr_t owner;
    char error[160] = {0};

    (void)swp_flags;
    (void)shaped;

    if (
        !rects ||
        !POCKETPC_BridgeReady()
    ) {
        return FALSE;
    }

    parent =
        NtUserGetAncestor(
            hwnd,
            GA_PARENT
        );

    if (
        parent &&
        parent !=
            NtUserGetDesktopWindow()
    ) {
        return TRUE;
    }

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        !pocketpc_bridge_ready ||
        pocketpc_connection.fd < 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return FALSE;
    }

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)hwnd,
            &existing
        ) == 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return TRUE;
    }

    owner = bridged_owner(hwnd);

    if (
        pdb_wine_window_bridge_create(
            &pocketpc_windows,
            (uintptr_t)hwnd,
            owner,
            0u,
            window_width(rects),
            window_height(rects),
            &window_id,
            error,
            sizeof(error)
        ) != 0
    ) {
        ERR(
            "create hwnd=%p failed: %s\n",
            hwnd,
            error
        );
        fail_closed_if_connection_invalid_locked(
            error
        );
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return FALSE;
    }

    TRACE(
        "created hwnd=%p window_id=%llu\n",
        hwnd,
        (unsigned long long)window_id
    );

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );
    return TRUE;
}

void POCKETPC_WindowPosChanged(
    HWND hwnd,
    HWND insert_after,
    HWND owner_hint,
    UINT swp_flags,
    const struct window_rects *new_rects,
    struct window_surface *surface
) {
    uint64_t window_id = 0u;
    uint32_t z_flags;
    uint64_t after_id;
    char error[160] = {0};

    (void)owner_hint;
    (void)surface;

    if (
        !new_rects ||
        !POCKETPC_BridgeReady()
    ) {
        return;
    }

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        !pocketpc_bridge_ready ||
        pocketpc_connection.fd < 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return;
    }

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)hwnd,
            &window_id
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return;
    }

    z_order_for(
        insert_after,
        swp_flags,
        &z_flags,
        &after_id
    );

    if (
        pdb_wine_window_bridge_geometry(
            &pocketpc_windows,
            (uintptr_t)hwnd,
            new_rects->window.left,
            new_rects->window.top,
            window_width(new_rects),
            window_height(new_rects),
            NtUserIsWindowVisible(hwnd)
                ? 1u
                : 0u,
            z_flags,
            after_id,
            error,
            sizeof(error)
        ) != 0
    ) {
        ERR(
            "geometry hwnd=%p id=%llu failed: %s\n",
            hwnd,
            (unsigned long long)window_id,
            error
        );
        fail_closed_if_connection_invalid_locked(
            error
        );
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );
}

void POCKETPC_DestroyWindow(
    HWND hwnd
) {
    uint64_t window_id = 0u;
    char error[160] = {0};

    if (!POCKETPC_BridgeReady())
        return;

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );

    if (
        !pocketpc_bridge_ready ||
        pocketpc_connection.fd < 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return;
    }

    if (
        pdb_wine_window_lookup(
            &pocketpc_windows.windows,
            (uintptr_t)hwnd,
            &window_id
        ) != 0
    ) {
        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );
        return;
    }

    if (
        pdb_wine_window_bridge_destroy(
            &pocketpc_windows,
            (uintptr_t)hwnd,
            error,
            sizeof(error)
        ) != 0
    ) {
        ERR(
            "destroy hwnd=%p id=%llu failed: %s\n",
            hwnd,
            (unsigned long long)window_id,
            error
        );
        fail_closed_if_connection_invalid_locked(
            error
        );
    }

    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );
}
