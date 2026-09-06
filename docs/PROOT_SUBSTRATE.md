# PRoot execution substrate research

Status: **DESIGN / NOT BUNDLED / EXECUTOR DISABLED**

## Researched baseline

Termux package metadata currently identifies:

- PRoot version 5.1.107.89
- GPL-2.0
- dependencies: libandroid-shmem, libtalloc

PocketPC does not redistribute those binaries yet.

## Expected packaged components

ExecutionSubstrateProbe checks for:

- libproot.so
- libproot_loader.so
- libtalloc.so
- libandroid-shmem.so

Presence alone is never sufficient for readiness.

## Alpha 6 preparation

PocketPC now has:

- legacy JNI extraction enabled so future loader files can exist in nativeLibraryDir;
- host-path allowlist;
- structured binds;
- minimal environment whitelist;
- PRoot argv planner;
- one-shot bounded process supervisor;
- explicit EXECUTOR_NOT_ENABLED blocker.

## CLI basis

The planner is based on PRoot's documented structured options:

- -r / --rootfs
- -b / --bind
- -w / --cwd
- -0 / --root-id

No user input is concatenated into a shell command.

## Fail-closed bundling

CI rejects PRoot-related library names until provenance/reproducible-build/license checks are completed.

## Before enabling executor

Required:

1. exact source tag/commit;
2. build recipe;
3. artifact hashes;
4. GPL source/notice compliance;
5. dependency license audit;
6. Android API 37 build;
7. physical-device execution test;
8. link semantics;
9. process-tree termination behavior;
10. environment/bind review;
11. logs demonstrating no host-root requirement.
