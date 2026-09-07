#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

CHECKS = {
    "app/src/main/java/dev/pocketpc/core/MainActivity.kt": (
        "SCREEN_ORIENTATION_SENSOR_LANDSCAPE",
        "DesktopCommand.CYCLE_WINDOWS",
        "DesktopCommand.OPEN_DESKTOP_CONTEXT",
        "MotionEvent.BUTTON_SECONDARY",
        "onKeyShortcut(",
        "onKeyDown(",
        "onGenericMotionEvent(",
        "addOnUnhandledKeyEventListener",
        "onProvideKeyboardShortcuts(",
        "KeyboardShortcutGroup",
        "DesktopCommand.SNAP_LEFT",
        "DesktopCommand.SNAP_RIGHT",
        "KEYCODE_DPAD_LEFT",
        "KEYCODE_DPAD_RIGHT",
        "BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE",
    ),
    "app/src/main/AndroidManifest.xml": (
        'android:screenOrientation="sensorLandscape"',
        "android.permission.ACCESS_NETWORK_STATE",
        "android.intent.category.LAUNCHER",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/PocketPcApp.kt": (
        "commandFlow: Flow<DesktopCommand>",
        "DesktopIconsV2(",
        "TaskbarV2(",
        "StartMenuV2(",
        "DesktopContextMenu(",
        "DesktopApp.APPS -> InstalledAppsApp(desktopCapabilities)",
        "DesktopApp.DOWNLOADS -> DownloadsApp()",
        "DesktopApp.DISPLAYS -> DisplaysApp(desktopCapabilities)",
        "DesktopApp.PERSONALIZATION -> PersonalizationApp(",
        "DesktopPinStore",
        "customWallpaperUri",
        "DesktopThemeMode.SYSTEM",
        "WindowSnap.LEFT",
        "WindowSnap.RIGHT",
        "pointerHoverIcon(PointerIcon.Crosshair)",
        "widthFraction",
        "heightFraction",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopChrome.kt": (
        "Fixar na barra de tarefas",
        "Mostrar area de trabalho",
        "DesktopSystemTray",
        "AppIconTile",
        "Canvas(",
        "DesktopApp.BROWSER",
        "DesktopApp.FILES",
        "DesktopApp.SYSTEM",
        "desktopSecondaryClick",
        "isSecondaryPressed",
        "collectIsHoveredAsState",
        "pointerHoverIcon(PointerIcon.Hand)",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopAppearance.kt": (
        "WallpaperPreset",
        "DesktopThemeMode",
        "SYSTEM",
        "LIGHT",
        "DARK",
        "customWallpaperUri",
        "ImageDecoder",
        "BitmapFactory",
        "Escolher imagem",
        "AURORA",
        "NEON",
        "animated = true",
        "pocketpc-desktop",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DisplaysApp.kt": (
        "Settings.ACTION_CAST_SETTINGS",
        "DesktopCapabilitySnapshot",
        "preferredExternalDisplayId",
        "Abrir Transmitir / Wi-Fi Display",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/InstalledAppsApp.kt": (
        "queryLaunchableApps",
        "DesktopCapabilitySnapshot",
        "setLaunchDisplayId",
        "setLaunchBounds",
        "Pedir janela livre do Android",
        "ApplicationInfo.CATEGORY_GAME",
        "GameCompatibilityStore",
        "GameDesktopRating.entries",
        "Perfil desktop:",
        "entrada desktop depende do jogo",
        "Monitor externo recusou o launch",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopModels.kt": (
        "enum class WindowSnap",
        "LEFT",
        "RIGHT",
        "val snap: WindowSnap = WindowSnap.NONE",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopController.kt": (
        "onPinnedAppsChanged",
        "pinnedApps.toList()",
        "snapActiveLeft",
        "snapActiveRight",
        "restoreSnap",
        "WindowSnap.NONE",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopPinStore.kt": (
        "pinned_apps",
        "defaultPins",
        "joinToString",
        "return emptyList()",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/GameCompatibilityProfile.kt": (
        "enum class GameDesktopRating",
        "UNTESTED",
        "PLAYABLE",
        "OPTIMIZED",
        "INCOMPATIBLE",
        "mouseConfirmed",
        "keyboardConfirmed",
        "gamepadConfirmed",
        "externalDisplayConfirmed",
        "pocketpc-game-compatibility",
    ),
    "app/src/test/java/dev/pocketpc/core/desktop/GameCompatibilityProfileTest.kt": (
        "newGameStartsUntestedWithoutConfirmedDesktopInput",
        "anyConfirmedInputMarksDesktopInputAsObserved",
        "optimizedRatingDoesNotFabricateInputEvidence",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/DeviceEvidenceCollector.kt": (
        '"schemaVersion", 4',
        '"desktop"',
        '"orientationLandscape"',
        '"freeformWindowManagement"',
        '"externalDisplayCount"',
        '"mouseCount"',
        '"keyboardCount"',
        '"gamepadCount"',
    ),
    "app/src/debug/java/dev/pocketpc/core/DebugEvidenceActivity.kt": (
        '"schemaVersion", 3',
        '"desktop"',
        '"orientationLandscape"',
        '"freeformWindowManagement"',
        '"externalDisplayCount"',
    ),
    "app/src/debug/AndroidManifest.xml": (
        'android:screenOrientation="sensorLandscape"',
        'android:resizeableActivity="true"',
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopCapabilityProbe.kt": (
        "FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS",
        "FEATURE_FREEFORM_WINDOW_MANAGEMENT",
        "freeformWindowManagement",
        "DISPLAY_CATEGORY_PRESENTATION",
        "DisplayManager.DisplayListener",
        "preferredExternalDisplayId",
        "MutableStateFlow",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopPeripheralMonitor.kt": (
        "inputManager.inputDeviceIds",
        "inputManager.getInputDevice(deviceId)",
        "sources.and(source) == source",
        "SOURCE_MOUSE",
        "SOURCE_KEYBOARD",
        "SOURCE_GAMEPAD",
        "desktopInputActive",
    ),
}


def main() -> int:
    failures: list[str] = []

    for relative, sentinels in CHECKS.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing desktop-mode source: {relative}")
            continue

        text = path.read_text(encoding="utf-8-sig")
        if relative.endswith("MainActivity.kt"):
            for forbidden in (
                "override fun dispatchKeyEvent(",
                "override fun dispatchGenericMotionEvent(",
                "super.dispatchKeyEvent(",
                "super.dispatchGenericMotionEvent(",
            ):
                if forbidden in text:
                    failures.append(
                        "MainActivity must use public Activity/View input callbacks; "
                        f"forbidden: {forbidden}"
                    )

        if (
            relative.endswith("DesktopPeripheralMonitor.kt")
            and ".mapNotNull" in text
        ):
            failures.append(
                "DesktopPeripheralMonitor must not mapNotNull over primitive "
                "Android device-id arrays"
            )
        for sentinel in sentinels:
            if sentinel not in text:
                failures.append(f"{relative}: missing sentinel: {sentinel}")

    if failures:
        print("DESKTOP_MODE_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("DESKTOP_MODE_POLICY_OK")
    print(f"files_checked={len(CHECKS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
