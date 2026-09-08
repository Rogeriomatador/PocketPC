#define _GNU_SOURCE
#include "pocketpc_display_bridge.h"

#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static void set_error(char *error, size_t bytes, const char *message) {
    if (!error || !bytes) return;
    snprintf(error, bytes, "%s", message ? message : "PDB_ERROR");
}

static void put_u16le(unsigned char *p, uint16_t v) {
    p[0] = (unsigned char)(v & 0xffu);
    p[1] = (unsigned char)((v >> 8) & 0xffu);
}

static void put_u32le(unsigned char *p, uint32_t v) {
    p[0] = (unsigned char)(v & 0xffu);
    p[1] = (unsigned char)((v >> 8) & 0xffu);
    p[2] = (unsigned char)((v >> 16) & 0xffu);
    p[3] = (unsigned char)((v >> 24) & 0xffu);
}

static void put_u64le(unsigned char *p, uint64_t v) {
    int i;
    for (i = 0; i < 8; ++i) {
        p[i] = (unsigned char)((v >> (i * 8)) & 0xffu);
    }
}

static uint16_t get_u16le(const unsigned char *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static uint32_t get_u32le(const unsigned char *p) {
    return (uint32_t)p[0] |
        ((uint32_t)p[1] << 8) |
        ((uint32_t)p[2] << 16) |
        ((uint32_t)p[3] << 24);
}

static uint64_t get_u64le(const unsigned char *p) {
    uint64_t value = 0;
    int i;
    for (i = 0; i < 8; ++i) {
        value |= ((uint64_t)p[i]) << (i * 8);
    }
    return value;
}

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

static int decode_token(
    const char *text,
    unsigned char token[PDB_TOKEN_BYTES]
) {
    size_t i;
    if (!text || strlen(text) != PDB_TOKEN_BYTES * 2u) return -1;
    for (i = 0; i < PDB_TOKEN_BYTES; ++i) {
        int hi = hex_nibble(text[i * 2u]);
        int lo = hex_nibble(text[i * 2u + 1u]);
        if (hi < 0 || lo < 0) return -1;
        token[i] = (unsigned char)((hi << 4) | lo);
    }
    return 0;
}

static int write_all(int fd, const unsigned char *data, size_t bytes) {
    size_t offset = 0;
    while (offset < bytes) {
        ssize_t written = write(fd, data + offset, bytes - offset);
        if (written < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (written == 0) return -1;
        offset += (size_t)written;
    }
    return 0;
}

static int read_all(int fd, unsigned char *data, size_t bytes) {
    size_t offset = 0;
    while (offset < bytes) {
        ssize_t got = read(fd, data + offset, bytes - offset);
        if (got < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (got == 0) return -1;
        offset += (size_t)got;
    }
    return 0;
}

static int parse_caps(const char *text, uint32_t *caps) {
    char *end = NULL;
    unsigned long value;
    if (!text || !*text || !caps) return -1;
    errno = 0;
    value = strtoul(text, &end, 10);
    if (errno || !end || *end || value > 0xfffffffful) return -1;
    *caps = (uint32_t)value;
    return 0;
}

static int validate_identity(const char *identity) {
    size_t i;
    if (!identity || strlen(identity) != PDB_IDENTITY_BYTES) return -1;
    for (i = 0; i < PDB_IDENTITY_BYTES; ++i) {
        char c = identity[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return -1;
    }
    return 0;
}

static int send_raw_frame(
    int fd,
    uint16_t type,
    uint64_t sequence,
    const void *payload,
    uint32_t payload_bytes
) {
    unsigned char header[PDB_HEADER_BYTES];
    if (payload_bytes > PDB_MAX_PAYLOAD_BYTES) return -1;

    put_u32le(header + 0, PDB_MAGIC);
    put_u16le(header + 4, PDB_VERSION);
    put_u16le(header + 6, type);
    put_u32le(header + 8, payload_bytes);
    put_u64le(header + 12, sequence);

    if (write_all(fd, header, sizeof(header)) != 0) return -1;
    if (payload_bytes && write_all(fd, payload, payload_bytes) != 0) return -1;
    return 0;
}

static int receive_raw_frame(
    int fd,
    struct pdb_frame *frame
) {
    unsigned char header[PDB_HEADER_BYTES];
    unsigned char *payload = NULL;
    uint32_t payload_bytes;

    if (!frame) return -1;
    memset(frame, 0, sizeof(*frame));

    if (read_all(fd, header, sizeof(header)) != 0) return -1;
    if (get_u32le(header + 0) != PDB_MAGIC) return -1;
    if (get_u16le(header + 4) != PDB_VERSION) return -1;

    payload_bytes = get_u32le(header + 8);
    if (payload_bytes > PDB_MAX_PAYLOAD_BYTES) return -1;

    if (payload_bytes) {
        payload = (unsigned char *)malloc(payload_bytes);
        if (!payload) return -1;
        if (read_all(fd, payload, payload_bytes) != 0) {
            free(payload);
            return -1;
        }
    }

    frame->type = get_u16le(header + 6);
    frame->sequence = get_u64le(header + 12);
    frame->payload_bytes = payload_bytes;
    frame->payload = payload;
    return 0;
}

int pdb_connect_from_environment(
    struct pdb_connection *connection,
    char *error,
    size_t error_bytes
) {
    const char *protocol = getenv("POCKETPC_DISPLAY_PROTOCOL");
    const char *socket_name = getenv("POCKETPC_DISPLAY_SOCKET");
    const char *token_hex = getenv("POCKETPC_DISPLAY_TOKEN");
    const char *identity = getenv("POCKETPC_DISPLAY_RUNTIME_SHA256");
    const char *caps_text = getenv("POCKETPC_DISPLAY_HOST_CAPS");
    unsigned char token[PDB_TOKEN_BYTES];
    unsigned char hello[PDB_HELLO_BYTES];
    struct sockaddr_un address;
    struct pdb_frame ack;
    uint32_t caps;
    size_t name_len;
    socklen_t address_len;
    int fd = -1;

    if (!connection) {
        set_error(error, error_bytes, "PDB_CONNECTION_NULL");
        return -1;
    }
    connection->fd = -1;
    connection->negotiated_capabilities = 0;
    connection->next_sequence = 1;

    if (!protocol || strcmp(protocol, "1") != 0) {
        set_error(error, error_bytes, "PDB_PROTOCOL_INVALID");
        return -1;
    }
    if (!socket_name || !*socket_name) {
        set_error(error, error_bytes, "PDB_SOCKET_NAME_MISSING");
        return -1;
    }
    name_len = strlen(socket_name);
    if (name_len > sizeof(address.sun_path) - 2u) {
        set_error(error, error_bytes, "PDB_SOCKET_NAME_TOO_LONG");
        return -1;
    }
    if (decode_token(token_hex, token) != 0) {
        set_error(error, error_bytes, "PDB_TOKEN_INVALID");
        return -1;
    }
    if (validate_identity(identity) != 0) {
        set_error(error, error_bytes, "PDB_RUNTIME_ID_INVALID");
        return -1;
    }
    if (parse_caps(caps_text, &caps) != 0) {
        set_error(error, error_bytes, "PDB_CAPABILITIES_INVALID");
        return -1;
    }

    fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        set_error(error, error_bytes, "PDB_SOCKET_CREATE_FAILED");
        return -1;
    }

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, socket_name, name_len);
    address_len =
        (socklen_t)(
            offsetof(struct sockaddr_un, sun_path) +
            1u +
            name_len
        );

    if (connect(fd, (struct sockaddr *)&address, address_len) != 0) {
        close(fd);
        set_error(error, error_bytes, "PDB_CONNECT_FAILED");
        return -1;
    }

    memset(hello, 0, sizeof(hello));
    memcpy(hello, token, PDB_TOKEN_BYTES);
    memcpy(hello + PDB_TOKEN_BYTES, identity, PDB_IDENTITY_BYTES);
    put_u32le(
        hello + PDB_TOKEN_BYTES + PDB_IDENTITY_BYTES,
        caps
    );

    if (
        send_raw_frame(
            fd,
            PDB_MSG_HELLO,
            0,
            hello,
            sizeof(hello)
        ) != 0
    ) {
        close(fd);
        set_error(error, error_bytes, "PDB_HELLO_SEND_FAILED");
        return -1;
    }

    if (receive_raw_frame(fd, &ack) != 0) {
        close(fd);
        set_error(error, error_bytes, "PDB_HELLO_ACK_READ_FAILED");
        return -1;
    }

    if (
        ack.type != PDB_MSG_HELLO_ACK ||
        ack.sequence != 0 ||
        ack.payload_bytes != 4u
    ) {
        pdb_release_frame(&ack);
        close(fd);
        set_error(error, error_bytes, "PDB_HELLO_ACK_INVALID");
        return -1;
    }

    connection->negotiated_capabilities =
        get_u32le(ack.payload);
    if (
        (connection->negotiated_capabilities & caps) !=
        connection->negotiated_capabilities
    ) {
        pdb_release_frame(&ack);
        close(fd);
        set_error(error, error_bytes, "PDB_HELLO_ACK_CAPS_INVALID");
        return -1;
    }

    pdb_release_frame(&ack);
    connection->fd = fd;
    return 0;
}

int pdb_send_frame(
    struct pdb_connection *connection,
    uint16_t type,
    const void *payload,
    uint32_t payload_bytes,
    char *error,
    size_t error_bytes
) {
    uint64_t sequence;
    if (!connection || connection->fd < 0) {
        set_error(error, error_bytes, "PDB_NOT_CONNECTED");
        return -1;
    }
    if (payload_bytes > PDB_MAX_PAYLOAD_BYTES) {
        set_error(error, error_bytes, "PDB_PAYLOAD_TOO_LARGE");
        return -1;
    }
    if (payload_bytes && !payload) {
        set_error(error, error_bytes, "PDB_PAYLOAD_NULL");
        return -1;
    }

    sequence = connection->next_sequence++;
    if (
        send_raw_frame(
            connection->fd,
            type,
            sequence,
            payload,
            payload_bytes
        ) != 0
    ) {
        set_error(error, error_bytes, "PDB_SEND_FAILED");
        return -1;
    }
    return 0;
}

int pdb_receive_frame(
    struct pdb_connection *connection,
    struct pdb_frame *frame,
    char *error,
    size_t error_bytes
) {
    if (!connection || connection->fd < 0 || !frame) {
        set_error(error, error_bytes, "PDB_RECEIVE_ARGUMENT_INVALID");
        return -1;
    }
    if (receive_raw_frame(connection->fd, frame) != 0) {
        set_error(error, error_bytes, "PDB_RECEIVE_FAILED");
        return -1;
    }
    return 0;
}

int pdb_send_window_create(
    struct pdb_connection *connection,
    uint64_t window_id,
    uint64_t parent_id,
    uint32_t flags,
    int32_t width,
    int32_t height,
    char *error,
    size_t error_bytes
) {
    unsigned char payload[PDB_WINDOW_CREATE_BYTES];
    if (!window_id || parent_id == window_id) {
        set_error(error, error_bytes, "PDB_WINDOW_ID_INVALID");
        return -1;
    }
    if (width <= 0 || width > 16384 || height <= 0 || height > 16384) {
        set_error(error, error_bytes, "PDB_WINDOW_DIMENSION_INVALID");
        return -1;
    }

    put_u64le(payload + 0, window_id);
    put_u64le(payload + 8, parent_id);
    put_u32le(payload + 16, flags);
    put_u32le(payload + 20, (uint32_t)width);
    put_u32le(payload + 24, (uint32_t)height);
    return pdb_send_frame(
        connection,
        PDB_MSG_WINDOW_CREATE,
        payload,
        sizeof(payload),
        error,
        error_bytes
    );
}

int pdb_send_window_geometry(
    struct pdb_connection *connection,
    uint64_t window_id,
    int32_t x,
    int32_t y,
    int32_t width,
    int32_t height,
    uint32_t visible,
    int32_t z_order,
    char *error,
    size_t error_bytes
) {
    unsigned char payload[PDB_WINDOW_GEOMETRY_BYTES];
    if (!window_id) {
        set_error(error, error_bytes, "PDB_WINDOW_ID_INVALID");
        return -1;
    }
    if (width <= 0 || width > 16384 || height <= 0 || height > 16384) {
        set_error(error, error_bytes, "PDB_WINDOW_DIMENSION_INVALID");
        return -1;
    }
    if (x < -1000000 || x > 1000000 || y < -1000000 || y > 1000000) {
        set_error(error, error_bytes, "PDB_WINDOW_COORDINATE_INVALID");
        return -1;
    }
    if (visible > 1u) {
        set_error(error, error_bytes, "PDB_WINDOW_VISIBLE_INVALID");
        return -1;
    }

    put_u64le(payload + 0, window_id);
    put_u32le(payload + 8, (uint32_t)x);
    put_u32le(payload + 12, (uint32_t)y);
    put_u32le(payload + 16, (uint32_t)width);
    put_u32le(payload + 20, (uint32_t)height);
    put_u32le(payload + 24, visible);
    put_u32le(payload + 28, (uint32_t)z_order);
    return pdb_send_frame(
        connection,
        PDB_MSG_WINDOW_GEOMETRY,
        payload,
        sizeof(payload),
        error,
        error_bytes
    );
}

int pdb_send_window_destroy(
    struct pdb_connection *connection,
    uint64_t window_id,
    char *error,
    size_t error_bytes
) {
    unsigned char payload[PDB_WINDOW_DESTROY_BYTES];
    if (!window_id) {
        set_error(error, error_bytes, "PDB_WINDOW_ID_INVALID");
        return -1;
    }
    put_u64le(payload, window_id);
    return pdb_send_frame(
        connection,
        PDB_MSG_WINDOW_DESTROY,
        payload,
        sizeof(payload),
        error,
        error_bytes
    );
}

void pdb_release_frame(struct pdb_frame *frame) {
    if (!frame) return;
    free(frame->payload);
    memset(frame, 0, sizeof(*frame));
}

void pdb_close(struct pdb_connection *connection) {
    if (!connection) return;
    if (connection->fd >= 0) close(connection->fd);
    connection->fd = -1;
    connection->negotiated_capabilities = 0;
    connection->next_sequence = 0;
}
