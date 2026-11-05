#if 0
#pragma makedep unix
#endif

#include "config.h"

#include <stdint.h>
#include <string.h>

#include "pocketpcdrv.h"

WINE_DEFAULT_DEBUG_CHANNEL(pocketpcdrv);

static struct pdb_host_event
host_event_queue[
    POCKETPC_HOST_EVENT_QUEUE_LIMIT
];
static unsigned int host_event_queue_head = 0u;
static unsigned int host_event_queue_count = 0u;

BOOL POCKETPC_QueueHostEventLocked(
    const struct pdb_host_event *event
) {
    unsigned int tail;

    if (
        !event ||
        (
            event->type !=
                PDB_MSG_POINTER_EVENT &&
            event->type !=
                PDB_MSG_KEY_EVENT &&
            event->type !=
                PDB_MSG_WINDOW_COMMAND
        ) ||
        host_event_queue_count >=
            POCKETPC_HOST_EVENT_QUEUE_LIMIT
    ) {
        return FALSE;
    }

    tail =
        (
            host_event_queue_head +
            host_event_queue_count
        ) %
        POCKETPC_HOST_EVENT_QUEUE_LIMIT;
    host_event_queue[tail] = *event;
    host_event_queue_count += 1u;
    return TRUE;
}

BOOL POCKETPC_DequeueHostEventLocked(
    struct pdb_host_event *event
) {
    if (
        !event ||
        host_event_queue_count == 0u
    ) {
        return FALSE;
    }

    *event =
        host_event_queue[
            host_event_queue_head
        ];
    memset(
        &host_event_queue[
            host_event_queue_head
        ],
        0,
        sizeof(
            host_event_queue[
                host_event_queue_head
            ]
        )
    );
    host_event_queue_head =
        (
            host_event_queue_head +
            1u
        ) %
        POCKETPC_HOST_EVENT_QUEUE_LIMIT;
    host_event_queue_count -= 1u;
    return TRUE;
}


static BOOL host_event_matches_mask(
    const struct pdb_host_event *event,
    DWORD mask
) {
    if (!event)
        return FALSE;

    switch (event->type)
    {
    case PDB_MSG_POINTER_EVENT:
        return (
            mask &
            (
                QS_MOUSEMOVE |
                QS_MOUSEBUTTON
            )
        ) != 0;

    case PDB_MSG_KEY_EVENT:
        return (mask & QS_KEY) != 0;

    case PDB_MSG_WINDOW_COMMAND:
        return (
            mask &
            (
                QS_SENDMESSAGE |
                QS_POSTMESSAGE
            )
        ) != 0;

    case PDB_MSG_FRAME_PRESENTED:
        /*
         * FRAME_PRESENTED is a control-plane ownership ACK, not a
         * Win32 input event. It must drain for every Wine queue mask
         * or the single-buffer surface can remain permanently owned
         * by the host.
         */
        return TRUE;

    default:
        return FALSE;
    }
}

static BOOL POCKETPC_DequeueHostEventForMaskLocked(
    DWORD mask,
    struct pdb_host_event *event
) {
    unsigned int offset;

    if (!event)
        return FALSE;

    for (
        offset = 0u;
        offset < host_event_queue_count;
        ++offset
    ) {
        unsigned int index =
            (
                host_event_queue_head +
                offset
            ) %
            POCKETPC_HOST_EVENT_QUEUE_LIMIT;
        unsigned int shift;

        if (
            !host_event_matches_mask(
                &host_event_queue[index],
                mask
            )
        ) {
            continue;
        }

        *event = host_event_queue[index];

        for (
            shift = offset;
            shift + 1u <
                host_event_queue_count;
            ++shift
        ) {
            unsigned int destination =
                (
                    host_event_queue_head +
                    shift
                ) %
                POCKETPC_HOST_EVENT_QUEUE_LIMIT;
            unsigned int source =
                (
                    host_event_queue_head +
                    shift +
                    1u
                ) %
                POCKETPC_HOST_EVENT_QUEUE_LIMIT;

            host_event_queue[destination] =
                host_event_queue[source];
        }

        {
            unsigned int tail =
                (
                    host_event_queue_head +
                    host_event_queue_count -
                    1u
                ) %
                POCKETPC_HOST_EVENT_QUEUE_LIMIT;

            memset(
                &host_event_queue[tail],
                0,
                sizeof(host_event_queue[tail])
            );
        }

        host_event_queue_count -= 1u;
        if (host_event_queue_count == 0u)
            host_event_queue_head = 0u;
        return TRUE;
    }

    return FALSE;
}

