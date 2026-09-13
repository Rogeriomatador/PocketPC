#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <string.h>
#include <unistd.h>

#include "ntstatus.h"
#define WIN32_NO_STATUS

#include "pocketpcdrv.h"
#include "unixlib.h"
#include "wine/server.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

struct pdb_connection pocketpc_connection;
struct pdb_wine_window_bridge pocketpc_windows;
pthread_mutex_t pocketpc_bridge_mutex =
    PTHREAD_MUTEX_INITIALIZER;
BOOL pocketpc_bridge_ready = FALSE;

BOOL POCKETPC_BridgeReady(void)
{
    BOOL ready;

    pthread_mutex_lock(
        &pocketpc_bridge_mutex
    );
    ready =
        pocketpc_bridge_ready &&
        pocketpc_connection.fd >= 0;
    pthread_mutex_unlock(
        &pocketpc_bridge_mutex
    );

    return ready;
}

static const struct user_driver_funcs
pocketpcdrv_funcs =
{
    .pCreateWindow =
        POCKETPC_CreateWindow,
    .pDestroyWindow =
        POCKETPC_DestroyWindow,
    .pProcessEvents =
        POCKETPC_ProcessEvents,
    .pCreateWindowSurface =
        POCKETPC_CreateWindowSurface,
    .pWindowPosChanging =
        POCKETPC_WindowPosChanging,
    .pWindowPosChanged =
        POCKETPC_WindowPosChanged,
    .pVulkanInit =
        POCKETPC_VulkanInit,
};

/*
 * Wine only invokes pProcessEvents from a blocking message wait after the
 * server observes readable data on the driver queue fd and raises QS_DRIVER.
 * Register the PocketPC display bridge socket with the current thread queue so
 * host pointer/key/FRAME_PRESENTED traffic wakes GetMessage/MsgWait callers.
 */
static BOOL register_bridge_queue_fd(void)
{
    HANDLE handle;
    int ret;

    if (pocketpc_connection.fd < 0)
        return FALSE;

    if (
        wine_server_fd_to_handle(
            pocketpc_connection.fd,
            GENERIC_READ | SYNCHRONIZE,
            0,
            &handle
        )
    ) {
        ERR(
            "POCKETPC_DRIVER_LOAD stage=queue_fd_handle_failed protocol=%u fd=%d\n",
            PDB_VERSION,
            pocketpc_connection.fd
        );
        return FALSE;
    }

    SERVER_START_REQ(set_queue_fd)
    {
        req->handle =
            wine_server_obj_handle(handle);
        ret = wine_server_call(req);
    }
    SERVER_END_REQ;

    NtClose(handle);

    if (ret)
    {
        ERR(
            "POCKETPC_DRIVER_LOAD stage=queue_fd_register_failed protocol=%u fd=%d status=%#x\n",
            PDB_VERSION,
            pocketpc_connection.fd,
            (unsigned int)ret
        );
        return FALSE;
    }

    TRACE(
        "POCKETPC_DRIVER_LOAD stage=queue_fd_registered protocol=%u fd=%d\n",
        PDB_VERSION,
        pocketpc_connection.fd
    );
    return TRUE;
}

static NTSTATUS pocketpcdrv_unix_init(
    void *arg
) {
    char error[160] = {0};
    uint32_t process_namespace =
        (uint32_t)getpid() &
        0x7fffffffu;

    (void)arg;

    TRACE(
        "POCKETPC_DRIVER_LOAD stage=unix_init_begin protocol=%u pid=%ld\n",
        PDB_VERSION,
        (long)getpid()
    );

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
            "POCKETPC_DRIVER_LOAD stage=bridge_connect_failed protocol=%u error=%s\n",
            PDB_VERSION,
            error
        );
        return STATUS_UNSUCCESSFUL;
    }

    TRACE(
        "POCKETPC_DRIVER_LOAD stage=bridge_connected protocol=%u capabilities=%u\n",
        PDB_VERSION,
        pocketpc_connection.negotiated_capabilities
    );

    pdb_wine_window_bridge_init(
        &pocketpc_windows,
        &pocketpc_connection
    );

    if (
        process_namespace == 0u ||
        pdb_wine_window_map_set_namespace(
            &pocketpc_windows.windows,
            process_namespace
        ) != 0
    ) {
        pdb_close(
            &pocketpc_connection
        );
        ERR(
            "POCKETPC_DRIVER_LOAD stage=namespace_failed protocol=%u pid=%ld namespace=%lu\n",
            PDB_VERSION,
            (long)getpid(),
            (unsigned long)process_namespace
        );
        return STATUS_UNSUCCESSFUL;
    }

    if (!register_bridge_queue_fd())
    {
        pdb_close(
            &pocketpc_connection
        );
        ERR(
            "POCKETPC_DRIVER_LOAD stage=queue_fd_failed protocol=%u pid=%ld\n",
            PDB_VERSION,
            (long)getpid()
        );
        return STATUS_UNSUCCESSFUL;
    }

    pocketpc_bridge_ready = TRUE;

    __wine_set_user_driver(
        &pocketpcdrv_funcs,
        WINE_GDI_DRIVER_VERSION
    );

    TRACE(
        "POCKETPC_DRIVER_LOAD stage=user_driver_registered protocol=%u pid_namespace=%lu\n",
        PDB_VERSION,
        (unsigned long)process_namespace
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
