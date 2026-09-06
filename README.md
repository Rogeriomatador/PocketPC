# PocketPC — 0.1.0-alpha5

PocketPC is an experimental Android desktop/runtime project. The long-term goal is a phone-hosted desktop workstation with Linux ARM, Windows compatibility research, accelerated graphics and measured sustainable performance.

## Evidence ladder

- **DESIGN** — proposal only.
- **IMPLEMENTED** — source exists in the repository.
- **STATICALLY VALIDATED** — a named limited pure/static check passed.
- **CI VALIDATED** — automated build/tests passed for the exact commit.
- **DEVICE TESTED** — the exact APK was exercised on physical Android hardware.
- **BENCHMARKED** — reproducible measurements exist.

A higher label is never inferred from a lower one.

## Alpha 5 — IMPLEMENTED source

Everything from Alpha 4 plus:

- Runtime Manifest schema v2.
- explicit rootfs archive format: tar or tar.gz.
- explicit extracted-byte and entry-count limits.
- strict TAR extractor with:
  - header checksum validation;
  - PAX extended headers;
  - GNU long name/link headers;
  - absolute-path rejection;
  - dot-dot traversal rejection;
  - duplicate-path rejection;
  - special device/FIFO entry rejection;
  - extracted-byte and entry limits;
  - path/extended-header limits.
- symlink/hardlink entries recorded as metadata only; they are **not materialized** on Android.
- transactional STAGED_VERIFIED -> INSTALLED_DATA conversion.
- rootfs archive SHA-256 re-audit immediately before extraction.
- tar.gz decompression with bounded extracted output.
- installation rollback/recovery.
- ExecutionSubstrateProbe for the packaged native-library directory.
- RuntimeLaunchPlanner with structured blockers.
- CI guard that rejects unreviewed PRoot binaries from the APK.
- unit-test sources for malicious TAR/path/limit cases and schema v2.

## Runtime states

~~~text
IMPORTED
  ↓
STAGED_VERIFIED
  ↓
INSTALLED_DATA
  ↓
[future link semantics + execution substrate]
  ↓
EXECUTABLE_LINUX
~~~

Alpha 5 stops at **INSTALLED_DATA**.

## Android execution boundary

PocketPC targets API 37. Android's W^X policy prevents relying on direct execve of downloaded executable files in writable app data. Executable host/loader code must be packaged through an Android-compliant executable/native-library path; guest rootfs content stays verified writable data.

Research indicates a PRoot-based substrate can use APK-packaged native components while guest binaries remain rootfs data. PocketPC does not bundle those components yet.

## PRoot status

Current research baseline:

- Termux-maintained PRoot package: 5.1.107.89.
- license: GPL-2.0.
- dependencies include libandroid-shmem and libtalloc.

No PRoot binary is currently approved or bundled. CI intentionally fails if it unexpectedly appears in the APK.

## Graphics

A native Vulkan capability probe exists, but there is still no renderer/vGPU claim.

~~~text
G0 capability probe        IMPLEMENTED source
G1 Vulkan renderer         DESIGN
G2 frame pacing            DESIGN
G3 Linux graphics bridge   DESIGN
G4 DXVK/VKD3D              DESIGN
~~~

## Toolchain

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk / targetSdk 37
- minSdk 26
- Android NDK 29.0.14206865
- CMake 3.22.1
- Compose BOM 2026.08.00

Native libraries use legacy extraction packaging so future loader components can exist as real files in nativeLibraryDir.

## CI status

**Not CI validated yet.**

GitHub-hosted runs continue to fail before the first declared workflow step, with steps/logs absent. This remains **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**, not a demonstrated Kotlin/NDK build failure.

See Issue #3.

## Next gates

1. CI runner starts.
2. Alpha 5 builds with tests/lint/NDK.
3. physical-device Native Runtime Host + Vulkan probe test.
4. schema-v2 rootfs staging.
5. safe INSTALLED_DATA extraction.
6. design/implement guest link semantics without unsafe Android symlink traversal.
7. package/audit a Linux execution substrate.
8. first supervised ARM64 Linux shell.
9. accelerated graphics.
10. Windows compatibility and measured performance work.
