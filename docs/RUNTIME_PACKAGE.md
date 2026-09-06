# Runtime package format — Alpha 9

The rootfs package format itself remains schema v2 from the earlier runtime work.

## Package stages

~~~text
SOURCE ARCHIVE
   ↓
STAGED_VERIFIED
   ↓
INSTALLED_DATA
   ↓
LINKS_PREPARED
~~~

These stages describe guest rootfs data only.

They are independent from the PRoot substrate approval chain introduced later.

## Schema v2

A manifest declares:

- id/name/version;
- aarch64 architecture;
- rootfs SHA-256;
- rootfs archive bytes;
- entrypoint;
- license metadata;
- tar or tar.gz;
- extracted-byte limit;
- entry limit.

## Safe extraction

- TAR checksum validation;
- path traversal rejection;
- duplicate/special-entry rejection;
- bounded PAX/GNU headers;
- size/entry/path limits;
- regular files/directories written first;
- guest links recorded in metadata.

## Link preparation

Guest symlinks/hardlinks are materialized only after extraction is complete and validated.

The LINKS_PREPARED marker binds to the metadata SHA-256.

## Important separation

A rootfs reaching LINKS_PREPARED is not executable by itself.

Linux execution also requires the independent substrate supply-chain and runtime-attestation gates documented in ATTESTATION.md and PROOT_SUBSTRATE.md.
