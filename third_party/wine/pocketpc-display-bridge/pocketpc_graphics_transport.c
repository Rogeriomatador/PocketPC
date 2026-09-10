#include "pocketpc_graphics_transport.h"

#include <errno.h>
#include <limits.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

typedef char pgt_header_size_must_be_24[
    sizeof(struct pgt_frame_header) == PGT_HEADER_BYTES ? 1 : -1
];
typedef char pgt_descriptor_size_must_be_64[
    sizeof(struct pgt_resource_descriptor) == PGT_RESOURCE_DESCRIPTOR_BYTES ? 1 : -1
];

static int pgt_state_valid(uint32_t state)
{
    return state >= PGT_STATE_HOST_AVAILABLE &&
        state <= PGT_STATE_RETIRED;
}

static void pgt_put_u16_le(unsigned char *out, uint16_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
}

static void pgt_put_u32_le(unsigned char *out, uint32_t value)
{
    out[0] = (unsigned char)(value & 0xffu);
    out[1] = (unsigned char)((value >> 8u) & 0xffu);
    out[2] = (unsigned char)((value >> 16u) & 0xffu);
    out[3] = (unsigned char)((value >> 24u) & 0xffu);
}

static int pgt_hex_nibble(char value)
{
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static int pgt_decode_token(
    const char *token_hex,
    unsigned char token[PGT_SESSION_TOKEN_BYTES]
) {
    size_t index;

    if (!token_hex || !token) return -1;
    if (strlen(token_hex) != PGT_SESSION_TOKEN_HEX_BYTES) return -2;

    for (index = 0; index < PGT_SESSION_TOKEN_BYTES; ++index) {
        const int high = pgt_hex_nibble(token_hex[index * 2u]);
        const int low = pgt_hex_nibble(token_hex[index * 2u + 1u]);
        if (high < 0 || low < 0) return -3;
        token[index] = (unsigned char)((high << 4) | low);
    }
    return 0;
}

int pgt_validate_header(
    const struct pgt_frame_header *header,
    uint16_t expected_type,
    uint32_t expected_payload_bytes
) {
    if (!header) return -1;
    if (header->magic != PGT_MAGIC) return -2;
    if (header->version != PGT_VERSION) return -3;
    if (header->type != expected_type) return -4;
    if (header->payload_bytes != expected_payload_bytes) return -5;
    if (header->payload_bytes > PGT_MAX_FRAME_BYTES) return -6;
    if (header->reserved != 0u) return -7;
    if (header->sequence == 0u) return -8;
    return 0;
}

int pgt_validate_resource_descriptor(
    const struct pgt_resource_descriptor *descriptor
) {
    uint64_t pixels;

    if (!descriptor) return -1;
    if (descriptor->resource_id == 0u) return -2;
    if (descriptor->generation == 0u) return -3;
    if (
        descriptor->width == 0u ||
        descriptor->width > PGT_MAX_DIMENSION ||
        descriptor->height == 0u ||
        descriptor->height > PGT_MAX_DIMENSION
    ) {
        return -4;
    }
    if (
        descriptor->layers == 0u ||
        descriptor->layers > PGT_MAX_LAYERS
    ) {
        return -5;
    }
    if (descriptor->pixel_format == 0u) return -6;
    if (descriptor->producer_pid == 0u) return -7;
    if (descriptor->reserved != 0u) return -8;
    if (descriptor->process_namespace == 0u) return -9;

    pixels = (uint64_t)descriptor->width * descriptor->height;
    if (
        descriptor->layers != 0u &&
        pixels > UINT64_MAX / descriptor->layers
    ) {
        return -10;
    }
    pixels *= descriptor->layers;
    if (pixels == 0u) return -11;

    return 0;
}

int pgt_next_ownership_state(
    uint32_t current_state,
    uint16_t event_type,
    uint32_t *next_state
) {
    uint32_t next;

    if (!next_state) return -1;
    if (!pgt_state_valid(current_state)) return -2;

    if (
        current_state == PGT_STATE_HOST_AVAILABLE &&
        event_type == PGT_MSG_RESOURCE_OFFER
    ) {
        next = PGT_STATE_OFFERED_TO_GUEST;
    } else if (
        current_state == PGT_STATE_OFFERED_TO_GUEST &&
        event_type == PGT_MSG_GUEST_IMPORTED
    ) {
        next = PGT_STATE_GUEST_IMPORTED;
    } else if (
        current_state == PGT_STATE_GUEST_IMPORTED &&
        event_type == PGT_MSG_GUEST_RENDER_BEGIN
    ) {
        next = PGT_STATE_GUEST_RENDERING;
    } else if (
        current_state == PGT_STATE_GUEST_RENDERING &&
        event_type == PGT_MSG_GUEST_RENDER_COMPLETE
    ) {
        next = PGT_STATE_GUEST_RENDER_COMPLETE;
    } else if (
        current_state == PGT_STATE_GUEST_RENDER_COMPLETE &&
        event_type == PGT_MSG_HOST_PRESENT_BEGIN
    ) {
        next = PGT_STATE_HOST_PRESENTING;
    } else if (
        current_state == PGT_STATE_HOST_PRESENTING &&
        event_type == PGT_MSG_HOST_PRESENT_COMPLETE
    ) {
        next = PGT_STATE_HOST_AVAILABLE;
    } else if (
        current_state == PGT_STATE_HOST_AVAILABLE &&
        event_type == PGT_MSG_RESOURCE_RETIRE
    ) {
        next = PGT_STATE_RETIRED;
    } else {
        return -3;
    }

    *next_state = next;
    return 0;
}

int pgt_validate_ownership_transition(
    const struct pgt_ownership_record *current,
    uint16_t event_type,
    uint64_t next_sequence,
    struct pgt_ownership_record *next
) {
    uint32_t next_state;
    int state_result;

    if (!current || !next) return -1;
    if (
        current->resource_id == 0u ||
        current->generation == 0u ||
        current->reserved != 0u ||
        !pgt_state_valid(current->state)
    ) {
        return -2;
    }
    if (current->sequence == UINT64_MAX) return -3;
    if (next_sequence != current->sequence + 1u) return -4;

    state_result = pgt_next_ownership_state(
        current->state,
        event_type,
        &next_state
    );
    if (state_result != 0) return -5;

    memset(next, 0, sizeof(*next));
    next->resource_id = current->resource_id;
    next->generation = current->generation;
    next->sequence = next_sequence;
    next->state = next_state;
    return 0;
}

int pgt_connect_authenticated_session(
    const char *socket_name,
    const char *token_hex
) {
    struct sockaddr_un address;
    unsigned char token[PGT_SESSION_TOKEN_BYTES];
    unsigned char handshake[PGT_SESSION_HANDSHAKE_BYTES];
    size_t name_length;
    socklen_t address_length;
    ssize_t sent;
    pid_t pid;
    int fd;

    if (!socket_name || !token_hex) return -1;
    name_length = strlen(socket_name);
    if (name_length == 0u || name_length > PGT_SESSION_MAX_SOCKET_NAME_BYTES)
        return -2;
    if (pgt_decode_token(token_hex, token) != 0) return -3;

    fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0);
    if (fd < 0) return -4;

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    address.sun_path[0] = '\0';
    memcpy(address.sun_path + 1, socket_name, name_length);
    address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1u + name_length);

    if (connect(fd, (const struct sockaddr *)&address, address_length) != 0) {
        close(fd);
        return -5;
    }

    pid = getpid();
    if (pid <= 0 || (uint64_t)pid > UINT32_MAX) {
        close(fd);
        return -6;
    }

    memset(handshake, 0, sizeof(handshake));
    pgt_put_u32_le(handshake + 0, PGT_SESSION_MAGIC);
    pgt_put_u16_le(handshake + 4, PGT_SESSION_VERSION);
    pgt_put_u16_le(handshake + 6, 0u);
    pgt_put_u32_le(handshake + 8, (uint32_t)pid);
    pgt_put_u32_le(handshake + 12, 0u);
    memcpy(handshake + 16, token, sizeof(token));
    memset(token, 0, sizeof(token));

    do {
        sent = send(fd, handshake, sizeof(handshake), MSG_NOSIGNAL);
    } while (sent < 0 && errno == EINTR);
    memset(handshake, 0, sizeof(handshake));

    if (sent != (ssize_t)PGT_SESSION_HANDSHAKE_BYTES) {
        close(fd);
        return -7;
    }
    return fd;
}

int pgt_connect_authenticated_session_from_environment(void)
{
    const char *socket_name = getenv(PGT_SESSION_ENV_SOCKET_NAME);
    const char *token_hex = getenv(PGT_SESSION_ENV_TOKEN);
    const char *protocol = getenv(PGT_SESSION_ENV_PROTOCOL);

    if (!socket_name || !token_hex || !protocol || strcmp(protocol, "1"))
        return -1;
    return pgt_connect_authenticated_session(socket_name, token_hex);
}
