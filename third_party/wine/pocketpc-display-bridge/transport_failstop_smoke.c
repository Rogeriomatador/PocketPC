#include "pocketpc_display_bridge.h"

#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static void init_connection(
    struct pdb_connection *connection,
    int fd
) {
    memset(
        connection,
        0,
        sizeof(*connection)
    );
    connection->fd = fd;
    connection->negotiated_capabilities =
        PDB_HOST_BASELINE;
    connection->next_sequence = 1u;
    connection->expected_inbound_sequence = 1u;
}

static int is_invalidated(
    const struct pdb_connection *connection
) {
    return
        connection->fd == -1 &&
        connection->negotiated_capabilities == 0u &&
        connection->next_sequence == 0u &&
        connection->expected_inbound_sequence == 0u;
}

int main(void) {
    int sockets[2] = {-1, -1};
    struct pdb_connection sender;
    struct pdb_connection receiver;
    struct pdb_frame frame;
    char error[160] = {0};

    signal(SIGPIPE, SIG_IGN);

    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        return 10;
    }

    init_connection(
        &sender,
        sockets[0]
    );
    close(sockets[1]);

    if (
        pdb_send_frame(
            &sender,
            PDB_MSG_ERROR,
            NULL,
            0u,
            error,
            sizeof(error)
        ) == 0
    ) {
        return 11;
    }
    if (!is_invalidated(&sender)) {
        return 12;
    }

    sockets[0] = -1;
    sockets[1] = -1;
    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        return 13;
    }

    init_connection(
        &sender,
        sockets[0]
    );
    init_connection(
        &receiver,
        sockets[1]
    );

    sender.next_sequence = 2u;
    if (
        pdb_send_frame(
            &sender,
            PDB_MSG_ERROR,
            NULL,
            0u,
            error,
            sizeof(error)
        ) != 0
    ) {
        return 14;
    }

    if (
        pdb_receive_frame(
            &receiver,
            &frame,
            error,
            sizeof(error)
        ) == 0
    ) {
        pdb_release_frame(&frame);
        return 15;
    }
    if (!is_invalidated(&receiver)) {
        return 16;
    }

    pdb_close(&sender);

    sockets[0] = -1;
    sockets[1] = -1;
    if (
        socketpair(
            AF_UNIX,
            SOCK_STREAM,
            0,
            sockets
        ) != 0
    ) {
        return 17;
    }

    init_connection(
        &sender,
        sockets[0]
    );
    sender.next_sequence = 0u;

    if (
        pdb_send_frame(
            &sender,
            PDB_MSG_ERROR,
            NULL,
            0u,
            error,
            sizeof(error)
        ) == 0
    ) {
        return 18;
    }
    if (!is_invalidated(&sender)) {
        return 19;
    }

    close(sockets[1]);

    printf(
        "POCKETPC_DISPLAY_BRIDGE_FAILSTOP_OK\n"
    );
    return 0;
}
