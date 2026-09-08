# PocketPC Roadmap

## Alpha 21 — current target

### PocketDrive storage

- C: private system volume for hot/runtime/cache state;
- P: persisted user-selected PocketDrive;
- standard desktop folder layout;
- Explorer P: shortcuts;
- persistent completed-download import queue;
- streaming/transactional import into P:\Downloads;
- PC installer vs Android package classification;
- Alpha 20 remains the latest physically validated baseline.

### Self-update bootstrap

- stable update feed;
- automatic feed check on PocketPC launch;
- periodic six-hour WorkManager scheduling;
- Este PC > Atualizações;
- DownloadManager APK transport;
- SHA-256 verification;
- package/version verification;
- embedded source-revision verification;
- installed/candidate signing-certificate compatibility check;
- Android unknown-source permission flow;
- PackageInstaller session staging;
- best-effort USER_ACTION_NOT_REQUIRED request;
- automatic verified-install preference;
- fallback to Android confirmation when the platform requires it;
- duplicate installer-session protection;
- fail-closed signed-release publisher workflow;
- fail-closed unpublished Alpha 21 feed;
- signed artifact publication: BLOCKED until compatible long-lived signing secrets exist.

### Implemented in source

#### Desktop UX
- PocketPC-specific visual theme;
- compact taskbar, Start launcher and system tray;
- adaptive phone touch mode with portrait/landscape support;
- compact phone windows use the available workspace instead of unusably small freeform geometry;
- per-app window sizing/position contracts;
- calculated drag/resize workspace bounds;
- left/right snap safety based on application minimum width;
- v2 persisted window geometry;
- wallpaper-safe desktop labels.

#### Browser
- responsive browser chrome for compact phone and desktop windows;
- tab strip;
- independent per-tab WebView history/state during the PocketPC session;
- true desktop browser User-Agent mode;
- downloads and external-open integration.

#### Explorer
- desktop sidebar/toolbar/table/details layout;
- compact phone Explorer layout that collapses sidebar/details/secondary columns;
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

### Required Alpha 21 gate

1. Finish static source/policy audit.
2. Run the complete Windows validation against the exact current HEAD.
3. Require all policy/self-tests to PASS.
4. Require Kotlin compile to PASS.
5. Require JVM unit tests to PASS.
6. Require Android lint to PASS.
7. Require APK assembly to PASS.
8. Install and hash-verify the exact Alpha 21 APK on the physical POCO.
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
10. Validate Este PC > Atualizações with the unpublished bootstrap feed.
11. Record mouse/keyboard/gamepad/external-display evidence only when physically
    observed.

Until steps 2–11 actually execute, Alpha 21 SOFTWARE/PHYSICAL remain NOT_EXECUTED.
Automatic remote delivery remains separately BLOCKED until a stable signed APK is
published.

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
