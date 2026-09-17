#include "pocketpcdrv_dll.h"

BOOL WINAPI DllMain(
    HINSTANCE instance,
    DWORD reason,
    void *reserved
) {
    (void)reserved;

    if (reason != DLL_PROCESS_ATTACH)
        return TRUE;

    DisableThreadLibraryCalls(instance);

    if (__wine_init_unix_call())
        return FALSE;

    return !POCKETPCDRV_UNIX_CALL(
        init,
        NULL
    );
}
