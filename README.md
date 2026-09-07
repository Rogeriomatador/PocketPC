# PocketPC — 0.1.0-alpha20

PocketPC is an experimental Android desktop/runtime project whose goal is to turn a
phone into a practical PC-like workspace while keeping strict evidence boundaries.
It can cooperate with Android desktop/freeform/display APIs, but it does not pretend
that Android applications are Windows processes or that unsupported runtime features
already work.

## Alpha 20 — Desktop UX Overhaul

Alpha 19 established the first physically observed desktop UI on the POCO X7 5G.
Those real-device screenshots showed that the shell worked, but also exposed major UX
problems: oversized browser chrome, generic window sizing, weak file-management UX,
prototype-looking Material defaults and too much diagnostic information in user-facing
screens.

Alpha 20 is a deliberate redesign around those findings.

### Desktop shell

Implemented in source:

- sensor-landscape immersive host;
- persistent bottom taskbar with pinned/open/active application state;
- custom PocketPC Start logo, launcher and system tray;
- wallpaper-safe icon labels;
- PocketPC-specific dark/light visual system instead of default Material colors;
- movable, minimizable, maximizable and resizable internal windows;
- left/right snap with automatic refusal of unusable half-width layouts;
- per-app window contracts:
  - default width/height;
  - minimum size;
  - maximum size;
  - initial position;
  - content padding;
- position-aware resize and drag bounds;
- layout persistence using the Alpha 20 v2 geometry store;
- right-click, touch long-press, hover, pointer cursors and keyboard shortcuts.

### Browser

The integrated browser was rebuilt for landscape efficiency:

- compact tab strip;
- compact single navigation/address toolbar;
- page content consumes the remaining workspace instead of being pushed below several
  control rows;
- multiple browser tabs;
- independent WebView state/history stored per tab during the PocketPC session;
- desktop mode uses a Windows desktop Chrome-style User-Agent;
- Google/search navigation;
- back/forward/reload/home;
- Android DownloadManager integration;
- open current page externally when needed.

Desktop User-Agent mode only affects websites loaded inside the PocketPC browser. It
does not claim the device or external Android applications are Windows.

### Explorer

The file manager is now a desktop-style workspace:

- sidebar;
- breadcrumb/path toolbar;
- search;
- tabular file listing;
- type, size and modification metadata;
- selection and double-click/open;
- responsive details pane;
- create directory;
- rename;
- delete;
- persistent Storage Access Framework root.

Copy/move/paste are intentionally not exposed yet because their transactional backend
has not been implemented.

### Este PC

The former user-facing System view is now **Este PC** with four tabs:

- Visão geral;
- Hardware;
- Desktop;
- Diagnóstico.

The PC-style hardware view is backed by real Android/device data when available:

- SoC manufacturer/model;
- CPU logical core count;
- RAM total/available;
- internal storage total/free;
- screen resolution/density/refresh rate;
- battery/temperature;
- active network transport;
- Android/API/security patch;
- kernel;
- ABIs;
- OpenGL ES and Vulkan feature advertisement.

These values describe the phone hardware used by PocketPC. They are not fabricated
desktop CPU/GPU identities.

### Apps, games, downloads and controls

- installed Android application/game launcher;
- adaptive app library grid;
- real installed-app icons with fallback;
- Android CATEGORY_GAME classification;
- persistent game compatibility profiles;
- separate observed mouse/keyboard/gamepad/external-display evidence;
- internal Downloads view using DownloadManager query/status/progress/open/remove;
- compact Control Center;
- Store workspace backed by Google Play intents/web fallback, not a fake PocketPC
  marketplace;
- Displays workspace that only reports external displays Android actually exposes;
- task-manager-style Performance view for PocketPC UI/process telemetry.

Performance telemetry is explicitly scoped to PocketPC. It is not third-party game FPS
and it is not global GPU utilization.

## Evidence state

### Alpha 19

Real physical/manual evidence exists that the redesigned desktop foundation launched on
the POCO and rendered Browser, Control Center, Files and desktop/taskbar UI. Earlier
physical evidence also established exact APK install/hash verification and the Android
host filesystem state.

That does not automatically validate every Alpha 19 feature.

### Alpha 20 current HEAD

- DESIGN: advanced;
- IMPLEMENTED: yes;
- STATICALLY VALIDATED: ongoing source/policy audit;
- SOFTWARE TEST: NOT_EXECUTED;
- INTEGRATION TEST: NOT_EXECUTED;
- PHYSICAL: NOT_EXECUTED;
- Linux/PRoot execution: BLOCKED by its independent runtime/artifact/link gates.

The physical host filesystem evidence remains:

- relative symlink: PASS;
- absolute symlink: PASS;
- NOFOLLOW cleanup: PASS;
- external symlink target preservation: PASS;
- host hardlink: DENIED on the current Xiaomi Android environment.

Android host filesystem readiness and future Linux/rootfs hardlink semantics remain
separate gates.

## One-command Windows validation

After the Alpha 20 source audit is complete:

```text
D:\Projetos\PocketPC\PocketPC-Test-Windows.bat
```

The command pulls the repository, runs policy/self-tests, performs the strict Android
build, installs the exact APK when build succeeds and continues through the physical
validation chain.

No PASS is claimed until that command actually proves it.
