# Linux ARM runtime — Alpha 5

## Implemented foundations

- Runtime Manifest v1/v2.
- ARM64 ABI gate.
- SHA-256 staging.
- transactional rootfs staging.
- safe TAR/TAR.GZ data extraction.
- metadata-only guest links.
- INSTALLED_DATA inventory.
- packaged native Runtime Host source.
- execution-substrate presence probe.
- structured launch blockers.

## Still not implemented

- actual PRoot/loader binaries;
- guest symlink/hardlink execution semantics;
- PRoot argument generation;
- process supervisor wired to PRoot;
- PTY;
- Linux shell execution.

## Architecture

~~~text
APK
├── PocketPC native host
└── future packaged execution substrate

app-private writable data
├── staged/rootfs.archive
└── installed/rootfs-data
    └── rootfs.metadata.tsv
~~~

The install tree intentionally contains no guest symlink nodes in Alpha 5.

## Execution candidate

Research supports a PRoot-style approach where the executable loader/proot component lives in nativeLibraryDir and guest binaries remain data.

This remains a candidate until device-tested on PocketPC's API-37 build.

## L2 shell proof gate

Pass only after evidence shows:

1. exact APK/commit;
2. packaged substrate passes provenance/license checks;
3. INSTALLED_DATA rootfs passes integrity gate;
4. guest link semantics are correct;
5. /bin/sh starts through the selected substrate;
6. basic filesystem/process commands work;
7. runtime home write/read works;
8. stop/restart cleanup is deterministic;
9. no root;
10. no unrestricted storage permission.

Only then may PocketPC label a runtime EXECUTABLE_LINUX.
