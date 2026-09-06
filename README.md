# PocketPC — v0.1.0-alpha2

PocketPC is a proof-of-concept Android desktop/runtime project. The name is provisional.

## Evidence labels

- **IMPLEMENTED**: source code exists in this repository.
- **STATICALLY REVIEWED**: manually reviewed for structure/obvious issues, but not necessarily compiled on Android yet.
- **DEVICE TEST**: requires a real Android device; no such claim is made without evidence.
- **PLANNED**: architecture only, not implemented.

## Current status

### IMPLEMENTED
- Full-screen Compose desktop shell.
- Desktop icons and start menu.
- Window controller: open, focus, drag, minimize, maximize/restore, close.
- Taskbar.
- App-level performance HUD.
- UI FPS counter using Choreographer.
- App process memory sampling.
- Available system RAM sampling.
- Thermal status/headroom sampling when supported.
- Resizeable activity configuration for desktop/connected-display scenarios.
- Storage Access Framework folder picker.
- Persistable folder permission request.
- Real folder listing through DocumentFile.

### PLANNED
- PTY terminal.
- Linux ARM rootfs/runtime.
- x86/x64 translation runtime.
- Wine compatibility layer.
- DXVK/VKD3D integration.
- Vulkan vGPU/translation layer.
- shader/pipeline cache manager.
- frame pacing controller.
- dynamic-resolution/upscaling experimentation.
- thermal/performance governor.

## Toolchain target

- compileSdk 37
- targetSdk 37
- minSdk 26
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0 target
- Compose BOM 2026.08.00
- Java 17 toolchain target

## Build status

Source is present on `main` and a GitHub Actions Android build workflow is configured. The first workflow run failed before any job step started, so that failure is currently classified as **CI INFRA/CONFIG UNRESOLVED**, not as an Android compilation failure. Until CI or a local Android build proves otherwise, do not label the project as build-validated.

## Next engineering gates

### Gate V0.1-A — device shell
1. app launches without crash;
2. desktop renders correctly in portrait and landscape;
3. all four windows open;
4. windows move/minimize/maximize/close;
5. FPS counter updates;
6. thermal field reports a value or cleanly reports unavailable;
7. external-display resize does not crash.

### Gate V0.1-B — files
1. Android folder picker opens;
2. selected folder permission persists;
3. files and directories are listed;
4. inaccessible/empty folders fail cleanly;
5. no unrestricted-storage permission is required.
