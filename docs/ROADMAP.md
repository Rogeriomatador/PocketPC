# Roadmap

## 0.1.0-alpha19 — Desktop Mode Foundation

### Implemented source

- landscape/immersive desktop host;
- persistent taskbar with pinned and active apps;
- original PocketPC icon tiles;
- Start menu and desktop context menu;
- long-press plus secondary mouse support;
- global keyboard shortcuts;
- mouse/keyboard/gamepad detection;
- installed Android app/game launcher;
- optional external-display activity launch with fallback;
- persistent static/animated wallpaper presets;
- Desktop Mode build policy self-test.

### Current evidence

Alpha 19 has not yet been built or run on the physical device after these changes.
Do not classify it as SOFTWARE TEST or PHYSICAL PASS yet.

Historical physical evidence:
- ADB install: PASS;
- installed APK hash: PASS;
- MainActivity launch: PASS;
- relative/absolute symlink: PASS;
- hardlink: FAIL / AccessDeniedException;
- NOFOLLOW cleanup: PASS;
- external target preservation: PASS.

### Next milestones

1. build and physically validate Alpha 19;
2. validate landscape/immersive behavior on the POCO;
3. validate mouse right-click, keyboard shortcuts and gamepad detection;
4. validate installed app/game discovery and launch;
5. validate an external monitor when one is available;
6. add custom image wallpaper and richer animated/live wallpapers;
7. add true window resizing/snapping and persistent window geometry;
8. add hover states, wheel scrolling polish and configurable keybindings;
9. investigate game-specific desktop input compatibility without spoofing unsupported PC identity;
10. continue Linux/runtime work behind its separate evidence gates.
