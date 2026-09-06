# PocketPC PRoot supply-chain lock

Status: **SOURCE_METADATA_LOCKED / ARTIFACT_AUDIT_REQUIRED / NO BINARIES BUNDLED**

## Authority

Pinned Termux recipe:

- repository: termux/termux-packages
- commit: 32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- timestamp: 2026-09-06T09:56:57Z

## Components

- PRoot 5.1.107.92
- libandroid-shmem 0.7
- libtalloc 2.4.3

The source archive hashes in LOCK.json come from the exact pinned Termux recipes. Independent re-hashing remains a separate gate.

## License evidence

PRoot and libandroid-shmem retain their pinned recipe metadata.

For talloc, PocketPC now records a conflict rather than claiming a final redistribution license:

- Termux recipe metadata says GPL-3.0.
- other current distribution/library metadata commonly identifies the talloc library as LGPL-3.0-or-later.

Therefore LOCK.json marks talloc as:

~~~text
UNRESOLVED_SOURCE_AUDIT_REQUIRED
~~~

The pinned source archive's own licensing files must be inspected before redistribution.

## Artifact contract

ARTIFACT_CONTRACT.json defines the ARM64 quarantine policy.

PocketPC must discover from the exact build:

- talloc SONAME;
- talloc runtime filename;
- PRoot DT_NEEDED;
- dependency closure;
- all produced artifact SHA-256 values.

No packaging filename guess is treated as authoritative.

## Proposed aliases

Only after artifact review may PocketPC consider:

- proot -> libproot.so
- loader -> libproot_loader.so

PROOT_LOADER would then point to the packaged loader alias.

The talloc filename is deliberately unresolved until ELF audit.

## Fail-closed rule

No third-party PRoot binary is currently committed.

Presence of files in nativeLibraryDir will not make prootReady true unless a separate artifact approval gate is explicitly changed after review.
