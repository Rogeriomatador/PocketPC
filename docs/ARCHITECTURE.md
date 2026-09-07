# PocketPC Architecture — Alpha 21

PocketPC is split into two evidence domains:

1. Android desktop host — user-facing desktop shell and Android integration.
2. Runtime substrate — future Linux/rootfs/native execution work.

A feature being visible in the desktop shell is never evidence that the runtime
substrate works.

## 1. Desktop shell

The Android host is a Compose-based landscape/immersive desktop with:

- wallpaper;
- desktop shortcuts;
- Start launcher;
- taskbar/system tray;
- internal PocketPC windows;
- theme/personalization;
- keyboard/mouse/touch/gamepad awareness.

PocketPC internal applications render inside PocketPC windows. Third-party Android
applications are launched as Android activities/tasks; PocketPC does not use unsupported
cross-app embedding and does not claim they are native PocketPC windows.

## 2. Logical storage volumes

PocketPC now distinguishes host Android storage from its PC namespace.

### C: PocketPC System

C: is backed by app-private storage and is reserved for data that benefits from direct,
low-overhead filesystem access:

- runtime state;
- package/runtime metadata;
- hot caches;
- temporary files;
- future shader/translation caches.

### P: PocketDrive

P: is backed by the user-selected persisted SAF tree and holds durable user-facing
content:

- Desktop;
- Documents;
- Downloads;
- Apps;
- Games;
- Projects;
- Pictures;
- Videos;
- Music;
- Shared.

Browser downloads use Android DownloadManager for transport reliability and are
registered in a persistent PocketPC import queue. Completed files are streamed into
P:\Downloads while a PocketPC session is active; unfinished import state is retried on
a later session. PC-installer classification is metadata only and is not execution
evidence.

## 3. Window contract

Every internal DesktopApp owns a DesktopWindowSpec:

- defaultWidthFraction;
- defaultHeightFraction;
- minWidthDp;
- minHeightDp;
- maxWidthFraction;
- maxHeightFraction;
- defaultXFraction;
- defaultYFraction;
- contentPaddingDp.

Freeform drag/resize is clamped against the actual workspace, including current window
dimensions and reserved taskbar space. Saved Alpha 20 geometry uses
`pocketpc-window-layout-v2`.

Half-screen snap is only offered when half of the logical display can satisfy the
application minimum width.

## 4. Integrated applications

### Browser

WebView-based browser with compact desktop chrome, tabs, per-tab WebView state,
DownloadManager and an explicit desktop-browser User-Agent mode.

### Explorer

Storage Access Framework-based file workspace. Current write operations are create
directory, rename and delete. Copy/move are not exposed until their backend exists.

### Este PC

Separates user-facing hardware/system information from developer/runtime diagnostics.
Hardware information is collected from Android APIs/Build/ActivityManager/DisplayManager
and remains the real device hardware identity.

### Apps / games

Launchable Android activities are enumerated through PackageManager. App icons come from
the installed package. Game classification uses ApplicationInfo.CATEGORY_GAME.
Compatibility ratings and observed input/display evidence are persisted independently.

### Downloads

Reads DownloadManager state and exposes progress/status/open/remove inside PocketPC.

### Store

Uses Google Play market intents with web fallback. PocketPC does not claim to operate a
parallel software marketplace.

### Displays

Uses DesktopCapabilitySnapshot / DisplayManager observations. A cast session is not
considered a separate monitor unless Android actually exposes a display.

### Performance

Shows PocketPC process/UI telemetry and advisory governor state. Metrics are not labeled
as third-party game FPS or global GPU utilization.

## 5. Android desktop cooperation

PocketPC probes public Android capabilities including:

- FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
- FEATURE_FREEFORM_WINDOW_MANAGEMENT;
- FEATURE_PC;
- DisplayManager presentation/external displays.

When Android advertises support, app launch can request:

- ActivityOptions.setLaunchDisplayId;
- ActivityOptions.setLaunchBounds.

These are requests, not guarantees. Unsupported/rejected external launch paths fall back
without being counted as PASS evidence.

## 6. Input

Supported host interaction paths include:

- touch;
- touch long-press;
- mouse primary/secondary click;
- pointer hover/cursor;
- keyboard shortcuts/focus;
- gamepad/joystick presence.

Public Activity/View input callbacks are used.

## 7. Persistence

App-private preferences store:

- theme;
- wallpaper;
- custom wallpaper URI;
- taskbar pins;
- Alpha 20 per-app freeform geometry;
- game compatibility profiles.

Browser tab/WebView state is currently session-scoped rather than a persistent browser
database across complete process death.

## 8. Physical evidence pipeline

Windows flow:

preflight -> strict build -> preflight -> APK install -> installed hash verification ->
debug evidence -> evidence bundle -> chain verification -> physical record ->
MainActivity launch -> final record.

Desktop device evidence records orientation, logical size, Android desktop capability
advertisements, display counts and physical input-device counts.

Alpha 21 has not yet completed this pipeline.

## 9. Runtime/filesystem boundary

On the current Xiaomi Android host, physical diagnostics established safe host
filesystem behaviors while direct host hardlink creation was denied.

Therefore:

- Android host filesystem readiness is one gate;
- Linux/rootfs link semantics are a separate gate.

Linux/PRoot remains blocked until its own artifact, provenance, link and execution gates
are implemented and physically validated.
