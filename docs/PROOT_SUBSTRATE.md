# PRoot execution substrate research

Status: **SOURCE METADATA LOCKED / NOT BUNDLED / EXECUTOR DISABLED**

## Locked baseline

The authoritative pin is third_party/proot/LOCK.json.

Termux recipe authority:

- repository: termux/termux-packages
- commit: 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- timestamp: 2026-09-06T09:56:57Z

Components:

- PRoot 5.1.107.92, GPL-2.0
- libandroid-shmem 0.7, BSD 3-Clause
- libtalloc 2.4.3, GPL-3.0

PRoot tag v5.1.107.92 resolves to commit 7266fb3e8516535682f5a9c8f3a7e70f6506eddb.
libandroid-shmem v0.7 resolves to commit 7f0bd7e25dbdd146265aff7c6a890029e374622d.

The source archive hashes are locked from Termux recipes but have not yet been independently recalculated by PocketPC CI because the hosted runner is blocked.

## Upstream loader behavior

The Termux PRoot recipe sets PROOT_UNBUNDLE_LOADER and upstream installs:

- loader
- loader32 when the build has 32-bit loader support

PRoot source also supports runtime overrides:

- PROOT_LOADER
- PROOT_LOADER_32

## PocketPC ARM64 packaging contract — DESIGN

ExecutionSubstrateProbe expects PocketPC packaging aliases:

- libproot.so — PRoot executable alias
- libproot_loader.so — ARM64 upstream loader alias
- libtalloc.so
- libandroid-shmem.so

PocketPC plans to set:

PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so

The first gate is aarch64-only, so loader32 is excluded.

Presence of these files never implies validation.

## Alpha 6 preparation

PocketPC has:

- legacy JNI extraction for real nativeLibraryDir files;
- host-path allowlist;
- structured binds;
- explicit read-only bind blocker;
- minimal environment whitelist;
- PROOT_LOADER planning;
- PRoot argv planner;
- one-shot bounded process supervisor;
- explicit EXECUTOR_NOT_ENABLED blocker;
- third-party source lock and source-audit workflow definition.

## CLI basis

The planner uses structured PRoot options:

- -r / --rootfs
- -b / --bind
- -w / --cwd
- -0 / --root-id

No user input is concatenated into a host shell command.

## Read-only binds

RuntimeBindSpec has a readOnly field, but Alpha 6 has not implemented proven read-only PRoot bind semantics. Any readOnly=true bind therefore adds READ_ONLY_BIND_UNIMPLEMENTED and prevents candidate argv generation.

This avoids silently treating a requested read-only bind as writable.

## Before enabling executor

Required:

1. independent source archive hash audit;
2. exact source tag/commit;
3. documented build recipe/adaptations;
4. produced artifact hashes;
5. GPL-2.0/GPL-3.0/BSD distribution compliance;
6. Android API 37 build;
7. dynamic dependency/linker behavior;
8. physical-device execution test;
9. guest link semantics;
10. process-tree termination behavior;
11. environment/bind review;
12. logs demonstrating no root requirement.
