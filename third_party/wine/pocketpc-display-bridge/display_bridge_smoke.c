#include "pocketpc_display_bridge.h"

#include <stdio.h>

int main(void) {
    struct pdb_connection connection;
    char error[160] = {0};

    if (
        pdb_connect_from_environment(
            &connection,
            error,
            sizeof(error)
        ) != 0
    ) {
        fprintf(
            stderr,
            "POCKETPC_DISPLAY_BRIDGE_SMOKE_FAILED %s\n",
            error
        );
        return 80;
    }

    printf(
        "POCKETPC_DISPLAY_BRIDGE_SMOKE_OK caps=%u\n",
        connection.negotiated_capabilities
    );
    pdb_close(&connection);
    return 0;
}
