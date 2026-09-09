#ifndef __WINE_POCKETPCDRV_H
#define __WINE_POCKETPCDRV_H

#ifndef __WINE_CONFIG_H
#error You must include config.h before pocketpcdrv.h
#endif

#include <pthread.h>
#include <stdint.h>

#include "windef.h"
#include "winbase.h"
#include "winuser.h"
#include "ntuser.h"
#include "wine/gdi_driver.h"
#include "wine/debug.h"

#include "pocketpc_display_bridge.h"
#include "pocketpc_wine_window_bridge.h"

#define POCKETPC_MAX_EVENTS_PER_PUMP 64u
#define POCKETPC_HOST_EVENT_QUEUE_LIMIT 128u

extern struct pdb_connection pocketpc_connection;
extern struct pdb_wine_window_bridge pocketpc_windows;
extern pthread_mutex_t pocketpc_bridge_mutex;
extern BOOL pocketpc_bridge_ready;

void POCKETPC_FailBridgeLocked(
    const char *reason
);
BOOL POCKETPC_BridgeReady(void);
BOOL POCKETPC_CreateWindow(HWND hwnd);
BOOL POCKETPC_ProcessEvents(DWORD mask);
BOOL POCKETPC_DispatchHostEvent(
    const struct pdb_host_event *event
);
BOOL POCKETPC_HandleFramePresented(
    const struct pdb_frame_presented *event
);
BOOL POCKETPC_QueueHostEventLocked(
    const struct pdb_host_event *event
);
BOOL POCKETPC_DequeueHostEventLocked(
    struct pdb_host_event *event
);
void POCKETPC_DestroyWindow(HWND hwnd);
BOOL POCKETPC_CreateWindowSurface(
    HWND hwnd,
    BOOL layered,
    const RECT *surface_rect,
    struct window_surface **surface
);
BOOL POCKETPC_WindowPosChanging(
    HWND hwnd,
    UINT swp_flags,
    BOOL shaped,
    const struct window_rects *rects
);
void POCKETPC_WindowPosChanged(
    HWND hwnd,
    HWND insert_after,
    HWND owner_hint,
    UINT swp_flags,
    const struct window_rects *new_rects,
    struct window_surface *surface
);

#endif
