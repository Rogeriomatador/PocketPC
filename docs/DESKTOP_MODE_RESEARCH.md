# PocketPC Desktop Mode Research — 2026-09

This document records research-backed architecture decisions for the PocketPC
desktop host. It is not evidence that a feature works on the physical device.

## Evidence labels

Research and documentation are DESIGN evidence only.

- IMPLEMENTED means source exists.
- STATICALLY VALIDATED means source structure/policy was inspected.
- SOFTWARE TEST requires an actual build/test run.
- PHYSICAL requires an actual device run.
- NOT_EXECUTED never means PASS.

## Android desktop direction

Android 16 QPR3 made connected-display desktop windowing generally available on
supported Android devices. A connected phone can keep its phone session while a
separate desktop session starts on the external display.

Official sources:

- https://developer.android.com/develop/adaptive-apps/guides/support-connected-displays
- https://developer.android.com/develop/adaptive-apps/guides/support-desktop-windowing
- https://developer.android.com/blog/posts/android-devices-extend-seamlessly-to-connected-displays

### PocketPC decision

PocketPC should use two desktop strategies:

1. Platform-cooperative mode
   - Detect public/presentation displays.
   - Detect support for activities on secondary displays.
   - Prefer Android system desktop windowing where available.
   - Launch apps into the external display through public Android APIs.
   - Let Android own freeform task/window behavior when the platform provides it.

2. PocketPC shell fallback
   - Keep the internal PocketPC desktop/taskbar/window shell.
   - Use it on the phone and on devices without system desktop windowing.
   - Continue to improve its own Browser, Files, Terminal, Downloads, System,
     Personalization and runtime tools.

PocketPC must not claim that arbitrary third-party apps are embedded into PocketPC
windows. Android applications launched through PackageManager remain separate Android
activities/tasks unless the platform provides desktop windowing.

## Connected displays

ActivityOptions.setLaunchDisplayId is public from API 26. Android permits launches
onto public displays and displays where the calling app already has activities.
Private or disallowed displays may reject the launch.

Official source:

- https://developer.android.com/reference/android/app/ActivityOptions

PocketPC behavior:

- monitor state is event-driven through DisplayManager.DisplayListener;
- presentation displays are preferred;
- external launch failures fall back to the current display;
- no external-display success is claimed until physically observed.

## Desktop input

Android desktop guidance expects:

- primary mouse click;
- secondary/right click;
- hover;
- scroll wheel / trackpad scrolling;
- visible focus states;
- keyboard navigation;
- keyboard shortcuts;
- gamepad support where relevant.

Official sources:

- https://developer.android.com/design/ui/desktop/guides/interaction/pointer-interactions
- https://developer.android.com/design/ui/desktop/guides/interaction/keyboard
- https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens

### PocketPC input roadmap

Implemented source foundation:

- mouse/keyboard/gamepad presence detection;
- touch long-press context menus;
- secondary-mouse context command path;
- desktop keyboard commands;
- Android Keyboard Shortcuts Helper publication.

Next:

- hover state on desktop/taskbar items;
- pointer cursors for click/drag/resize;
- wheel-scroll verification on custom surfaces;
- visible keyboard focus styling;
- Escape dismissal everywhere;
- drag-and-drop;
- window edge/corner resizing;
- snapping;
- configurable shortcuts.

## Games

Samsung DeX documentation states that keyboard/mouse play depends on the game having
keyboard/mouse support. Google Play Games on PC similarly distinguishes native input
support from compatibility/input-mapping mechanisms.

Official sources:

- https://www.samsung.com/br/support/apps-services/como-usar-os-recursos-suportados-no-samsung-dex/
- https://developer.android.com/games/playgames/input
- https://developer.android.com/games/playgames/input-sdk
- https://developer.android.com/games/develop/multiplatform/enable-natural-input-on-all-form-factors

### PocketPC decision

PocketPC must not spoof a universal "this phone is Windows/PC" identity.

Instead:

- classify Android apps versus Android games;
- launch games from the desktop;
- prefer an external display when requested and supported;
- detect connected mouse/keyboard/gamepad hardware;
- store per-game compatibility profiles in a future milestone;
- report keyboard/mouse support as UNKNOWN unless it is actually detected or
  user-confirmed;
- investigate touch-to-key mapping only through Android-permitted mechanisms;
- never inject into another process or claim native game support without evidence.

## DeX-inspired features worth implementing

Samsung DeX demonstrates useful product ideas independent of Samsung branding:

- external display workspace;
- keyboard/mouse;
- phone-as-touchpad;
- virtual keyboard;
- taskbar/launcher;
- multiple workspaces;
- windowed multitasking.

Official source:

- https://www.samsung.com/br/apps/samsung-dex/

PocketPC targets the same problem category with its own implementation and visual
identity, not Samsung API compatibility or Samsung branding.

## Near-term milestones

1. Get Alpha 19 through Kotlin + Android lint + unit tests.
2. Physically validate landscape and immersive behavior.
3. Validate launcher discovery and game classification.
4. Validate keyboard shortcut helper.
5. Validate long-press context menu.
6. Validate real mouse right-click.
7. Validate mouse/keyboard/gamepad counters.
8. Validate connected-display capability probe when hardware is available.
9. Add hover/focus/cursor states.
10. Add resizable and snap-capable PocketPC windows.
11. Add custom image wallpapers and efficient live wallpapers.
12. Add phone-as-touchpad mode for an external PocketPC desktop session.
13. Add per-game desktop compatibility profiles.
