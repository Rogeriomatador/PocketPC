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
        "android.hardware.type.pc",
        'android:required="false"',
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
        "DesktopApp.STORE -> StoreApp()",
        "DesktopApp.CONTROL_CENTER -> ControlCenterApp()",
        "DesktopApp.DISPLAYS -> DisplaysApp(desktopCapabilities)",
        "DesktopApp.PERSONALIZATION -> PersonalizationApp(",
        "DesktopPinStore",
        "DesktopWindowLayoutStore",
        "WindowGeometry",
        "persistGeometry",
        "customWallpaperUri",
        "showPerformanceHud",
        "if (appearance.showPerformanceHud)",
        "DesktopThemeMode.SYSTEM",
        "WindowSnap.LEFT",
        "WindowSnap.RIGHT",
        "pointerHoverIcon(PointerIcon.Crosshair)",
        "widthFraction",
        "heightFraction",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopChrome.kt": (
        "import androidx.compose.foundation.clickable",
        "awaitPointerEventScope {",
        "Fixar na barra de tarefas",
        "Mostrar area de trabalho",
        "DesktopSystemTray",
        "AppIconTile",
        "Canvas(",
        "DesktopApp.BROWSER",
        "DesktopApp.FILES",
        "DesktopApp.SYSTEM",
        "DesktopApp.STORE",
        "DesktopApp.CONTROL_CENTER",
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
        "performance_hud",
        "setPerformanceHud",
        "HUD de desempenho",
        "ImageDecoder",
        "BitmapFactory",
        "Escolher imagem",
        "AURORA",
        "NEON",
        "animated = true",
        "pocketpc-desktop",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/ControlCenterApp.kt": (
        "Build.VERSION.SDK_INT",
        "Build.VERSION_CODES.TIRAMISU",
        "Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS",
        "openNotificationsSetting",
        "Settings.ACTION_WIFI_SETTINGS",
        "Settings.ACTION_BLUETOOTH_SETTINGS",
        "Settings.ACTION_SOUND_SETTINGS",
        "Settings.ACTION_DISPLAY_SETTINGS",
        "Settings.ACTION_INPUT_METHOD_SETTINGS",
        "Settings.ACTION_CAST_SETTINGS",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/StoreApp.kt": (
        "market://search?q=apps",
        "https://play.google.com/store",
        "Abrir Google Play",
        "Explorar jogos",
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
        "DesktopLaunchPolicy.plan",
        "setLaunchDisplayId",
        "setLaunchBounds",
        "Pedir janela livre do Android",
        "ApplicationInfo.CATEGORY_GAME",
        "GameCompatibilityStore",
        "GameDesktopRating.entries",
        "Voltar para aplicativos",
        "verticalScroll(rememberScrollState())",
        ".weight(1f)",
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
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopWindowLayoutStore.kt": (
        "data class WindowGeometry",
        "fun sanitized()",
        "pocketpc-window-layout",
        "widthFraction",
        "heightFraction",
    ),
    "app/src/test/java/dev/pocketpc/core/desktop/WindowGeometryTest.kt": (
        "validGeometryIsPreserved",
        "invalidGeometryIsClampedToDesktopBounds",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopPinStore.kt": (
        "pinned_apps",
        "defaultDesktopPins",
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
    "scripts/test-desktop-enum-coverage.py": (
        "DESKTOP_ENUM_COVERAGE_OK",
        "DesktopApp values missing PocketPcApp window handler",
        "DesktopApp values missing AppIconTile Canvas case",
        "DesktopCommand values missing PocketPcApp handler",
        "defaultDesktopShortcuts",
    ),
    "scripts/test-device-chain-verifier.py": (
        '"schemaVersion": 4',
        '"orientationLandscape": True',
        '"freeformWindowManagement": False',
        '"pcHardwareType": False',
        '"externalDisplayCount": 0',
        '"mouseCount": 1',
        '"keyboardCount": 1',
        '"gamepadCount": 0',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/DeviceEvidenceCollector.kt": (
        '"schemaVersion", 4',
        '"desktop"',
        '"orientationLandscape"',
        '"freeformWindowManagement"',
        '"pcHardwareType"',
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
        '"pcHardwareType"',
        '"externalDisplayCount"',
    ),
    "app/src/debug/AndroidManifest.xml": (
        'android:screenOrientation="sensorLandscape"',
        'android:resizeableActivity="true"',
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopLaunchPolicy.kt": (
        "data class DesktopLaunchPlan",
        "requestedDisplayId",
        "useFreeformBounds",
        "preferredExternalDisplayId",
        "freeformWindowManagement",
    ),
    "app/src/test/java/dev/pocketpc/core/desktop/DesktopLaunchPolicyTest.kt": (
        "noCapabilitiesFallsBackToCurrentDisplayWithoutFreeform",
        "presentationDisplayIsPreferredOverGenericExternalDisplay",
        "userCanKeepExternalDisplayButDisableFreeformBounds",
        "userCanPreferCurrentDisplayWhileStillRequestingFreeform",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopCapabilityProbe.kt": (
        "FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS",
        "FEATURE_FREEFORM_WINDOW_MANAGEMENT",
        "FEATURE_PC",
        "pcHardwareType",
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

        if relative.endswith("ControlCenterApp.kt"):
            if "Settings.ACTION_NOTIFICATION_SETTINGS" in text:
                failures.append(
                    "ControlCenter must not use nonexistent "
                    "Settings.ACTION_NOTIFICATION_SETTINGS"
                )

        if relative.endswith("DesktopChrome.kt"):
            if (
                "import androidx.compose.ui.input.pointer."
                "awaitPointerEventScope" in text
            ):
                failures.append(
                    "DesktopChrome must call PointerInputScope."
                    "awaitPointerEventScope without importing a nonexistent "
                    "top-level symbol"
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
