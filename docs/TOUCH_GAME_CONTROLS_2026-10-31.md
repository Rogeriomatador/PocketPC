# Held touch controls — 31 October 2026

Continues `improve/alpha22-desktop-continuity`, based on
`0494dd1ad18989100c809bb70d23610fedc0a7cc`, in draft PR #6.

## Behavior

The **Jogo** button in a Windows window title bar opens a compact touch panel.
W/A/S/D, Space and left Shift now send key-down when a finger touches the button
and key-up on release or gesture cancellation. Separate fingers can hold separate
buttons for diagonal movement, movement with Space and movement with Shift.
The desktop viewport reserves space for the panel, as it does for the PC keyboard.
Only one of those panels is shown at a time.

The keyboard bridge now tracks ownership per window and Win32 key. Hardware,
keyboard taps and individual touch gestures share the same router. Releasing one
source does not release a key still held by another source. Held modifiers are
included in outgoing events. Duplicate downs and late ups are ignored; a canceled
gesture cannot release a newer gesture of the same button.

Focus changes, hidden/removed windows, closing or switching panels, Android
pause/stop, loss of Android window focus and component disposal attempt to release
all tracked keys. A failed bridge release remains tracked for a subsequent cleanup
attempt. Delivery cannot be guaranteed if the bridge/process has disconnected.

## Validation

`RuntimeKeyInputRouterTest` covers diagonal movement with Space, both directions of
hardware/touch overlap, duplicate and stale events, canceled gesture generations,
window-scoped cleanup, Shift, tap chords sharing Ctrl, rejected sends, release retry
and hardware repeat ownership. These are host logic tests, not physical multitouch
or Windows compatibility evidence.

Focused validation of the final router, virtual keyboard and legacy pressed-key
state: **17 JVM tests PASS** (13 new router tests and 4 existing tests). Kotlin
2.3.21 and JDK 17 were used with the real protocol/key mapper sources. The three
Python source/protocol/Compose refresh checks listed below also passed.

The final Android Gradle JVM suite passed **587 tests, zero failures, errors or
skips**, including all 13 new router tests. Kotlin/Java compilation and
`assembleDebug` also passed with native rebuilding disabled.

Reproduction with the pinned toolchain and Android SDK:

```sh
gradle --no-daemon -Ppocketpc.skipNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/test-kotlin-source-regressions.py
python3 scripts/test-display-bridge-protocol-lock.py
python3 scripts/test-runtime-vulkan-compose-refresh-policy.py
```

`skipNativeBuild` validates Android/Kotlin packaging without rebuilding the native
runtime. This development APK is not an OTA release and must not replace the
installed signed Home Test app.

## Physical test still required

Once a Windows window is running on a device, open **Jogo**, then:

1. Hold W for several seconds, add D with a second finger, then release each
   independently. Add Space and Shift in separate combinations.
2. Hold W on a hardware keyboard and on the screen. Releasing either source alone
   must keep W down until the remaining source is released.
3. While holding a touch key, switch windows, close the panel, minimize/close the
   target, rotate the device, open the notification shade and background the app.
   Return and check that no key remains down.
4. Reopen the panel rapidly and press the same button again; cancellation from the
   previous gesture must not release the new hold.

Android multitouch, lifecycle behavior, Wine input and application behavior remain
**NOT_EXECUTED physically** for this revision. No runtime evidence or readiness gate
is promoted. The next execution gate remains PRoot/rootfs on Android, then
Box64/Wine/window/input, followed by graphics/presentation and individual games.
Roblox compatibility remains unproven. Wine v51 remains the stable path and v52
experimental; this change does not alter either path.
