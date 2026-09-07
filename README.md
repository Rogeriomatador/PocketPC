# PocketPC — 0.1.0-alpha19

PocketPC is an experimental Android desktop/runtime project with strict evidence
labels. The Alpha 19 goal is a phone-optimized desktop environment in the same
problem category as DeX, while keeping its own PocketPC identity and respecting
Android platform/security boundaries.

## Alpha 19 — Desktop Mode Foundation

Implemented in source:

### Desktop shell
- sensor-landscape host;
- immersive system bars with swipe-to-reveal;
- bottom taskbar with pinned/open/active app state;
- taskbar pins persisted across restarts, including an intentionally empty pin list;
- original PocketPC Canvas icons;
- Start/launcher menu;
- movable, minimizable and maximizable PocketPC windows;
- resizable freeform PocketPC windows;
- left/right half-screen snap;
- Meta+Left / Meta+Right snap shortcuts;
- Show Desktop and window cycling;
- touch long-press and secondary-mouse context menus;
- exact Compose secondary-click routing on icons/taskbar/desktop;
- hover feedback and pointer cursors;
- Escape menu dismissal;
- Android Keyboard Shortcuts Helper integration.

### Personalization
- System / Light / Dark theme modes, persisted;
- static gradient wallpaper presets;
- efficient animated gradient presets;
- custom user-selected wallpaper images through Storage Access Framework;
- persisted wallpaper selection without broad storage permission.

### Apps and games
- integrated Browser, Files, Terminal, Downloads, Displays, Personalization,
  Runtimes, System and Performance windows;
- launcher for installed Android applications;
- Android game classification using ApplicationInfo.CATEGORY_GAME;
- persistent per-game desktop compatibility profiles;
- game ratings: UNTESTED, PLAYABLE, OPTIMIZED, INCOMPATIBLE;
- mouse, keyboard, gamepad and external-display confirmations stored separately
  from the subjective compatibility rating.

A rating never fabricates input evidence.

### Desktop input
- physical mouse detection;
- physical keyboard detection;
- gamepad/joystick detection;
- global keyboard commands;
- right-click plus touch long-press;
- hover/cursor feedback.

### Displays / Android desktop cooperation
- event-driven DisplayManager monitoring;
- public/presentation display discovery;
- FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS detection;
- FEATURE_FREEFORM_WINDOW_MANAGEMENT detection;
- ActivityOptions.setLaunchDisplayId when a compatible external display exists;
- ActivityOptions.setLaunchBounds when Android advertises freeform window support;
- safe fallback to the current display when an external launch is rejected;
- Displays window with live capability information;
- direct entry to Android Cast / Wi-Fi Display settings.

PocketPC does not claim arbitrary third-party Android apps are embedded inside its
Compose windows. Third-party apps remain Android activities/tasks unless Android
itself provides a supported desktop/freeform path.

## Current physical-device boundary

The physical device used so far is Xiaomi model 24095PCADG / POCO X7 5G.
Historical evidence established:

- ADB authorization: PASS;
- APK install and installed hash verification: PASS on earlier Alpha;
- MainActivity launch: PASS on earlier Alpha;
- relative symlink: PASS;
- absolute symlink: PASS;
- NOFOLLOW cleanup: PASS;
- external symlink target preservation: PASS;
- host hardlink creation: DENIED / AccessDeniedException.

The Android host filesystem gate is therefore separate from Linux/rootfs link
semantics. Linux/rootfs hardlink readiness remains BLOCKED.

Xiaomi documentation for this device indicates USB-C 2.0 without wired video output,
while Wi-Fi Display is supported. PocketPC therefore treats wireless display testing
as the primary external-screen experiment on this phone.

## Alpha 19 physical evidence pipeline

The physical runner now records:

- desktop landscape state;
- logical screen size;
- Android freeform capability advertisement;
- secondary-display activity capability;
- external/presentation display counts;
- mouse/keyboard/gamepad counts;
- host filesystem state;
- Linux link-semantics state;
- native host state;
- exact APK/build/bundle hashes.

A successful final host run must end with:

`POCKETPC_FIRST_PHYSICAL_TEST_OK`

This token does not mean Linux/PRoot is ready.

## Current evidence state

For the latest Alpha 19 source:

- DESIGN: advanced;
- IMPLEMENTED: yes;
- STATICALLY VALIDATED: extensive source/policy inspection;
- SOFTWARE TEST: NOT_EXECUTED after the latest source changes;
- INTEGRATION TEST: NOT_EXECUTED;
- PHYSICAL: NOT_EXECUTED after the latest source changes.

The latest real Windows run before these changes reached Kotlin compilation successfully
and then failed Android Lint on a restricted ComponentActivity dispatch override. That
implementation has since been replaced with public Activity/View input callbacks, but
the current HEAD still requires a fresh real build before any PASS claim.

## One-command Windows test

Use:

```text
D:\Projetos\PocketPC\PocketPC-Test-Windows.bat
```

The build runs repository/toolchain checks, process-capture self-test, policy checks,
unit tests, Android lint, APK assembly, install/evidence verification and physical
desktop-host validation.

PRoot remains separately gated and Linux execution remains disabled until its own
artifact/provenance/runtime gates are satisfied.
