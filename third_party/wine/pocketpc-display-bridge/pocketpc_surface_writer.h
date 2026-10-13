#ifndef POCKETPC_SURFACE_WRITER_H
#define POCKETPC_SURFACE_WRITER_H

#include <stddef.h>
#include <stdint.h>

#include "pocketpc_display_bridge.h"

#ifdef __cplusplus
extern "C" {
#endif

struct pdb_surface_rect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
};

struct pdb_surface_writer {
    int fd;
    unsigned char *mapping;
    size_t mapped_bytes;
    struct pdb_surface_available surface;
    uint64_t next_frame_id;
};

void pdb_surface_writer_init(
    struct pdb_surface_writer *
);

int pdb_surface_writer_open(
    struct pdb_surface_writer *,
    const struct pdb_surface_available *,
    char *,
    size_t
);

int pdb_surface_writer_copy_bgra(
    struct pdb_surface_writer *,
    const void *source,
    int32_t source_width,
    int32_t source_height,
    int32_t source_stride_bytes,
    int source_top_down,
    const struct pdb_surface_rect *dirty,
    int force_opaque,
    char *,
    size_t
);

int pdb_surface_writer_commit(
    struct pdb_surface_writer *,
    struct pdb_connection *,
    uint64_t *,
    char *,
    size_t
);

void pdb_surface_writer_close(
    struct pdb_surface_writer *
);

#ifdef __cplusplus
}
#endif

#endif
