#define _GNU_SOURCE

#include "pocketpc_graphics_session_client.h"

#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define PGS_MAGIC 0x31484750u /* PGH1 little-endian */

static void set_error(char *error, size_t error_size, const char *message)
{
    if (error && error_size)
        snprintf(error, error_size, "%s", message ? message : "PGS_ERROR");
}

static void put_u16_le(unsigned char *out, uint16_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
}

static void put_u32_le(unsigned char *out, uint32_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
    out[2] = (unsigned char)((value >> 16u) & 0xffu);
    out[3] = (unsigned char)((value >> 24u) & 0xffu);
}

static int hex_nibble(char value)
{
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static int decode_token(const char *text, unsigned char token[PGS_TOKEN_BYTES])
{
    size_t index;

    if (!text || strlen(text) != PGS_TOKEN_BYTES * 2u) return -1;
    for (index = 0; index < PGS_TOKEN_BYTES; ++index)
    {
        const int high = hex_nibble(text[index * 2u]);
        const int low = hex_nibble(text[index * 2u + 1u]);
        if (high < 0 || low < 0) return -1;
        token[index] = (unsigned char)((high << 4) | low);
    }
    return 0;
}

static int parse_protocol(const char *text)
{
    char *end = NULL;
    unsigned long value;

    if (!text || !*text) return -1;
    errno = 0;
    value = strtoul(text, &end, 10);
    if (errno || !end || *end || value != PGS_PROTOCOL_VERSION) return -1;
    return 0;
}

static int set_socket_timeout(int fd, int timeout_millis)
{
    struct timeval timeout;

    if (fd < 0 || timeout_millis < PGS_MIN_TIMEOUT_MILLIS ||
        timeout_millis > PGS_MAX_TIMEOUT_MILLIS)
        return -1;

    memset(&timeout, 0, sizeof(timeout));
    timeout.tv_sec = timeout_millis / 1000;
    timeout.tv_usec = (timeout_millis % 1000) * 1000;

    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0)
        return -1;
    if (setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) != 0)
        return -1;
    return 0;
}

static int send_packet_exact(int fd, const unsigned char *payload, size_t payload_size)
{
    ssize_t sent;

    if (fd < 0 || !payload || !payload_size) return -1;
    do
    {
        sent = send(fd, payload, payload_size, MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);

    return sent == (ssize_t)payload_size ? 0 : -1;
}

void pocketpc_graphics_session_close(struct pocketpc_graphics_session *session)
{
    if (!session) return;
    if (session->fd >= 0) close(session->fd);
    session->fd = -1;
    session->peer_protocol = 0;
    session->pid = 0;
}

int pocketpc_graphics_session_is_connected(const struct pocketpc_graphics_session *session)
{
    return session && session->fd >= 0 &&
           session->peer_protocol == PGS_PROTOCOL_VERSION && session->pid != 0;
}

int pocketpc_graphics_session_connect_from_environment(
    struct pocketpc_graphics_session *session,
    int timeout_millis,
    char *error,
    size_t error_size)
{
    const char *socket_name;
    const char *token_text;
    const char *protocol_text;
    unsigned char token[PGS_TOKEN_BYTES];
    unsigned char handshake[PGS_HANDSHAKE_BYTES];
    struct sockaddr_un address;
    size_t socket_name_length;
    socklen_t address_length;
    pid_t process_pid;
    int fd = -1;

    if (!session || timeout_millis < PGS_MIN_TIMEOUT_MILLIS ||
        timeout_millis > PGS_MAX_TIMEOUT_MILLIS)
    {
        set_error(error, error_size, "PGS_ARGUMENT_INVALID");
        return PGS_INVALID_ARGUMENT;
    }

    pocketpc_graphics_session_close(session);

    socket_name = getenv("POCKETPC_GRAPHICS_SOCKET_NAME");
    token_text = getenv("POCKETPC_GRAPHICS_SESSION_TOKEN");
    protocol_text = getenv("POCKETPC_GRAPHICS_SESSION_PROTOCOL");

    if (!socket_name || !*socket_name ||
        strlen(socket_name) > PGS_MAX_SOCKET_NAME_BYTES ||
        parse_protocol(protocol_text) != 0)
    {
        set_error(error, error_size, "PGS_ENVIRONMENT_INVALID");
        return PGS_ENVIRONMENT_INVALID;
    }
    if (decode_token(token_text, token) != 0)
    {
        set_error(error, error_size, "PGS_TOKEN_INVALID");
        return PGS_TOKEN_INVALID;
    }

    process_pid = getpid();
    if (process_pid <= 0 || (uint64_t)process_pid > UINT32_MAX)
    {
        set_error(error, error_size, "PGS_PID_INVALID");
        return PGS_PID_INVALID;
    }

    fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0)
    {
        set_error(error, error_size, "PGS_SOCKET_CREATE_FAILED");
        return PGS_SOCKET_CREATE_FAILED;
    }
    if (set_socket_timeout(fd, timeout_millis) != 0)
    {
        close(fd);
        set_error(error, error_size, "PGS_SOCKET_TIMEOUT_SETUP_FAILED");
        return PGS_SOCKET_TIMEOUT_SETUP_FAILED;
    }

    socket_name_length = strlen(socket_name);
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, socket_name, socket_name_length);
    address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1u + socket_name_length);

    if (connect(fd, (const struct sockaddr *)&address, address_length) != 0)
    {
        close(fd);
        set_error(error, error_size, "PGS_CONNECT_FAILED");
        return PGS_CONNECT_FAILED;
    }

    memset(handshake, 0, sizeof(handshake));
    put_u32_le(handshake + 0, PGS_MAGIC);
    put_u16_le(handshake + 4, PGS_PROTOCOL_VERSION);
    put_u16_le(handshake + 6, 0u);
    put_u32_le(handshake + 8, (uint32_t)process_pid);
    put_u32_le(handshake + 12, 0u);
    memcpy(handshake + 16, token, sizeof(token));

    if (send_packet_exact(fd, handshake, sizeof(handshake)) != 0)
    {
        close(fd);
        set_error(error, error_size, "PGS_HANDSHAKE_SEND_FAILED");
        return PGS_HANDSHAKE_SEND_FAILED;
    }

    session->fd = fd;
    session->peer_protocol = PGS_PROTOCOL_VERSION;
    session->pid = (uint32_t)process_pid;
    set_error(error, error_size, "");
    return PGS_OK;
}
