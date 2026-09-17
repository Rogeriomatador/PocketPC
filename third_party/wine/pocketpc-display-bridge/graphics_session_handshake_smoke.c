#define _GNU_SOURCE
#include "pocketpc_graphics_transport.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

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

int main(void)
{
    static const char token_hex[] =
        "00112233445566778899aabbccddeeff"
        "102132435465768798a9bacbdcedfe0f";
    static const unsigned char token[PGT_SESSION_TOKEN_BYTES] = {
        0x00,0x11,0x22,0x33,0x44,0x55,0x66,0x77,
        0x88,0x99,0xaa,0xbb,0xcc,0xdd,0xee,0xff,
        0x10,0x21,0x32,0x43,0x54,0x65,0x76,0x87,
        0x98,0xa9,0xba,0xcb,0xdc,0xed,0xfe,0x0f,
    };
    char socket_name[PGT_SESSION_MAX_SOCKET_NAME_BYTES + 1u];
    struct sockaddr_un address;
    unsigned char handshake[PGT_SESSION_HANDSHAKE_BYTES];
    struct ucred peer;
    socklen_t peer_len = sizeof(peer);
    socklen_t address_len;
    ssize_t received;
    pid_t child;
    int status = 0;
    int server_fd;
    int accepted_fd;
    int child_fd;

    snprintf(socket_name, sizeof(socket_name), "pocketpc-gfx-smoke-%ld", (long)getpid());
    server_fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (server_fd < 0) return 10;

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, socket_name, strlen(socket_name));
    address_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1u + strlen(socket_name));
    if (bind(server_fd, (const struct sockaddr *)&address, address_len) != 0) return 11;
    if (listen(server_fd, 1) != 0) return 12;

    child = fork();
    if (child < 0) return 13;
    if (child == 0) {
        close(server_fd);
        child_fd = pgt_connect_authenticated_session(socket_name, token_hex);
        if (child_fd < 0) _exit(20);
        close(child_fd);
        _exit(0);
    }

    do {
        accepted_fd = accept4(server_fd, NULL, NULL, SOCK_CLOEXEC);
    } while (accepted_fd < 0 && errno == EINTR);
    if (accepted_fd < 0) return 14;

    received = recv(accepted_fd, handshake, sizeof(handshake), 0);
    if (received != (ssize_t)sizeof(handshake)) return 15;
    if (get_u32_le(handshake + 0) != PGT_SESSION_MAGIC) return 16;
    if (get_u16_le(handshake + 4) != PGT_SESSION_VERSION || get_u16_le(handshake + 6) != 0u) return 17;
    if (get_u32_le(handshake + 12) != 0u) return 18;
    if (memcmp(handshake + 16, token, sizeof(token)) != 0) return 19;

    if (getsockopt(accepted_fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_len) != 0) return 21;
    if (peer_len != sizeof(peer) || peer.pid != child) return 22;
    if (get_u32_le(handshake + 8) != (uint32_t)peer.pid) return 23;

    close(accepted_fd);
    close(server_fd);
    if (waitpid(child, &status, 0) != child) return 24;
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) return 25;

    if (pgt_connect_authenticated_session("", token_hex) >= 0) return 26;
    if (pgt_connect_authenticated_session(socket_name, "bad") >= 0) return 27;

    puts("POCKETPC_GRAPHICS_SESSION_HANDSHAKE_SMOKE_OK");
    puts("transport=AF_UNIX/SOCK_SEQPACKET");
    puts("authentication=token+SO_PEERCRED-contract");
    puts("graphics_import=false");
    puts("gpu_sync=false");
    puts("roblox=false");
    return 0;
}
