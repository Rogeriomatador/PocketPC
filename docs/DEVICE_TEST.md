# Device test gate — Alpha 3

Nothing below is passed until observed on a physical Android device using an APK tied to an exact commit.

## A — desktop

- [ ] launches without crash;
- [ ] portrait renders;
- [ ] landscape renders;
- [ ] Files, Terminal, System and Performance open;
- [ ] focus/drag/minimize/maximize/restore/close work;
- [ ] taskbar remains usable on a narrow phone.

## B — storage

- [ ] folder picker opens;
- [ ] selected tree survives app restart;
- [ ] directories can be entered and backed out of;
- [ ] a readable file opens through an Android viewer;
- [ ] revoked/inaccessible permission fails cleanly;
- [ ] disconnect forgets/releases the root.

## C — local shell

- [ ] help, pwd and cd work;
- [ ] echo pocketpc executes through /system/bin/sh;
- [ ] a non-zero exit code is visible;
- [ ] a long command is stopped by timeout;
- [ ] huge output is capped;
- [ ] UI stays responsive.

## D — telemetry

- [ ] UI cadence updates;
- [ ] process RAM/CPU update;
- [ ] total/available RAM are plausible;
- [ ] thermal headroom reports a value when supported or unavailable cleanly;
- [ ] no field claims third-party game FPS or global GPU telemetry.

## E — connected display when supported

- [ ] activity resizes without crash;
- [ ] pointer works;
- [ ] keyboard works;
- [ ] window controls remain reachable.

Record device, Android version, commit SHA, APK hash, result and logs.