void POCKETPC_FailBridgeLocked(
    const char *reason
) {
    ERR(
        "display bridge fail-closed: %s\n",
        reason ? reason : "unknown"
    );
    pdb_close(&pocketpc_connection);
    pocketpc_bridge_ready = FALSE;
    memset(
        host_event_queue,
        0,
        sizeof(host_event_queue)
    );
    host_event_queue_head = 0u;
    host_event_queue_count = 0u;
}

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
    RECT window_rect;
    HWND hwnd;
    int width;
    int height;
    int screen_x;
    int screen_y;

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

    if (
        !NtUserGetWindowRect(
            hwnd,
            &window_rect,
            0
        )
    ) {
        WARN(
            "pointer window rect unavailable hwnd=%p\n",
            hwnd
        );
        return FALSE;
    }

    width =
        window_rect.right -
        window_rect.left;
    height =
        window_rect.bottom -
        window_rect.top;

    if (
        event->x < 0 ||
        event->y < 0 ||
        event->x >= width ||
        event->y >= height
    ) {
        WARN(
            "pointer outside hwnd=%p bounds=%dx%d pos=%d,%d\n",
            hwnd,
            width,
            height,
            event->x,
            event->y
        );
        return FALSE;
    }

    screen_x =
        window_rect.left +
        event->x;
    screen_y =
        window_rect.top +
        event->y;

    /*
     * Wine graphics drivers call NtUserSendHardwareInput with
     * host-mapped pixel coordinates. 0..65535 normalization belongs
     * to the public NtUserSendInput path in win32u and must not be
     * applied here.
     */
    memset(
        &input,
        0,
        sizeof(input)
    );
    input.type = INPUT_MOUSE;
    input.mi.dx = screen_x;
    input.mi.dy = screen_y;
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

static BOOL dispatch_window_command(
    const struct pdb_window_command *command
) {
    HWND hwnd;

    if (!command)
        return FALSE;

    hwnd =
        hwnd_for_window_id(
            command->window_id
        );
    if (!hwnd)
    {
        WARN(
            "window command for unknown window_id=%llu command=%u\n",
            (unsigned long long)
                command->window_id,
            command->command
        );
        return FALSE;
    }

    TRACE(
        "window command hwnd=%p command=%u\n",
        hwnd,
        command->command
    );

    switch (command->command)
    {
    case PDB_WINDOW_COMMAND_ACTIVATE:
        (void)NtUserShowWindow(
            hwnd,
            SW_RESTORE
        );
        return NtUserSetForegroundWindow(
            hwnd
        );

    case PDB_WINDOW_COMMAND_MINIMIZE:
        return NtUserShowWindow(
            hwnd,
            SW_MINIMIZE
        );

    case PDB_WINDOW_COMMAND_RESTORE:
        return NtUserShowWindow(
            hwnd,
            SW_RESTORE
        );

    case PDB_WINDOW_COMMAND_MAXIMIZE:
        return NtUserShowWindow(
            hwnd,
            SW_MAXIMIZE
        );

    case PDB_WINDOW_COMMAND_CLOSE:
        return NtUserPostMessage(
            hwnd,
            WM_CLOSE,
            0,
            0
        );

    default:
        return FALSE;
    }
}

BOOL POCKETPC_DispatchHostEvent(
    const struct pdb_host_event *event
) {
    if (!event)
        return FALSE;

    switch (event->type)
    {
    case PDB_MSG_POINTER_EVENT:
        return inject_pointer(
            &event->data.pointer
        );

    case PDB_MSG_KEY_EVENT:
        return inject_key(
            &event->data.key
        );

    case PDB_MSG_WINDOW_COMMAND:
        return dispatch_window_command(
            &event->data.window_command
        );

    default:
        return FALSE;
    }
}

