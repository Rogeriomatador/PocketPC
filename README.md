# PocketPC — v0.1.0-alpha2

PocketPC is an experimental Android desktop/runtime project. The name is provisional.

The long-term goal is to turn mobile hardware into a desktop-oriented computing environment with a native shell, Linux/Windows compatibility research, accelerated graphics and measurable sustained-performance management. PocketPC does **not** claim that virtual VRAM or a virtual GPU creates physical compute power.

## Evidence labels

- **DESIGN** — architecture/proposal only.
- **IMPLEMENTED** — source code exists.
- **STATICALLY REVIEWED** — source was inspected, without claiming a successful build.
- **CI VALIDATED** — automated build/tests passed for the exact commit.
- **DEVICE TESTED** — the exact APK was exercised on real Android hardware.
- **BENCHMARKED** — reproducible measurements exist.

See [docs/ENGINEERING_RULES.md](docs/ENGINEERING_RULES.md).

## Alpha 2 — IMPLEMENTED source

### Desktop
- Jetpack Compose full-screen desktop shell.
- Start menu and taskbar.
- Window controller: open, focus, drag, minimize, maximize/restore and close.
- Resizable activity for phone/connected-display layouts.

### Real file access
- Storage Access Framework directory picker.
- Persisted user-granted tree access.
- Directory navigation and back stack.
- File metadata listing.
- Open files through compatible Android apps.
- Explicit disconnect/release of persisted access.

Android remains the authority over what the app can access. PocketPC does not request unrestricted storage access.

### Pocket Terminal
- Executes commands using `/system/bin/sh` as the PocketPC app UID.
- Stateful working directory with `cd`.
- Bounded command output.
- Command timeout/termination.
- Scrollable command history.

This is a real subprocess shell in the **Android app sandbox**, not a Linux rootfs and not a PTY yet. Interactive TTY programs are therefore out of scope for this alpha.

### Telemetry
- UI FPS via Choreographer.
- PocketPC process CPU sampling.
- PocketPC process RAM.
- available system RAM.
- thermal status/headroom when supported.

## PLANNED

- PTY-backed terminal.
- Linux ARM rootfs/runtime.
- runtime/process supervisor.
- Vulkan capability probe and graphics bridge.
- frame-time telemetry.
- shader/pipeline cache research.
- x86/x64 translation research.
- Wine compatibility layer.
- DXVK/VKD3D compatibility research.
- dynamic-resolution/upscaling experiments.
- thermal/performance governor.
- reproducible benchmark harness.

## Toolchain target

- Android 17 / compileSdk 37 / targetSdk 37.
- minSdk 26.
- Android Gradle Plugin 9.4.0.
- Gradle 9.6.0.
- AGP built-in Kotlin + Compose compiler plugin.
- Compose BOM 2026.08.00.
- Activity Compose 1.13.0.
- Lifecycle 2.11.0.
- Java 17 target.

## Validation status

Source presence is **not** build proof.

Current engineering gate:

1. JVM unit tests pass.
2. Android lint passes.
3. debug APK assembles.
4. exact APK launches on a physical device.
5. desktop/window interactions work in portrait and landscape.
6. SAF can select, persist, browse and disconnect a directory.
7. terminal can run `pwd`, `ls`, `id`, `uname -a`, `cd` and timeout a long command.
8. telemetry updates without crashing when thermal headroom is unavailable.
9. resizing/external display does not crash the activity.

Only after steps 1–3 may the commit be labeled **CI VALIDATED**. Step 4+ is **DEVICE TESTED**.
