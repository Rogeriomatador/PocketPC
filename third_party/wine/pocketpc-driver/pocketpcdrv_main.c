#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <string.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS

#include "pocketpcdrv.h"
#include "unixlib.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

struct pdb_connection pocketpc_connection;
struct pdb_wine_window_bridge pocketpc_windows;
pthread_mutex_t pocketpc_bridge_mutex =
    PTHREAD_MUTEX_INITIALIZER;
BOOL pocketpc_bridge_ready = FALSE;

static const struct user_driver_funcs
pocketpcdrv_funcs =
{
    .pCreateWindow =
        POCKETPC_CreateWindow,
    .pDestroyWindow =
        POCKETPC_DestroyWindow,
    .pProcessEvents =
        POCKETPC_ProcessEvents,
    .pWindowPosChanging =
        POCKETPC_WindowPosChanging,
    .pWindowPosChanged =
        POCKETPC_WindowPosChanged,
};

static NTSTATUS pocketpcdrv_unix_init(
    void *arg
) {
    char error[160] = {0};

    (void)arg;

    memset(
        &pocketpc_connection,
        0,
        sizeof(pocketpc_connection)
    );
    pocketpc_connection.fd = -1;

    if (
        pdb_connect_from_environment(
            &pocketpc_connection,
            error,
            sizeof(error)
        ) != 0
    ) {
        ERR(
            "display bridge connection failed: %s\n",
            error
        );
        return STATUS_UNSUCCESSFUL;
    }

    pdb_wine_window_bridge_init(
        &pocketpc_windows,
        &pocketpc_connection
    );
    pocketpc_bridge_ready = TRUE;

    __wine_set_user_driver(
        &pocketpcdrv_funcs,
        WINE_GDI_DRIVER_VERSION
    );

    TRACE(
        "PocketPC USER driver registered, protocol=%u\n",
        PDB_VERSION
    );
    return STATUS_SUCCESS;
}

const unixlib_entry_t
__wine_unix_call_funcs[] =
{
    pocketpcdrv_unix_init,
};

C_ASSERT(
    ARRAYSIZE(__wine_unix_call_funcs) ==
        pocketpcdrv_unix_func_count
);

#ifdef _WIN64
const unixlib_entry_t
__wine_unix_call_wow64_funcs[] =
{
    pocketpcdrv_unix_init,
};

C_ASSERT(
    ARRAYSIZE(
        __wine_unix_call_wow64_funcs
    ) ==
        pocketpcdrv_unix_func_count
);
#endif
