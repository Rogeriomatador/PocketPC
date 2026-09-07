# PocketPC Roadmap

## Alpha 20 — current target

### Implemented in source

#### Desktop UX
- PocketPC-specific visual theme;
- compact taskbar, Start launcher and system tray;
- per-app window sizing/position contracts;
- calculated drag/resize workspace bounds;
- left/right snap safety based on application minimum width;
- v2 persisted window geometry;
- wallpaper-safe desktop labels.

#### Browser
- compact landscape browser chrome;
- tab strip;
- independent per-tab WebView history/state during the PocketPC session;
- true desktop browser User-Agent mode;
- downloads and external-open integration.

#### Explorer
- desktop sidebar/toolbar/table/details layout;
- search;
- SAF directory navigation;
- create directory;
- rename;
- delete;
- safe storage-name validation and JVM tests.

#### Este PC
- PC-style overview;
- hardware tab;
- desktop/display tab;
- diagnostics tab;
- SoC, CPU, RAM, storage, display, battery, network, Android and graphics capability
  information sourced from the device.

#### Apps / games / system utilities
- adaptive installed-app library;
- real Android app icons;
- game classification and persistent tested compatibility profiles;
- internal DownloadManager view;
- compact Control Center;
- Google Play-backed Store workspace;
- external-display capability workspace;
- task-manager-style PocketPC performance telemetry.

### Required Alpha 20 gate

1. Finish static source/policy audit.
2. Run the complete Windows validation against the exact current HEAD.
3. Require all policy/self-tests to PASS.
4. Require Kotlin compile to PASS.
5. Require JVM unit tests to PASS.
6. Require Android lint to PASS.
7. Require APK assembly to PASS.
8. Install and hash-verify the exact Alpha 20 APK on the physical POCO.
9. Validate the redesigned shell manually:
   - Browser tabs/content area;
   - Explorer layout and real SAF operations;
   - Este PC hardware values;
   - Start/taskbar;
   - Apps library;
   - Downloads;
   - Control Center;
   - Store;
   - Displays;
   - Performance.
10. Record mouse/keyboard/gamepad/external-display evidence only when physically
    observed.

Until steps 2–10 actually execute, Alpha 20 SOFTWARE/PHYSICAL remain NOT_EXECUTED.

## Next desktop milestones

### Explorer / productivity
- transactional copy/move/paste;
- multi-select;
- keyboard file operations;
- drag-and-drop;
- archive operations;
- richer file-type previews.

### Window manager
- persist maximized/snap/open-workspace state;
- multiple desktops/workspaces;
- taskbar thumbnails;
- richer window switcher;
- edge/corner resize handles;
- keyboard-first focus traversal.

### System
- notification center;
- volume/media quick controls;
- richer network panel;
- power/session menu;
- phone-as-touchpad/keyboard mode for external desktop use.

### External desktop
- physically characterize Wi-Fi Display behavior on the POCO X7;
- distinguish mirroring from a real secondary DisplayManager display;
- test other Android hardware with wired video output;
- cooperate with Android system freeform/desktop windowing where advertised.

### Games
- continue per-package compatibility evidence;
- separately validate mouse, keyboard, controller and external display;
- investigate Android-permitted input/desktop compatibility approaches without
  injection, unsupported identity spoofing or fabricated Windows support.

### Runtime
- continue Linux/rootfs work behind independent evidence gates;
- solve guest hardlink semantics;
- keep artifact/provenance approval fail-closed;
- no Windows x86/x64 compatibility claim until a real execution substrate exists.
