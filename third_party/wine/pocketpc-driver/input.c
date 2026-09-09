#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdint.h>
#include <string.h>

#include "pocketpcdrv.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static HWND hwnd_for_window_id(
    uint64_t window_id
) {
    uintptr_t native_handle = 0u;

    if (
        pdb_wine_window_bridge_handle_for_id(
            &pocketpc_windows,
            window_id,
            &native_handle
        ) != 0
    ) {
        return 0;
    }

    return (HWND)native_handle;
}

static DWORD pointer_button_flags(
    uint32_t action,
    uint32_t buttons
) {
    DWORD flags = 0u;

    if (
        action !=
            PDB_POINTER_ACTION_DOWN &&
        action !=
            PDB_POINTER_ACTION_UP
    ) {
        return 0u;
    }

    if (
        buttons &
        PDB_POINTER_BUTTON_PRIMARY
    ) {
        flags |=
            action ==
                PDB_POINTER_ACTION_DOWN
                ? MOUSEEVENTF_LEFTDOWN
                : MOUSEEVENTF_LEFTUP;
    }
    if (
        buttons &
        PDB_POINTER_BUTTON_SECONDARY
    ) {
        flags |=
            action ==
                PDB_POINTER_ACTION_DOWN
                ? MOUSEEVENTF_RIGHTDOWN
                : MOUSEEVENTF_RIGHTUP;
    }
    if (
        buttons &
        PDB_POINTER_BUTTON_MIDDLE
    ) {
        flags |=
            action ==
                PDB_POINTER_ACTION_DOWN
                ? MOUSEEVENTF_MIDDLEDOWN
                : MOUSEEVENTF_MIDDLEUP;
    }

    return flags;
}

static BOOL inject_pointer(
    const struct pdb_pointer_event *event
) {
    INPUT input;
    HWND hwnd;

    if (!event)
        return FALSE;

    hwnd =
        hwnd_for_window_id(
            event->window_id
        );
    if (!hwnd)
    {
        WARN(
            "pointer for unknown window_id=%llu\n",
            (unsigned long long)
                event->window_id
        );
        return FALSE;
    }

    memset(
        &input,
        0,
        sizeof(input)
    );
    input.type = INPUT_MOUSE;
    input.mi.dx = event->x;
    input.mi.dy = event->y;
    input.mi.dwFlags =
        MOUSEEVENTF_MOVE |
        MOUSEEVENTF_ABSOLUTE;

    switch (event->action)
    {
    case PDB_POINTER_ACTION_MOVE:
        break;

    case PDB_POINTER_ACTION_DOWN:
    case PDB_POINTER_ACTION_UP:
        input.mi.dwFlags |=
            pointer_button_flags(
                event->action,
                event->buttons
            );
        if (
            !(input.mi.dwFlags &
              (
                MOUSEEVENTF_LEFTDOWN |
                MOUSEEVENTF_LEFTUP |
                MOUSEEVENTF_RIGHTDOWN |
                MOUSEEVENTF_RIGHTUP |
                MOUSEEVENTF_MIDDLEDOWN |
                MOUSEEVENTF_MIDDLEUP
              ))
        ) {
            WARN(
                "pointer button action without supported button mask=%#x\n",
                event->buttons
            );
            return FALSE;
        }
        break;

    case PDB_POINTER_ACTION_SCROLL:
        input.mi.dwFlags |=
            MOUSEEVENTF_WHEEL;
        input.mi.mouseData =
            (DWORD)event
                ->vertical_scroll;
        break;

    default:
        return FALSE;
    }

    TRACE(
        "pointer hwnd=%p action=%u pos=%d,%d buttons=%#x scroll=%d\n",
        hwnd,
        event->action,
        event->x,
        event->y,
        event->buttons,
        event->vertical_scroll
    );

    return NtUserSendHardwareInput(
        hwnd,
        0,
        &input,
        0
    );
}

