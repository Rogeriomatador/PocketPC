# PocketPC Architecture — Alpha 19

PocketPC separates the user-facing Android desktop host from the future Linux/runtime
substrate. A desktop-looking UI is never evidence that the runtime layer works.

## 1. Android host desktop

The host is a Compose-based desktop shell with:

- landscape/immersive Activity;
- taskbar, Start menu and original app icons;
- PocketPC internal windows;
- freeform drag/resize, minimize/maximize and left/right snap;
- Browser, Files, Terminal, Downloads, Store, Control Center, Displays,
  Personalization, Runtimes, System and Performance;
- persistent theme, wallpaper, taskbar pins and freeform window geometry;
- input/peripheral monitoring.

Internal PocketPC applications run inside PocketPC Compose windows.

Third-party Android applications are launched as Android activities/tasks. PocketPC
does not use unsupported cross-app embedding to make arbitrary apps appear inside its
own windows.

## 2. Platform-cooperative desktop mode

PocketPC probes public Android capabilities:

- FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
- FEATURE_FREEFORM_WINDOW_MANAGEMENT;
- FEATURE_PC;
- DisplayManager public/presentation displays.

When supported, PocketPC can request:

- ActivityOptions.setLaunchDisplayId;
- ActivityOptions.setLaunchBounds.

These are requests to Android, not guarantees. Rejection/unsupported behavior falls
back safely and does not become a fabricated PASS.

## 3. Desktop input

Input is treated as a first-class desktop capability:

- touch;
- touch long-press;
- mouse primary/secondary interaction;
- hover/pointer cursor;
- visible keyboard focus;
- keyboard shortcuts;
- gamepad/joystick presence.

Public Activity/View callbacks are used rather than restricted ComponentActivity
dispatch overrides.

## 4. Game compatibility evidence

PocketPC classifies launchable packages as apps/games and stores an explicit user-tested
profile for games.

Compatibility rating:
- UNTESTED;
- PLAYABLE;
- OPTIMIZED;
- INCOMPATIBLE.

Evidence booleans are independent:
- mouse confirmed;
- keyboard confirmed;
- gamepad confirmed;
- external display confirmed.

Changing the rating alone never changes those evidence booleans.

## 5. Personalization

Appearance state is persisted in app-private SharedPreferences:

- System / Light / Dark theme;
- static/animated preset wallpaper;
- custom SAF wallpaper URI;
- taskbar pin list.

Custom images are selected with the Android document picker. No broad storage
permission is required. Large images are downscaled during decode.

## 6. Physical evidence layer

Windows flow:

preflight -> strict build -> preflight -> APK install -> installed hash verification ->
debug evidence -> evidence bundle -> chain verification -> physical record ->
MainActivity launch -> final record.

Alpha 19 device evidence schema records desktop state including orientation, logical
size, Android desktop capability advertisements (including FEATURE_PC), connected
display counts and connected input-device counts.

Landscape is a host-desktop gate. Freeform, external displays and peripheral counts are
capabilities/state, not mandatory PASS conditions.

## 7. Filesystem/runtime boundary

Physical evidence on the current Xiaomi showed host hardlink creation denied by Android,
while the safety-critical host filesystem behaviors needed by PocketPC passed.

Therefore:

- Android host filesystem readiness is one gate;
- Linux/rootfs hardlink semantics are another gate.

Linux/PRoot remains BLOCKED until its own link/artifact/provenance/runtime requirements
are implemented and physically validated.
