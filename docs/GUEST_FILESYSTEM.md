# Guest filesystem semantics — Alpha 7

Status: **IMPLEMENTED source / STATICALLY VALIDATED core / NOT DEVICE TESTED**

## Problem

Linux rootfs archives rely heavily on symlinks and hardlinks. Creating those links while an archive is still being extracted creates a host-side path traversal hazard.

PocketPC therefore uses a two-phase model.

## Phase 1 — link-free extraction

SafeTarExtractor writes only:

- regular files;
- directories.

Symlinks and hardlinks are recorded in rootfs.metadata.tsv.

No guest link exists in Android storage during extraction.

## Phase 2 — LINKS_PREPARED

RootfsLinkManager:

1. parses and validates metadata;
2. compares link count against INSTALL_VERIFIED;
3. removes remnants of an interrupted previous link transaction;
4. validates every planned link path;
5. resolves hardlink chains to a regular-file target;
6. validates symlink targets in guest path semantics;
7. hashes rootfs.metadata.tsv;
8. writes LINKS_PREPARING;
9. creates hardlinks;
10. creates symlinks;
11. writes LINKS_PREPARED with metadata hash and counts;
12. removes LINKS_PREPARING;
13. immediately verifies the result.

On failure, created link nodes are removed in reverse order.

## Guest symlink semantics

A symlink target is interpreted as Linux guest data.

Example:

~~~text
bin -> usr/bin
entrypoint: /bin/sh
~~~

RootfsGuestResolver resolves that as:

~~~text
/bin/sh
  ↓
/usr/bin/sh
~~~

It does not use File.canonicalFile to follow the Android-host symlink.

Absolute guest links are also interpreted relative to the guest root for logical resolution.

## Hardlinks

Hardlink targets are archive-root-relative paths.

PocketPC:

- rejects invalid/dot-dot targets;
- resolves hardlink chains;
- rejects cycles;
- requires a regular-file base target;
- materializes with Files.createLink;
- verifies with Files.isSameFile.

## Deletion

Once links exist, File.deleteRecursively is not used for installed rootfs trees.

SafeTreeOps.deleteNoFollow uses Files.walkFileTree with no FOLLOW_LINKS option. Symlink nodes are deleted as nodes; their targets are not traversed.

## Entry point launch gate

RuntimeLaunchPlanner:

- checks link preparation when the rootfs contains links;
- verifies the LINKS_PREPARED marker;
- re-hashes metadata;
- verifies symlink targets/hardlinks;
- logically resolves the manifest entrypoint;
- requires the resolved host node to be a plain regular file;
- still adds EXECUTOR_NOT_IMPLEMENTED.

## Open device questions

Need physical Android evidence for:

- Files.createSymbolicLink in app-private storage;
- Files.createLink hardlinks on the target filesystem;
- absolute symlink behavior under PRoot;
- OEM/kernel differences;
- NOFOLLOW deletion behavior on the actual phone filesystem.
