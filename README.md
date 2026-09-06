# PocketPC — 0.1.0-alpha3

PocketPC is an experimental Android desktop/runtime project. The goal is to turn a phone into a usable desktop workstation while keeping Android as the host, then progressively research Linux ARM, Windows compatibility, accelerated graphics and sustainable performance.

PocketPC does **not** claim that virtual VRAM creates physical memory or that a virtual GPU creates compute power the SoC does not have. Performance claims require reproducible measurements.

## Evidence ladder

- **DESIGN** — architecture/proposal only.
- **IMPLEMENTED** — source exists in this repository.
- **STATICALLY VALIDATED** — a named limited static/pure-code check passed; not an Android build.
- **CI VALIDATED** — automated build/tests passed for the exact commit.
- **DEVICE TESTED** — the exact APK was exercised on real Android hardware.
- **BENCHMARKED** — reproducible measurements exist with device/build/workload/methodology recorded.

A higher label must never be inferred from a lower one.

## Alpha 3 — IMPLEMENTED source

- Jetpack Compose desktop shell.
- Start menu, desktop icons and scrollable taskbar.
- Window controller: open, focus, drag, minimize, maximize/restore and close.
- Safe drawing insets and resizeable activity.
- Storage Access Framework explorer:
  - persistent root authorization;
  - directory listing and child navigation;
  - external file opening;
  - explicit disconnect/release flow.
- Pocket Terminal:
  - executes Android /system/bin/sh with the normal app UID;
  - no root claim;
  - help, pwd, cd, clear and logical exit;
  - working-directory state;
  - 8-second command timeout;
  - 64 KiB output cap;
  - command/history UI.
- App-scoped telemetry:
  - PocketPC UI cadence/FPS;
  - average/worst frame interval in the sampling window;
  - process CPU-time estimate;
  - process PSS memory;
  - available/total RAM and low-memory signal;
  - Android thermal status;
  - 10-second thermal headroom when available.
- Advisory performance governor:
  - HOLD;
  - WATCH;
  - REDUCE_LOAD;
  - REDUCE_AGGRESSIVELY.
- System capability view:
  - Android/API;
  - ABI list;
  - logical CPU count;
  - OpenGL ES;
  - /data capacity visible to the app;
  - Vulkan feature records exposed by PackageManager.
- Unit-test sources for desktop-controller, shell parsing, byte formatting and governor thresholds.
- CI workflow for tests, lint, debug assembly and artifact upload.
- Build/device helper scripts.

## STATICALLY VALIDATED scope

A limited local Kotlin compiler smoke check passed for the pure Kotlin shell models/builtins and performance governor. This does **not** validate Android APIs, Compose, Gradle dependency resolution, APK assembly or execution on a phone.

## DESIGN / PLANNED

- PTY-backed terminal.
- Linux ARM rootfs/runtime manager.
- Linux package/bootstrap/process supervision.
- x86/x64 translation research.
- Wine bootstrap.
- DXVK/VKD3D path.
- Pocket graphics bridge / vGPU.
- shader and pipeline-cache policy.
- active frame-pacing controller.
- dynamic-resolution/upscaling experiments.
- governor actions connected to a renderer/runtime we control.
- connected-display-specific UX and phone-as-touchpad mode.

## Toolchain

- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- compileSdk / targetSdk 37
- minSdk 26
- Compose BOM 2026.08.00
- Activity Compose 1.13.0
- Lifecycle 2.11.0
- DocumentFile 1.1.0

The repository currently does not contain a Gradle Wrapper binary. Do not fabricate one. Use trusted Gradle 9.6.0 directly until a verified wrapper is committed.

## Build status

**Not CI validated yet.**

Earlier observed Actions runs failed before their first declared job step. That is classified as **CI RUNNER/ACCOUNT/INFRA UNRESOLVED**, not as a Kotlin/Android compilation failure, because checkout and Gradle never ran.

The Alpha 3 workflow now attempts, in order:

1. checkout;
2. JDK 17;
3. Android SDK API 37 + Build Tools 36.0.0;
4. Gradle 9.6.0;
5. unit tests;
6. Android lint;
7. debug APK assembly;
8. report/APK artifact upload.

## Local verification

Run:

~~~bash
scripts/check.sh
~~~

Or:

~~~bash
gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
~~~

Then install:

~~~bash
scripts/device-smoke.sh
~~~

Expected APK:

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

## Engineering gates

- **A / Build:** tests + lint + assembleDebug pass.
- **B / Desktop:** launch, rotate, window controls, taskbar.
- **C / Storage:** permission persistence, navigation, file open, revoke handling.
- **D / Terminal:** builtins, real local shell command, timeout, truncation, no-root boundary.
- **E / Telemetry:** plausible updating values and clean unsupported states.
- **F / Connected display:** resize/pointer/keyboard where hardware supports it.
- **G / Linux:** rootfs lifecycle + shell proof before graphical Linux.
- **H / Graphics:** capability probe and measured accelerated presentation before any vGPU performance claim.
- **I / Benchmark:** reproducible A/B evidence before optimization claims.

See docs/ for architecture, gates, Linux, graphics, CI, security and benchmark policy.