BOOL POCKETPC_ProcessEvents(
    DWORD mask
) {
    unsigned int processed = 0u;
    char error[160] = {0};

    if (!POCKETPC_BridgeReady())
        return FALSE;

    /*
     * Do not return early for an empty/unrelated Win32 queue mask:
     * FRAME_PRESENTED ownership ACKs are control-plane traffic and
     * must remain drainable independently of input eligibility.
     */

    while (
        processed <
        POCKETPC_MAX_EVENTS_PER_PUMP
    ) {
        struct pdb_host_event event;
        BOOL queued_event = FALSE;
        int available;

        memset(&event, 0, sizeof(event));

        pthread_mutex_lock(
            &pocketpc_bridge_mutex
        );

        queued_event =
            POCKETPC_DequeueHostEventForMaskLocked(
                mask,
                &event
            );

        if (!queued_event)
        {
            uint16_t next_type = 0u;
            int peeked;

            available =
                pdb_connection_has_input(
                    &pocketpc_connection,
                    error,
                    sizeof(error)
                );
            if (available <= 0)
            {
                if (available < 0)
                {
                    POCKETPC_FailBridgeLocked(
                        error[0]
                            ? error
                            : "PDB_EVENT_POLL_FAILED"
                    );
                }
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }

            peeked =
                pdb_peek_message_type(
                    &pocketpc_connection,
                    &next_type,
                    error,
                    sizeof(error)
                );

            if (peeked < 0)
            {
                POCKETPC_FailBridgeLocked(
                    error[0]
                        ? error
                        : "PDB_EVENT_PEEK_FAILED"
                );
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
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
                    PDB_MSG_KEY_EVENT &&
                next_type !=
                    PDB_MSG_WINDOW_COMMAND &&
                next_type !=
                    PDB_MSG_FRAME_PRESENTED
            ) {
                POCKETPC_FailBridgeLocked(
                    "PDB_UNEXPECTED_HOST_EVENT"
                );
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }

            if (
                pdb_receive_host_event(
                    &pocketpc_connection,
                    &event,
                    error,
                    sizeof(error)
                ) != 0
            ) {
                POCKETPC_FailBridgeLocked(
                    error[0]
                        ? error
                        : "PDB_EVENT_RECEIVE_FAILED"
                );
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }

            if (
                (
                    event.type ==
                        PDB_MSG_POINTER_EVENT ||
                    event.type ==
                        PDB_MSG_KEY_EVENT ||
                    event.type ==
                        PDB_MSG_WINDOW_COMMAND
                ) &&
                !host_event_matches_mask(
                    &event,
                    mask
                )
            ) {
                if (
                    !POCKETPC_QueueHostEventLocked(
                        &event
                    )
                ) {
                    POCKETPC_FailBridgeLocked(
                        "PDB_HOST_EVENT_QUEUE_FULL"
                    );
                    pthread_mutex_unlock(
                        &pocketpc_bridge_mutex
                    );
                    break;
                }

                TRACE(
                    "PDB_HOST_EVENT_MASK_DEFERRED type=%u mask=%#lx\n",
                    event.type,
                    (unsigned long)mask
                );

                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                processed += 1u;
                continue;
            }
        }

        pthread_mutex_unlock(
            &pocketpc_bridge_mutex
        );

        if (
            event.type ==
                PDB_MSG_POINTER_EVENT ||
            event.type ==
                PDB_MSG_KEY_EVENT ||
            event.type ==
                PDB_MSG_WINDOW_COMMAND
        ) {
            (void)
                POCKETPC_DispatchHostEvent(
                    &event
                );
        } else if (
            event.type ==
                PDB_MSG_FRAME_PRESENTED
        ) {
            const BOOL accepted =
                POCKETPC_HandleFramePresented(
                    &event.data
                        .frame_presented
                );

            if (!accepted) {
                WARN(
                    "frame ACK rejected or unmatched window=%llu surface=%llu generation=%llu frame=%llu status=%u\n",
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .window_id,
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .surface_id,
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .generation,
                    (unsigned long long)
                        event.data
                            .frame_presented
                            .frame_id,
                    event.data
                        .frame_presented
                        .status
                );
                pthread_mutex_lock(
                    &pocketpc_bridge_mutex
                );
                if (pocketpc_bridge_ready)
                {
                    POCKETPC_FailBridgeLocked(
                        "PDB_FRAME_ACK_REJECTED_OR_UNMATCHED"
                    );
                }
                pthread_mutex_unlock(
                    &pocketpc_bridge_mutex
                );
                break;
            }
        } else {
            ERR(
                "unexpected host event type=%u\n",
                event.type
            );
        }

        processed += 1u;
    }

    return processed != 0u;
}