static BOOL inject_key_once(
    HWND hwnd,
    const struct pdb_key_event *event,
    BOOL key_up
) {
    INPUT input;

    memset(
        &input,
        0,
        sizeof(input)
    );
    input.type =
        INPUT_KEYBOARD;
    input.ki.wVk =
        (WORD)event->key_code;
    input.ki.wScan =
        (WORD)(
            event->scan_code &
            0xffu
        );
    input.ki.dwFlags =
        key_up
            ? KEYEVENTF_KEYUP
            : 0u;

    if (
        event->scan_code &
        0x100u
    ) {
        input.ki.dwFlags |=
            KEYEVENTF_EXTENDEDKEY;
    }

    return NtUserSendHardwareInput(
        hwnd,
        0,
        &input,
        0
    );
}

static BOOL inject_key(
    const struct pdb_key_event *event
) {
    HWND hwnd;
    uint32_t count;
    uint32_t i;

    if (!event)
        return FALSE;

    hwnd =
        hwnd_for_window_id(
            event->window_id
        );
    if (!hwnd)
    {
        WARN(
            "key for unknown window_id=%llu\n",
            (unsigned long long)
                event->window_id
        );
        return FALSE;
    }

    count =
        event->action ==
            PDB_KEY_ACTION_REPEAT
            ? event->repeat_count
            : 1u;
    if (count == 0u)
        count = 1u;

    TRACE(
        "key hwnd=%p action=%u vk=%u scan=%u repeat=%u\n",
        hwnd,
        event->action,
        event->key_code,
        event->scan_code,
        count
    );

    for (i = 0u; i < count; ++i)
    {
        if (
            !inject_key_once(
                hwnd,
                event,
                event->action ==
                    PDB_KEY_ACTION_UP
            )
        ) {
            return FALSE;
        }
    }

    return TRUE;
}

BOOL POCKETPC_ProcessEvents(
    DWORD mask
) {
    unsigned int processed = 0u;
    char error[160] = {0};

    if (!pocketpc_bridge_ready)
        return FALSE;

    if (
        !(mask &
          (
            QS_INPUT |
            QS_SENDMESSAGE |
            QS_POSTMESSAGE
          ))
    ) {
        return FALSE;
    }

    while (
        processed <
        POCKETPC_MAX_EVENTS_PER_PUMP
    ) {
        struct pdb_host_event event;
        int available;

        pthread_mutex_lock(
            &pocketpc_bridge_mutex
        );

        available =
            pdb_connection_has_input(
                &pocketpc_connection,
                error,
                sizeof(error)
            );
        if (available <= 0)
        {
            pthread_mutex_unlock(
                &pocketpc_bridge_mutex
            );

            if (available < 0)
            {
                ERR(
                    "event poll failed: %s\n",
                    error
                );
            }
            break;
        }

        {
            uint16_t next_type = 0u;
            int peeked =
                pdb_peek_message_type(
                    &pocketpc_connection,
                    &next_type,
                    error,
                    sizeof(error)
                );

            if (peeked < 0)
            {
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                ERR(
                    "event peek failed: %s\n",
                    error
                );
                break;
            }

            if (peeked == 0)
            {
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }

            if (
                next_type !=
                    PDB_MSG_POINTER_EVENT &&
                next_type !=
                    PDB_MSG_KEY_EVENT
            ) {
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }
        }

        if (
            pdb_receive_host_event(
                &pocketpc_connection,
                &event,
                error,
                sizeof(error)
            ) != 0
        ) {
            pthread_mutex_unlock(
                &pocketpc_bridge_mutex
            );
            ERR(
                "event receive failed: %s\n",
                error
            );
            break;
        }

        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );

        switch (event.type)
        {
        case PDB_MSG_POINTER_EVENT:
            if (
                mask &
                (
                    QS_MOUSEMOVE |
                    QS_MOUSEBUTTON
                )
            ) {
                (void)inject_pointer(
                    &event.data.pointer
                );
            }
            break;

        case PDB_MSG_KEY_EVENT:
            if (mask & QS_KEY)
            {
                (void)inject_key(
                    &event.data.key
                );
            }
            break;

        case PDB_MSG_SURFACE_AVAILABLE:
        case PDB_MSG_FRAME_PRESENTED:
            ERR(
                "non-input event escaped peek type=%u\n",
                event.type
            );
            break;

        default:
            WARN(
                "unexpected host event type=%u\n",
                event.type
            );
            break;
        }

        processed += 1u;
    }

    return processed != 0u;
}
