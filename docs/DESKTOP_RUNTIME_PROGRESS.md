# Desktop and runtime progress

Base: `f42d5cdd9d78862ef745367a4db60266e6176237`.

## Implemented

- Taskbar click minimizes the active app or restores/raises the existing window.
- Desktop shortcuts and the desktop context menu scroll within the available height.
- Start menu columns account for menu width and system font scale.
- App content receives its measured viewport; compact window padding is reduced.
- Control Center, Store and Performance have full-page vertical scrolling.
- Performance cards stack in narrow windows; Control Center controls grow with text.
- Explorer toolbar, locations, errors and file rows share a single list. Android Back navigates up a folder. Sidebar scrolls independently.
- Downloads controls and entries share one list; actions wrap.
- System summary moves into the Overview scroll area; tabs scroll horizontally and summary cards adapt to width.
- Terminal uses the runtime process supervisor, handles start failures, retains at most 100 command records, and scrolls to the latest output.
- Guest probes run explicit non-interactive `/bin/sh -c` commands through the existing PRoot invocation gates. The shell probe reports architecture/current directory. The toolchain probe resolves Box64/Wine commands and queries versions, reporting missing tools and nonzero version exits.
- Runtime results can be expanded and copied, with an explicit display cap. Neither missing tools nor successful version queries mark a Windows game as compatible.
- Supervisor captures bounded output without waiting for EOF held open by descendants, checks cancellation while polling, and closes the supervised process/resources on termination.

## Validation and limits

See `validation/alpha22-desktop-runtime-probes.json`.
151 JVM tests pass. New process tests execute local host shell commands. Toolchain protocol tests use deliberately labeled fixtures; these are not Box64/Wine execution evidence. Kotlin compiles; lint has zero errors, 69 warnings and one hint. Eighteen Python script checks pass.

Native APK compilation, Android installation, visual/gesture verification, real PRoot guest execution, Box64/Wine integration and Roblox execution were not performed. The environment lacks Docker for the pinned PRoot workflow and the required Android NDK. No PRoot artifacts or approval records were changed.

PRoot is a userspace prerequisite, not a Windows runtime. Packaging and auditing the pinned PRoot components, then running the guest probe on Android, is still required before the translator/Windows/graphics integration can be validated. The Windows and Roblox readiness gates remain blocked.

## Device checks

1. Open each changed app in portrait and landscape, including with the keyboard visible and enlarged system text.
2. Scroll to the last entry and back in Explorer/Downloads; verify folder Back navigation and file actions.
3. Click an active/background/minimized taskbar app and check the expected minimize/focus/restore behavior without duplicate windows.
4. Open Start/context menus on a short screen and reach all actions.
5. In a rootfs with approved PRoot binaries, execute Shell Linux, then Box64 / Wine. Missing commands must remain missing. Copy the output for diagnosis.
6. Do not equate a completed probe, a version string or a successful unit test with Roblox desktop compatibility.
