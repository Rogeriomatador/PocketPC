# PocketPC — 0.1.0-alpha7

PocketPC is an experimental Android desktop/runtime project aimed at turning a phone into a desktop workstation while preserving strict evidence labels.

## Evidence ladder

- **DESIGN**
- **IMPLEMENTED**
- **STATICALLY VALIDATED**
- **CI VALIDATED**
- **DEVICE TESTED**
- **BENCHMARKED**

A higher label is never inferred from a lower one.

## Alpha 7 — guest filesystem semantics

Alpha 7 adds the missing filesystem layer between safe extraction and a future PRoot launch:

- rootfs metadata parser;
- guest path normalization;
- logical guest symlink resolver;
- hardlink target-chain resolver with cycle detection;
- separate transactional link-preparation phase;
- symlink target preservation;
- actual hardlink creation;
- interrupted link-preparation recovery;
- hardlink verification with Files.isSameFile;
- metadata SHA-256 bound to the LINKS_PREPARED marker;
- NOFOLLOW tree deletion for rootfs cleanup/rollback;
- launch gate resolves the entrypoint using guest semantics rather than Android host symlink semantics;
- link verification is part of the launch assessment;
- UI distinguishes INSTALLED_DATA from LINKS_PREPARED.

Linux execution remains disabled.

## Runtime state machine

~~~text
manifest + archive
       ↓
STAGED_VERIFIED
       ↓
safe TAR/TAR.GZ extraction
       ↓
INSTALLED_DATA
       ↓
validate guest link plan
       ↓
materialize symlink/hardlink
       ↓
LINKS_PREPARED
       ↓
verify metadata + links + entrypoint
       ↓
[EXECUTOR_NOT_IMPLEMENTED]
~~~

## Why link preparation is separate

Extraction stays link-free. This prevents archive writes from following a guest symlink into an unintended host path.

Only after every archive entry has been extracted and validated does PocketPC create links. If link creation fails, only newly created link nodes are rolled back.

Runtime deletion uses java.nio.file walkFileTree without FOLLOW_LINKS.

## STATICALLY VALIDATED in this development session

A local Kotlin/JVM smoke test passed for the Alpha 7 core:

- 1 guest symlink prepared;
- 1 hardlink prepared;
- hardlink verified as the same file/inode;
- /bin/sh logically resolved through bin -> usr/bin to /usr/bin/sh;
- rootfs deletion did not follow an absolute symlink to an external directory;
- the external file remained intact.

This does not validate Android filesystem behavior or PRoot.

## PRoot source baseline

Pinned in third_party/proot/LOCK.json:

- recipe authority: termux/termux-packages@32f2b3a6c7a1f2a6d068e523d6248e6b4a334d68
- PRoot 5.1.107.92 / GPL-2.0
- libandroid-shmem 0.7 / BSD 3-Clause
- libtalloc 2.4.3 / GPL-3.0

No third-party PRoot binary is bundled.

## Version

- versionCode: 7
- versionName: 0.1.0-alpha7
- target/compile SDK: 37
- min SDK: 26
- NDK: 29.0.14206865
- CMake: 3.22.1

## CI status

Both Android CI and PRoot Source Audit currently fail before their first workflow step. Their jobs expose steps=null and logs_url=null.

Classification remains **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**.

Issues:
- #3 — CI runner/infrastructure
- #4 — PRoot substrate provenance/build/device gate

## Next gates

1. hosted runner actually starts;
2. unit tests/lint/APK assembly;
3. source archive independent hash audit;
4. physical Android test of symlink/hardlink preparation and NOFOLLOW deletion;
5. PRoot build/artifact/license audit;
6. package reviewed ARM64 PRoot + loader aliases;
7. non-interactive /bin/sh smoke behind a new explicit executor gate;
8. PTY only after one-shot execution is proven.
