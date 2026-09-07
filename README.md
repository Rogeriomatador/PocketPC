# PocketPC — 0.1.0-alpha19

PocketPC is an experimental Android desktop/runtime project with strict evidence labels.

## Alpha 19 — Desktop Mode Foundation

Alpha 19 advances the Android host toward a DeX-like pocket desktop while keeping
runtime claims separate from UI/host capabilities.

Implemented in source:

- sensor-landscape PocketPC host;
- immersive system bars with swipe-to-reveal;
- persistent bottom taskbar with pinned/open app state;
- custom PocketPC app icon tiles;
- Start/launcher menu;
- long-press and secondary-mouse context menu;
- keyboard shortcuts including Alt+Tab, Alt+F4, Meta+E, Meta+B and Ctrl+Alt+T;
- physical mouse, keyboard and gamepad detection;
- Android installed-app/game launcher;
- external-display launch attempt through ActivityOptions.setLaunchDisplayId with safe fallback;
- persistent static and animated wallpaper presets;
- integrated Browser, Files, Terminal, Downloads, Runtimes, System and Performance apps;
- Desktop Mode policy self-test in the Windows build.

Important boundary: launching an Android game from PocketPC does not make the game
become or identify itself as a Windows/PC binary. PocketPC can provide a desktop
environment, external-display launch and desktop peripherals. Game-specific mouse,
keyboard and PC-mode behavior still depends on the game and Android platform support.

## Evidence state

Historical physical evidence from Alpha 17/18 proved APK install, installed APK hash,
MainActivity launch and most host filesystem capabilities. Host hardlink creation is
denied on the tested Android device, so Linux/rootfs hardlink semantics remain blocked.

Alpha 19 source changes are:

- IMPLEMENTED;
- STATICALLY VALIDATED;
- SOFTWARE TEST: NOT_EXECUTED;
- PHYSICAL TEST: NOT_EXECUTED.

## Normal command

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\first-physical-test-windows.ps1
```

A successful run must end with `POCKETPC_FIRST_PHYSICAL_TEST_OK`. PRoot remains
separately gated and Linux execution remains disabled.
