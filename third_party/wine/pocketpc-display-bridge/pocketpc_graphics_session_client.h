#ifndef POCKETPC_GRAPHICS_SESSION_CLIENT_H
#define POCKETPC_GRAPHICS_SESSION_CLIENT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PGS_PROTOCOL_VERSION 1u
#define PGS_TOKEN_BYTES 32u
#define PGS_HANDSHAKE_BYTES 48u
#define PGS_MAX_SOCKET_NAME_BYTES 80u
/*
 * The guest connects immediately when the Wine VkDevice is created, then a
 * worker waits for the host to learn the first real Display Bridge extent
 * before PGT/PVI1/PVS1 are sent. Keep the socket bounded but do not force an
 * arbitrary image size just to satisfy a short receive timeout.
 */
#define PGS_DEFAULT_TIMEOUT_MILLIS 30000
#define PGS_MIN_TIMEOUT_MILLIS 100
#define PGS_MAX_TIMEOUT_MILLIS 120000

enum pocketpc_graphics_session_result {
    PGS_OK = 0,
    PGS_INVALID_ARGUMENT = -1,
    PGS_ENVIRONMENT_INVALID = -2,
    PGS_TOKEN_INVALID = -3,
    PGS_SOCKET_CREATE_FAILED = -4,
    PGS_SOCKET_TIMEOUT_SETUP_FAILED = -5,
    PGS_CONNECT_FAILED = -6,
    PGS_HANDSHAKE_SEND_FAILED = -7,
    PGS_PID_INVALID = -8,
};

struct pocketpc_graphics_session {
    int fd;
    uint32_t peer_protocol;
    uint32_t pid;
};

/*
 * Connect the live guest process to the Android graphics broker using the
 * private environment injected into the exact PRoot/Box64/Wine launch:
 *
 *   POCKETPC_GRAPHICS_SOCKET_NAME
 *   POCKETPC_GRAPHICS_SESSION_TOKEN
 *   POCKETPC_GRAPHICS_SESSION_PROTOCOL
 *
 * A successful return proves only that PGH1 was sent over the authenticated
 * transport candidate. It does not prove that Android accepted the handshake,
 * that PVI1/PVS1 were received, that Vulkan imported them, or that GPU work ran.
 */
int pocketpc_graphics_session_connect_from_environment(
    struct pocketpc_graphics_session *session,
    int timeout_millis,
    char *error,
    size_t error_size
);

void pocketpc_graphics_session_close(
    struct pocketpc_graphics_session *session
);

int pocketpc_graphics_session_is_connected(
    const struct pocketpc_graphics_session *session
);

#ifdef __cplusplus
}
#endif

#endif
