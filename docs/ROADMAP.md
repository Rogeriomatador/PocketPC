# PocketPC Roadmap

## Alpha 19 — current consolidation target

### Implemented source

- landscape/immersive desktop shell;
- persistent taskbar pins;
- original PocketPC icons;
- searchable/scrollable Start menu and context menu;
- right-click, long-press, hover, pointer cursors and keyboard focus;
- keyboard shortcuts + Android shortcut helper;
- movable/minimizable/maximizable windows;
- resizable freeform PocketPC windows;
- persistent per-app freeform geometry;
- left/right window snapping with keyboard and touch controls;
- System / Light / Dark themes;
- static and animated wallpaper presets;
- custom SAF image wallpapers;
- installed Android app/game launcher;
- Store window with Play Store/web fallback;
- Control Center with Wi-Fi/Bluetooth/Sound/Display/Keyboard/Notifications/
  Battery/Cast settings;
- persistent game compatibility profiles;
- mouse/keyboard/gamepad detection;
- Displays control/diagnostics window;
- event-driven external-display detection;
- Android secondary-display capability detection;
- Android freeform-window capability detection;
- Android FEATURE_PC detection and optional app declaration;
- launch-display and launch-bounds requests with fallback;
- Alpha 19 desktop evidence in the physical runner;
- evidence-bundle verification for the desktop schema;
- dynamic CI version evidence from the build lock;
- CI version regression policy.

### Required next gate

1. Run the complete Windows build against the latest HEAD.
2. Require all Python policies and self-tests to PASS.
3. Require Kotlin compile + unit tests + Android lint + APK assembly to PASS.
4. Install the exact APK on the physical POCO.
5. Require physical desktop landscape evidence.
6. Verify browser/files/terminal/personalization/apps/displays manually.
7. Record mouse/keyboard/gamepad behavior only when the hardware is actually connected.
8. Test Wi-Fi Display on the POCO and observe whether HyperOS exposes a distinct
   DisplayManager display or only mirrors the phone.

No item above is PHYSICAL PASS until the run actually proves it.

## After Alpha 19 physically passes

### Desktop UX
- persist snap/open-workspace state across full app restarts;
- richer keyboard navigation beyond current focus support;
- richer taskbar/system-tray panels;
- notification center;
- volume/media/device quick controls;
- drag-and-drop;
- multiple desktop workspaces;
- better wheel/trackpad gestures.

### External desktop
- determine real Wi-Fi Display behavior on POCO X7;
- phone-as-touchpad mode for an external PocketPC session;
- virtual keyboard mode;
- generic wired-display testing on other Android devices that support video out;
- cooperate with Android system desktop windowing where advertised.

### Games
- build per-package tested compatibility database;
- distinguish UNTESTED / PLAYABLE / OPTIMIZED / INCOMPATIBLE;
- validate mouse, keyboard, gamepad and external-display behavior independently;
- investigate Android-permitted compatibility mapping without injecting into other
  processes or spoofing unsupported Windows identity.

### Runtime
- continue Linux/rootfs work behind its independent evidence gates;
- solve hardlink semantics without pretending Android host hardlinks are available;
- keep PRoot artifact/provenance approval fail-closed.
