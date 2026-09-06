# PocketPC — v0.1.0-alpha1

PocketPC is an experimental Android desktop/runtime project. The name is provisional.

The long-term goal is not to fake a high-end PC, but to turn Android hardware into a desktop-oriented computing environment with a native shell, Linux/Windows compatibility research, accelerated graphics and measurable sustained-performance management.

## Evidence labels

- **IMPLEMENTED**: source code exists in this repository.
- **STATICALLY REVIEWED**: manually reviewed for structure/obvious issues, but not proven by build/device execution.
- **CI VALIDATED**: the exact commit passed the repository's automated build/tests.
- **DEVICE TESTED**: the exact APK was exercised on real Android hardware.
- **PLANNED**: architecture only, not implemented.

See `docs/ENGINEERING_RULES.md` for the full evidence ladder.

## Current status

### IMPLEMENTED
- Full-screen Jetpack Compose desktop shell.
- Desktop icons and start menu.
- Window controller: open, focus, drag, minimize, maximize/restore, close.
- Taskbar.
- App-level performance HUD.
- UI FPS counter using `Choreographer`.
- App process memory sampling.
- Available system RAM sampling.
- Thermal status/headroom sampling when supported by Android.
- Resizeable activity configuration for desktop/connected-display scenarios.
- JVM unit tests for core window-controller behavior.
- GitHub Actions pipeline that tests, lints, builds and uploads a debug APK artifact.

### PLANNED
- Storage Access Framework file explorer.
- PTY terminal.
- Linux ARM rootfs/runtime.
- x86/x64 translation runtime.
- Wine compatibility layer.
- DXVK/VKD3D integration research.
- Vulkan graphics bridge/vGPU abstraction.
- shader/pipeline cache manager.
- frame pacing controller.
- dynamic-resolution/upscaling experimentation.
- thermal/performance governor.

## Toolchain target

- compileSdk 37 (Android 17 API 37)
- targetSdk 37
- minSdk 26
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Compose BOM 2026.08.00
- Java 17

## CI / APK

Every push to `main` or `work/**` and every pull request to `main` runs:

1. JVM unit tests;
2. Android lint;
3. debug APK build;
4. upload of `app-debug.apk` as the `PocketPC-debug-apk` workflow artifact.

A successful workflow is **CI VALIDATED**, not **DEVICE TESTED**.

## Local build

Open the repository in a current Android Studio version with Android SDK 37 installed, or use Gradle 9.6.0 with a configured Android SDK:

```bash
gradle assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Current engineering gate

**Gate V0.1-A:** get the exact repository commit green in CI, then launch its generated APK on a physical Android device.

Device pass criteria:
1. app launches without crash;
2. desktop renders in portrait and landscape;
3. all four prototype windows open;
4. windows move/minimize/maximize/close;
5. FPS counter updates;
6. thermal field reports a valid value or cleanly remains unavailable;
7. resizing/external-display use does not crash the activity.
