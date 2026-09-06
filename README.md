# PocketPC — 0.1.0-alpha4

PocketPC is an experimental Android desktop/runtime project. The goal is to turn a phone into a usable desktop workstation while keeping Android as the host, then progressively research Linux ARM, Windows compatibility, accelerated graphics and sustainable performance.

PocketPC does **not** claim that virtual VRAM creates physical memory or that a virtual GPU creates compute power the SoC does not have. Performance claims require reproducible measurements.

## Evidence ladder

- **DESIGN** — architecture/proposal only.
- **IMPLEMENTED** — source exists in this repository.
- **STATICALLY VALIDATED** — a named limited static/pure-code check passed; not an Android build.
- **CI VALIDATED** — automated build/tests passed for the exact commit.
- **DEVICE TESTED** — the exact APK was exercised on real Android hardware.
- **BENCHMARKED** — reproducible measurements exist with device/build/workload/methodology recorded.

## Alpha 4 — IMPLEMENTED source

Everything from Alpha 3 plus:

- **Runtimes** desktop application.
- NDK r29 native Runtime Host packaged as libpocketpc_runtime.so.
- Native host probe reporting packaged-host load, ABI, process/kernel information and native library directory.
- Runtime Manifest schema v1.
- ARM64/aarch64 compatibility gate.
- manifest/path validation with traversal-resistant id, version and Linux entrypoint.
- SHA-256 utility with byte limits.
- verified rootfs staging:
  - manifest selected through Android documents;
  - rootfs selected through Android documents;
  - declared byte count enforced while copying;
  - SHA-256 verified before promotion;
  - temporary directory deleted on failure;
  - prior verified staging preserved until replacement is ready;
  - staged rootfs stored as **data**, not executed.
- runtime inventory and removal UI.
- CI definition extended with Android NDK r29 + CMake and packaged-native-host inspection.
- pure Kotlin tests for manifest validation and SHA-256.

## Critical Android execution constraint

Android 10+ apps targeting API 29+ cannot directly execve code placed in the writable app home directory. PocketPC targets API 37, so downloaded rootfs binaries must not be treated as ordinary executable app-data files.

Alpha 4 therefore separates:

~~~text
EXECUTABLE HOST CODE
    packaged in APK / native library path
             +
ROOTFS / PACKAGES
    writable verified data
~~~

This is why Alpha 4 stages and verifies a rootfs but deliberately does **not** claim it can execute that rootfs yet.

## STATICALLY VALIDATED scope

A local kotlinc smoke check passed for the pure Kotlin Runtime Manifest validator and SHA-256 logic, including:

- valid aarch64 manifest accepted;
- version=../../escape rejected;
- /usr/../bin/sh entrypoint rejected;
- known SHA-256 vector for PocketPC matched.

This does **not** validate Android APIs, JNI/NDK linkage, CMake, Compose, Gradle dependency resolution, APK assembly or execution on a phone.

## Existing implemented foundations

- Compose desktop shell, taskbar, start menu and window controller.
- navigable/persistent SAF file explorer.
- local /system/bin/sh terminal under the normal app UID.
- system/ABI/OpenGL/Vulkan capability view.
- app-scoped UI/process/memory/thermal telemetry.
- advisory thermal/memory performance governor.
- unit-test sources and build/device scripts.

## DESIGN / next gates

- prove NDK/JNI host loads in a built APK;
- rootfs extraction as data with safe archive policy;
- embedded runtime-loader/proot/system-linker execution research;
- PTY-backed terminal;
- first ARM64 Linux userspace shell proof;
- accelerated Linux graphics;
- x86/x64 translation + Wine research;
- DXVK/VKD3D;
- Pocket graphics bridge / vGPU;
- active frame pacing/scaling/thermal controls for workloads PocketPC owns.

## Toolchain

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk / targetSdk 37
- minSdk 26
- Android NDK 29.0.14206865 (r29)
- CMake 3.22.1
- Compose BOM 2026.08.00
- Activity Compose 1.13.0
- Lifecycle 2.11.0

The repository currently does not contain a fabricated Gradle Wrapper binary. Use trusted Gradle 9.6.0 directly until a verified wrapper is committed.

## CI status

**Not CI validated yet.**

Observed GitHub Actions runs continue to fail before the first declared job step (steps: null, no job logs). This remains classified as **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**, not as a source build failure.

## Engineering order

1. Build gate.
2. Native host/JNI load gate.
3. Device shell/storage/terminal gate.
4. Runtime staging gate.
5. Safe rootfs data extraction.
6. Linux ARM execution proof.
7. graphics.
8. Windows compatibility.
9. measured performance work.

See docs/ for architecture, runtime package format, Linux plan, graphics, security, CI, device tests and benchmark policy.
