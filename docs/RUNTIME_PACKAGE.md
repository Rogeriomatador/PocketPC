# Runtime package format — schemas v1 and v2

## State

- schema v1 staging: **IMPLEMENTED**
- schema v2 staging: **IMPLEMENTED**
- schema v2 safe data extraction: **IMPLEMENTED source**
- Linux execution: **NOT IMPLEMENTED**

## Schema v1

Schema v1 is preserved for compatibility and remains opaque/non-extractable.

## Schema v2

~~~json
{
  "schemaVersion": 2,
  "id": "debian.bookworm",
  "name": "Debian Bookworm",
  "version": "12.9-1",
  "architecture": "aarch64",
  "rootfsSha256": "<64 hex characters>",
  "rootfsBytes": 123456789,
  "entrypoint": "/bin/sh",
  "license": "Upstream license/redistribution metadata",
  "archiveFormat": "tar.gz",
  "extractedBytesLimit": 2000000000,
  "entryLimit": 500000
}
~~~

Supported archive formats in v2:

- tar
- tar.gz

The manifest must declare both a maximum extracted byte budget and entry-count budget.

## Staging

1. select manifest;
2. select rootfs archive;
3. validate schema/ABI/path fields;
4. check free-space reserve;
5. stream-copy archive;
6. enforce declared archive bytes;
7. verify SHA-256;
8. write VERIFIED metadata;
9. transactionally promote to STAGED_VERIFIED.

## Installation as data

For schema v2 only:

1. re-audit archive SHA-256;
2. reserve space according to extractedBytesLimit;
3. create temporary install transaction;
4. stream TAR or TAR.GZ through SafeTarExtractor;
5. validate every TAR header checksum;
6. reject absolute/dot-dot paths;
7. reject duplicate entries;
8. reject device/FIFO/special types;
9. enforce byte/entry/path/header limits;
10. write regular files/directories under rootfs-data;
11. record symlink/hardlink semantics in rootfs.metadata.tsv;
12. **do not create Android filesystem links**;
13. write INSTALL_VERIFIED;
14. transactionally promote to INSTALLED_DATA.

## Why links are metadata

Creating guest symlinks directly inside Android app storage creates cleanup/traversal hazards, especially for absolute symlink targets. Alpha 5 therefore records guest link semantics without materializing them.

Before Linux execution, a substrate-aware link strategy must be designed and tested.

## Metadata line format

Each rootfs.metadata.tsv line contains:

~~~text
TYPE MODE UID GID SIZE MTIME BASE64URL_PATH BASE64URL_TARGET
~~~

TYPE:

- F regular file
- D directory
- S symlink metadata
- H hardlink metadata

This metadata is guest information. Alpha 5 does not claim Android applies guest ownership/modes.

## Limits

Current hard ceilings include:

- rootfs archive: 16 GiB;
- extracted data budget: 64 GiB;
- entry count: 2,000,000;
- path bytes: 4096 in the extractor default;
- extended TAR header: 1 MiB default.

A package can set lower limits in its v2 manifest.
