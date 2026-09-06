# Runtime package format — Alpha 7

## Package schemas

Schema v1 remains staging-only/opaque.

Schema v2 declares:

- ARM64 architecture;
- archive SHA-256 and bytes;
- tar or tar.gz;
- extracted byte limit;
- entry limit;
- Linux entrypoint;
- license metadata.

## State machine

~~~text
SOURCE ARCHIVE
   ↓
STAGED_VERIFIED
   ↓
INSTALLED_DATA
   ↓
LINKS_PREPARED
~~~

None of those states means Linux execution is enabled.

## STAGED_VERIFIED

PocketPC:

1. validates manifest;
2. checks ARM64 support;
3. reserves free space;
4. streams archive;
5. enforces declared archive bytes;
6. verifies SHA-256;
7. promotes staging transactionally.

## INSTALLED_DATA

SafeTarExtractor:

- validates TAR checksum;
- supports bounded PAX/GNU long-name headers;
- rejects absolute/dot-dot paths;
- rejects duplicate/special entries;
- enforces byte/entry/path/header limits;
- writes regular files/directories only;
- stores symlink/hardlink information in rootfs.metadata.tsv.

INSTALL_VERIFIED records that links were not materialized during extraction.

## LINKS_PREPARED

Alpha 7 adds a separate link transaction.

RootfsLinkManager:

- validates metadata and link count;
- recovers an interrupted previous link attempt;
- resolves hardlink chains and rejects cycles;
- validates guest symlink resolution;
- hashes metadata;
- creates hardlinks;
- creates symlinks;
- writes LINKS_PREPARED;
- verifies target text/inode identity.

The split is intentional: no archive write occurs after guest links exist.

## Metadata format

Each line:

~~~text
TYPE MODE UID GID SIZE MTIME BASE64URL_PATH BASE64URL_TARGET
~~~

Types:

- F regular file
- D directory
- S symlink
- H hardlink

## Cleanup

Installed rootfs trees may contain links after LINKS_PREPARED.

They must be removed with SafeTreeOps.deleteNoFollow, not generic recursive traversal.

## Launch boundary

RuntimeLaunchPlanner requires:

- valid metadata;
- LINKS_PREPARED when links exist;
- link verification;
- guest-aware entrypoint resolution;
- resolved regular file.

It still adds EXECUTOR_NOT_IMPLEMENTED.
