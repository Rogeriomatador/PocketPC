#define _GNU_SOURCE

#include "pocketpc_graphics_session_client.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#define PGH1_MAGIC 0x31484750u

static uint16_t get_u16_le(const unsigned char *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8u);
}

static uint32_t get_u32_le(const unsigned char *p)
{
    return (uint32_t)p[0] |
           ((uint32_t)p[1] << 8u) |
           ((uint32_t)p[2] << 16u) |
           ((uint32_t)p[3] << 24u);
}

static int make_server(const char *name)
{
    struct sockaddr_un address;
    size_t name_length = strlen(name);
    socklen_t address_length;
    int fd;

    fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, name, name_length);
    address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1u + name_length);

    if (bind(fd, (const struct sockaddr *)&address, address_length) != 0 ||
        listen(fd, 1) != 0)
    {
        close(fd);
        return -1;
    }
    return fd;
}

int main(void)
{
    static const char token_hex[] =
        "00112233445566778899aabbccddeeff"
        "102132435465768798a9bacbdcedfe0f";
    unsigned char expected_token[PGS_TOKEN_BYTES];
    unsigned char packet[PGS_HANDSHAKE_BYTES];
    char socket_name[64];
    pid_t child;
    int server_fd;
    int accepted_fd;
    int status;
    size_t index;
    ssize_t received;

    snprintf(socket_name, sizeof(socket_name), "pocketpc-pgh1-smoke-%ld", (long)getpid());
    server_fd = make_server(socket_name);
    if (server_fd < 0)
    {
        fprintf(stderr, "server create failed: %s\n", strerror(errno));
        return 1;
    }

    child = fork();
    if (child < 0)
    {
        close(server_fd);
        return 2;
    }

    if (child == 0)
    {
        struct pocketpc_graphics_session session = {.fd = -1};
        char error[128] = {0};
        int result;

        close(server_fd);
        if (setenv("POCKETPC_GRAPHICS_SOCKET_NAME", socket_name, 1) != 0 ||
            setenv("POCKETPC_GRAPHICS_SESSION_TOKEN", token_hex, 1) != 0 ||
            setenv("POCKETPC_GRAPHICS_SESSION_PROTOCOL", "1", 1) != 0)
            _exit(10);

        result = pocketpc_graphics_session_connect_from_environment(
            &session,
            3000,
            error,
            sizeof(error));
        if (result != PGS_OK || !pocketpc_graphics_session_is_connected(&session))
        {
            fprintf(stderr, "client failed: %d %s\n", result, error);
            _exit(11);
        }
        pocketpc_graphics_session_close(&session);
        _exit(0);
    }

    do
    {
        accepted_fd = accept4(server_fd, NULL, NULL, SOCK_CLOEXEC);
    } while (accepted_fd < 0 && errno == EINTR);
    close(server_fd);
    if (accepted_fd < 0) return 3;

    do
    {
        received = recv(accepted_fd, packet, sizeof(packet), MSG_TRUNC);
    } while (received < 0 && errno == EINTR);
    close(accepted_fd);
    if (received != (ssize_t)sizeof(packet)) return 4;

    for (index = 0; index < PGS_TOKEN_BYTES; ++index)
    {
        unsigned int value = 0;
        if (sscanf(token_hex + index * 2u, "%2x", &value) != 1) return 5;
        expected_token[index] = (unsigned char)value;
    }

    if (get_u32_le(packet + 0) != PGH1_MAGIC ||
        get_u16_le(packet + 4) != PGS_PROTOCOL_VERSION ||
        get_u16_le(packet + 6) != 0 ||
        get_u32_le(packet + 8) != (uint32_t)child ||
        get_u32_le(packet + 12) != 0 ||
        memcmp(packet + 16, expected_token, sizeof(expected_token)) != 0)
        return 6;

    if (waitpid(child, &status, 0) != child || !WIFEXITED(status) || WEXITSTATUS(status) != 0)
        return 7;

    puts("POCKETPC_GRAPHICS_SESSION_CLIENT_SMOKE_PASS");
    return 0;
}
