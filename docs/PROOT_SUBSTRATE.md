# PRoot execution substrate research

Status: **DESIGN / NOT BUNDLED**

## Why this path exists

Android targetSdk 29+ W^X rules prevent PocketPC from simply downloading an executable into app-private writable storage and calling execve on it.

The execution substrate therefore needs packaged executable/native-library components, while guest rootfs files remain data.

## Current researched baseline

Termux currently maintains a PRoot package with:

- version: 5.1.107.89
- license: GPL-2.0
- dependencies: libandroid-shmem, libtalloc
- unbundled loader support in its package recipe

Upstream/research references:

- https://github.com/termux/proot
- https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh
- https://github.com/termux/termux-packages/wiki/Termux-execution-environment

## Expected packaged component contract

PocketPC's ExecutionSubstrateProbe currently checks for:

- libproot.so
- libproot_loader.so
- libtalloc.so
- libandroid-shmem.so

Their presence does **not** imply validation.

## CI fail-closed rule

Until source provenance, exact hashes, build recipe and GPL obligations are documented, CI rejects an APK containing the PRoot substrate names.

## Before bundling

Required evidence:

1. exact upstream commit/tag;
2. reproducible build recipe where feasible;
3. SHA-256 for source/archive and produced libraries;
4. GPL-2.0 source/notice obligations documented;
5. dependency licenses documented;
6. ABI target confirmed;
7. Android API 37/device behavior tested;
8. security review of arguments/env/mount mappings;
9. device test proving stop/cleanup;
10. no root privilege.

## Launch gate

Even with all libraries present, Alpha 5 still blocks launch with EXECUTOR_NOT_IMPLEMENTED. Rootfs link semantics must also be resolved before execution can be called ready.
