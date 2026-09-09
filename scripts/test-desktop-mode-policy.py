#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys
import json

ROOT = pathlib.Path(__file__).resolve().parents[1]

CHECKS = {
    "app/src/main/java/dev/pocketpc/core/MainActivity.kt": (
        "enableEdgeToEdge()",
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
        'android:screenOrientation="fullUser"',
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION",
        ".update.PocketPcInstallReceiver",
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
        "DesktopApp.DOWNLOADS ->",
        "DownloadsApp(",
        "onOpenRuntime = { target ->",
        "PocketDownloadImporter.importReady",
        "PocketPcFirstRunStore",
        "PocketPcFirstRunExperience",
        "WallpaperEditorDialog",
        "runtimeTarget",
        "PcApplicationTarget",
        "customWallpaperTransform",
        "BrowserWindowActions",
        "integratedBrowserHeader",
        "kotlinx.coroutines.launch",
        "compactMobile",
        "LocalDesktopLayout.current",
        "WindowInsets.safeDrawing",
        ".imePadding()",
        "taskbarHeightDp",
        "!compactMobile &&",
        "PocketPcUpdateAutoCheck(",
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
    "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt": (
        "data class BrowserTabState",
        "mutableStateListOf",
        "items(session.tabs, key = { it.id })",
        "session.closeTab",
        "session.newTab",
        "Nova aba",
        "Windows NT 10.0; Win64; x64",
        "settings.useWideViewPort",
        "settings.loadWithOverviewMode",
        "compactToolbar",
        "maxWidth < 840.dp",
        "TextButton(",
        "Modifier.size(48.dp)",
        "BrowserWindowActions",
        "WindowControlButton",
        "BrowserAddressField",
        "BasicTextField",
        '"Pesquisar ou digitar endereço"',
        "compactWindowControls",
        'WindowControlButton("×", danger = true)',
        "DownloadManager",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/FilesApp.kt": (
        "ExplorerSidebar",
        "ExplorerToolbar",
        "FileTableHeader",
        "FileTableRow",
        "ExplorerDetailsPane",
        "combinedClickable",
        "PocketDriveDirectory.DOWNLOADS",
        "\"P:  \" + (mount?.label ?: \"PocketDrive\")",
        "mount.volumeId.take(8)",
        "mount.schemaVersion",
        "Trocar PocketDrive",
        "Nova pasta",
        "Renomear",
        "Excluir",
        "BoxWithConstraints",
        "compactExplorer",
        "maxWidth < 680.dp",
        "maxWidth >= 980.dp",
        "PocketDownloadImporter.importReady",
        "delay(2_000)",
        "CompactExplorerLocations",
        "PocketDriveDirectory.entries",
        "horizontalScroll(",
        "onOpenRuntime",
        "PcApplicationTarget",
        '"Ver compatibilidade"',
        '"Corrigir nome"',
        "sanitizePocketImportedFileName",
        '"Pesquisar nesta pasta"',
    ),
    "app/src/main/java/dev/pocketpc/core/ui/WallpaperViewportMath.kt": (
        "WallpaperViewportGeometry",
        "wallpaperViewportGeometry",
        "WallpaperFitMode.CROP",
        "WallpaperFitMode.FIT",
        "overflowXpx",
        "overflowYpx",
        "panByPixels",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/WallpaperEditor.kt": (
        "detectTransformGestures",
        "wallpaperViewportGeometry",
        "panByPixels",
        "ContentScale.FillBounds",
        '"use pinça para ampliar/reduzir"',
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopAppearance.kt": (
        "SOLID_BLACK",
        "SOLID_WHITE",
        "SOLID_GRAPHITE",
        "SOLID_GRAY",
        '"SÓLIDO"',
        "wallpaperViewportGeometry",
        "ContentScale.FillBounds",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/ProotInvocationPlan.kt": (
        "EXECUTION_REQUIRES_USER_APPROVAL",
        '"/home/pocket"',
        "normalizeGuestPath",
        "RuntimeBindPolicy.validate",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/ProotExecutionController.kt": (
        "ProotExecutionController",
        "EXECUTION_REQUIRES_USER_APPROVAL",
        "RuntimeProcessSupervisor",
        "executeOneShot",
        "userApproved",
        "TIMED_OUT",
        "MAX_CAPTURE_BYTES",
    ),
    "app/src/test/java/dev/pocketpc/core/runtime/ProotExecutionControllerTest.kt": (
        "explicitApprovalIsRequiredBeforeProcessStart",
        "structuralBlockerCannotBeOverriddenByApproval",
        "SUBSTRATE_NOT_READY",
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/PcRuntimeExecutionPlan.kt": (
        "PcApplicationTarget",
        "PcRuntimeExecutionPlanner",
        "PcRuntimeExecutionGateState",
        "launchEligible",
        '"Próximo gate: ',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/PcApplicationCompatibility.kt": (
        "ROBLOX_DESKTOP",
        "RUNTIME_BLOCKED",
        "RUNTIME_READY_APP_UNVALIDATED",
        "PcApplicationCompatibilityProbe",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/RuntimeApp.kt": (
        "PcApplicationCompatibilityProbe",
        "PcRuntimeExecutionPlanner",
        '"Alvo do Explorador"',
        '"Execução bloqueada pelos gates"',
        '"Executar probe ARM64?"',
        '"Probe ARM64"',
        "ProotInvocationPlanner.build",
        "ProotExecutionController",
        "RuntimeBindPlanner",
        "bindPlanner",
        ".base(runtime)",
        "allowedHostRoots",
        '"BIND_PLAN_FAILED:"',
        "runCatching",
        "onClearTarget",
    ),
    "app/src/test/java/dev/pocketpc/core/ui/WallpaperViewportMathTest.kt": (
        "portraitPhotoCanPanVerticallyAtCropZoomOne",
        "landscapePhotoCanPanHorizontallyAtCropZoomOne",
        "fitAtZoomOneStaysCenteredWhenThereIsNoOverflow",
        "zoomCreatesAdditionalPanRange",
    ),
    "app/src/test/java/dev/pocketpc/core/runtime/PcRuntimeExecutionPlannerTest.kt": (
        "robloxTargetStopsAtFirstMissingRuntimeGate",
        "readyRuntimeStillRequiresApplicationIntegration",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/PocketPcFirstRun.kt": (
        '"pocketpc-first-run"',
        '"completed"',
        '"Seu espaço de trabalho no celular"',
        '"C: para o sistema. P: para você."',
        '"Entrar no PocketPC"',
        '"Downloads concluídos podem ser "',
        "PocketDrive",
        '"Downloads, Documentos, Apps, Jogos e Projetos."',
    ),
    "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt": (
        "ensurePocketDrive",
        "pocketDirectoryUri",
        "systemVolume",
        "importIntoPocketDrive",
        "createDirectory",
        "rename(",
        "delete(entry",
        "validateStorageName",
        "takePersistableUriPermission",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDrive.kt": (
        "POCKET_DRIVE_LETTER",
        "POCKET_SYSTEM_LETTER",
        "PocketDriveDirectory",
        "PocketFileClass.PC_INSTALLER",
        "PocketFileClass.ANDROID_PACKAGE",
        "BACKUPS",
        "fun pocketPath",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketPcProfileBackup.kt": (
        "PocketDriveDirectory.BACKUPS",
        "PocketPC-profile-",
        "pocketpc-desktop",
        "pocketpc-window-layout-v2",
        "pocketpc-game-compatibility",
        "performance_hud",
        "pinned_apps",
        "restoreLatest",
        "schemaVersion",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDownloadBridge.kt": (
        "PocketDownloadRegistry",
        "PocketDownloadImporter",
        "importReady",
        "importIntoPocketDrive",
        "PocketDriveDirectory.DOWNLOADS",
        "openDownloadedFile",
    ),
    "app/src/test/java/dev/pocketpc/core/storage/PocketDriveTest.kt": (
        "buildsStablePocketPaths",
        "classifiesWindowsInstallerAsPcPackage",
        "distinguishesAndroidPackage",
        "userVolumeContainsExpectedDesktopFolders",
        '"Backups"',
    ),
    "app/src/test/java/dev/pocketpc/core/storage/StorageNameValidationTest.kt": (
        "trimsValidNames",
        "rejectsEmptyName",
        "rejectsPathSeparator",
        "rejectsParentTraversalName",
    ),
    "app/src/main/java/dev/pocketpc/core/system/SystemSnapshot.kt": (
        "socManufacturer",
        "socModel",
        "totalRamBytes",
        "availableRamBytes",
        "displayWidthPx",
        "displayHeightPx",
        "refreshRateHz",
        "batteryPercent",
        "networkTransport",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/SystemApp.kt": (
        "Este PC",
        "PcSummaryCards",
        "PcInfoTab.OVERVIEW",
        "PcInfoTab.HARDWARE",
        "PcInfoTab.DESKTOP",
        "PcInfoTab.UPDATES",
        "UpdateCenterApp()",
        "PcInfoTab.DIAGNOSTICS",
        "C: PocketPC System",
        "P: PocketDrive",
        "Processador",
        "Memória",
        "Armazenamento",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt": (
        "queryDownloads",
        "DownloadManager.Query",
        "STATUS_RUNNING",
        "getUriForDownloadedFile",
        "P:\\\\Downloads",
        "PocketFileClass.PC_INSTALLER",
        "Pacote de PC",
        "PocketDriveDirectory.DOWNLOADS",
        "PcApplicationTarget",
        '"Ver compatibilidade"',
        "onCompatibility",
    ),
    "app/src/main/java/dev/pocketpc/core/update/PocketPcUpdater.kt": (
        "updates/stable.json",
        "checkForUpdate",
        "beginDownload",
        "verifyPendingDownload",
        "SHA-256 do APK não confere.",
        "archiveInfo.packageName",
        "signaturesCompatible",
        "canRequestPackageInstalls",
        "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
        "getUriForDownloadedFile",
        "GET_SIGNING_CERTIFICATES",
        "PackageInstaller.SessionParams",
        "USER_ACTION_NOT_REQUIRED",
        "autoInstallVerifiedEnabled",
        "KEY_INSTALL_ATTEMPT_DOWNLOAD_ID",
        "runCatching<PocketPcInstallResult>",
    ),
    "app/src/main/java/dev/pocketpc/core/update/PocketPcInstallReceiver.kt": (
        "PackageInstaller.STATUS_PENDING_USER_ACTION",
        "Intent.EXTRA_INTENT",
        "PocketPcInstallStatusStore",
        "EXTRA_UPDATE_DOWNLOAD_ID",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/UpdateCenterApp.kt": (
        "Verificar agora",
        "Baixar atualização",
        "Verificar APK",
        "Instalar",
        "Fail-closed",
        "PocketPcUpdateAutoCheck",
        "Instalar automaticamente",
        "attemptAutomaticInstall",
        "SESSION_ALREADY_PENDING",
        "Perfil e migração",
        "Criar backup em P:",
        "Restaurar último",
        "PocketPcProfileBackup",
    ),
    "updates/stable.json": (
        "\"schemaVersion\": 1",
        "\"channel\": \"stable\"",
        "\"published\": false",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopChrome.kt": (
        "import androidx.compose.foundation.clickable",
        "awaitPointerEventScope {",
        "Fixar na barra de tarefas",
        "Mostrar área de trabalho",
        "DesktopSystemTray",
        "TaskbarAppMenu",
        "taskbarMenuTarget",
        "TaskbarAnchoredPopup(",
        "TaskbarPopupPositionProvider",
        "TaskbarSystemMenu(",
        "desktopSecondaryClickAt",
        '"Gerenciador de Tarefas"',
        '"Mostrar no Gerenciador de Tarefas"',
        '"Encaixar à esquerda"',
        '"Encaixar à direita"',
        '"Fechar janela"',
        "targetWindow",
        '"Minimizar"',
        '"Maximizar"',
        '"Fechar janela"',
        "compactMobile",
        "taskbarHeight",
        "taskIconSize",
        "menuWidth",
        '"Seus aplicativos"',
        "PocketPcStartButton",
        "PocketPcStartLogo",
        "StartMenuV2",
        "Mostrar desktop",
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
    "app/src/main/java/dev/pocketpc/core/ui/TaskManagerApp.kt": (
        "fun TaskManagerApp(",
        "TaskManagerSection.APPLICATIONS",
        "TaskManagerSection.PROCESSES",
        "RuntimeProcessRegistry.snapshots()",
        "RuntimeProcessRegistry",
        ".terminate(",
        "force = false",
        "force = true",
        "Process.myPid()",
        '"Finalizar tarefa"',
        '"Forçar encerramento"',
        '"protegido"',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/RuntimeProcessSupervisor.kt": (
        "data class RuntimeProcessSnapshot",
        "object RuntimeProcessRegistry",
        "fun snapshots",
        "fun terminate(",
        ".register(",
        ".unregister(",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/PocketPcTheme.kt": (
        "PocketPcDarkColors",
        "PocketPcLightColors",
        "PocketPcTypography",
        "PocketPcShapes",
        "fun PocketPcTheme",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/PerformanceApp.kt": (
        "PerformanceMeter",
        "Telemetria do host PocketPC",
        "Não representam FPS de jogos externos",
        "PerformanceGovernor.decide",
        "LinearProgressIndicator",
        "PerformanceHud",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DesktopAppearance.kt": (
        "WallpaperPreset",
        "DesktopThemeMode",
        "SYSTEM",
        "LIGHT",
        "DARK",
        "customWallpaperUri",
        "WallpaperFitMode",
        "WallpaperTransform",
        "customWallpaperTransform",
        "custom_wallpaper_zoom",
        "custom_wallpaper_offset_x",
        "custom_wallpaper_offset_y",
        "wallpaperViewportGeometry",
        "graphicsLayer",
        '"Ajustar enquadramento"',
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
    "app/src/main/java/dev/pocketpc/core/ui/WallpaperEditor.kt": (
        '"Ajustar papel de parede"',
        "A prévia usa a proporção atual da tela.",
        "detectTransformGestures",
        "WallpaperFitMode.CROP",
        "WallpaperFitMode.FIT",
        "Slider(",
        "DialogProperties(",
        "usePlatformDefaultWidth = false",
        ".fillMaxWidth(0.94f)",
        '"Centralizar"',
        '"Cancelar"',
        '"Aplicar"',
    ),
    "app/src/test/java/dev/pocketpc/core/ui/WallpaperTransformTest.kt": (
        "sanitizeClampsZoomAndOffsets",
        "defaultTransformIsCenteredCrop",
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
        "openStoreTarget",
        "StoreShortcut",
        "Produtividade",
        "Ferramentas",
        "Abrir Google Play",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DisplaysApp.kt": (
        "Settings.ACTION_CAST_SETTINGS",
        "DesktopCapabilitySnapshot",
        "preferredExternalDisplayId",
        "DisplayCapabilityCard",
        "Monitores e capacidades que o Android realmente expõe",
        "Transmitir / Wi-Fi Display",
        "não marcará suporte externo como PASS",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/InstalledAppsApp.kt": (
        "queryLaunchableApps",
        "DesktopCapabilitySnapshot",
        "DesktopLaunchPolicy.plan",
        "setLaunchDisplayId",
        "setLaunchBounds",
        "LazyVerticalGrid",
        "GridCells.Adaptive",
        "ApplicationInfo.CATEGORY_GAME",
        "GameCompatibilityStore",
        "GameDesktopRating.entries",
        "← Aplicativos",
        "verticalScroll(",
        "rememberScrollState()",
        ".weight(1f)",
        "Perfil desktop:",
        "Monitor externo recusou a abertura",
    ),
    "app/src/main/java/dev/pocketpc/core/desktop/DesktopModels.kt": (
        "enum class WindowSnap",
        "data class DesktopWindowSpec",
        "fun DesktopApp.windowSpec()",
        "defaultWidthFraction",
        "defaultHeightFraction",
        "minWidthDp",
        "minHeightDp",
        "defaultXFraction",
        "defaultYFraction",
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
        "fun sanitized(spec: DesktopWindowSpec)",
        "pocketpc-window-layout-v2",
        "app.windowSpec()",
        "widthFraction",
        "heightFraction",
    ),
    "app/src/test/java/dev/pocketpc/core/desktop/WindowGeometryTest.kt": (
        "validGeometryIsPreservedInsideAppContract",
        "invalidGeometryIsClampedToAppAndDesktopBounds",
        "browserAllowsFullWorkspaceGeometry",
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
    "scripts/build-local-windows.ps1": (
        "scripts\\test-desktop-mode-policy.py",
        "scripts\\test-desktop-enum-coverage.py",
        "scripts\\test-ci-version-policy.py",
        "scripts\\test-update-feed-policy.py",
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
        '"orientationLandscape": False',
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
        "returnToPocketPc",
        "MainActivity::class.java",
    ),
    "app/src/debug/AndroidManifest.xml": (
        'android:screenOrientation="fullUser"',
        'android:resizeableActivity="true"',
    ),
    "scripts/validate-device-windows.ps1": (
        '"Orientation          : {0} (adaptive)"',
        "$logicalSizeUsable",
        '"Logical size usable  : "',
        '"PocketPC nao reportou uma area logica utilizavel durante "',
    ),
    "scripts/verify-device-evidence-bundle.py": (
        '"orientationLandscape"',
        '"adaptive desktop {field} must be boolean"',
        '"adaptive desktop {field} must be "',
        '"a positive integer"',
    ),
    "app/src/main/java/dev/pocketpc/core/runtime/PcRuntimeReadiness.kt": (
        "PcRuntimeStageState",
        "READY",
        "BLOCKED",
        "NOT_IMPLEMENTED",
        "UNKNOWN",
        '"x86-64-translation"',
        '"win32-compat"',
        '"graphics-bridge"',
        '"roblox-compatibility"',
        '"Windows x64 em Android ARM64"',
        "executableReady",
    ),
    "app/src/test/java/dev/pocketpc/core/runtime/PcRuntimeReadinessTest.kt": (
        "windowsExecutionRemainsFailClosedWithoutTranslationAndWin32",
        "missingHostSubstrateAndRootfsStayBlocked",
        "assertFalse(result.executableReady)",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/RuntimeApp.kt": (
        "PcRuntimeReadinessProbe.assess",
        '"Compatibilidade de PC"',
        '"Ver etapas"',
        '"Ocultar etapas"',
        '"Execução de .exe continua bloqueada. "',
        '"O PocketPC não marcará Roblox/Windows "',
    ),
    "scripts/verify-physical-validation-record.py": (
        '"desktopOrientationLandscape must be boolean"',
        '"automation desktop orientationLandscape must be boolean"',
        '"automation desktop {field} must be a positive integer"',
        '"desktopOrientationLandscape"',
        '"orientationLandscape"',
    ),
    "scripts/test-physical-validation-record-verifier.py": (
        '"orientationLandscape": False',
        '"desktopOrientationLandscape": False',
        '"screenWidthDp": 469',
        '"screenHeightDp": 1043',
    ),
    "scripts/first-physical-test-core-windows.ps1": (
        '"Desktop host nao reportou orientacao adaptativa valida."',
        '"Orientation  : {0} (adaptive)"',
        '"LANDSCAPE"',
        '"PORTRAIT"',
    ),
    "scripts/verify-first-physical-test-record.py": (
        '"final desktopOrientationLandscape must be boolean"',
        '"physical record desktopOrientationLandscape must be boolean"',
        '"desktopOrientationLandscape"',
    ),
    "scripts/test-first-physical-test-record-verifier.py": (
        '"desktopOrientationLandscape": False',
        "FIRST_PHYSICAL_TEST_RECORD_SELFTEST_OK",
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
    try:
        feed = json.loads((ROOT / "updates/stable.json").read_text(encoding="utf-8"))
        version = json.loads((ROOT / "toolchains/android-build-lock.json").read_text(encoding="utf-8"))["app"]
        if not feed.get("published"):
            for field in ("versionCode", "versionName"):
                if feed.get(field) != version[field]:
                    failures.append(f"unpublished update feed {field} differs from the build lock")
    except (OSError, ValueError, KeyError) as error:
        failures.append(f"cannot validate bootstrap feed version: {error}")

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
                "SCREEN_ORIENTATION_SENSOR_LANDSCAPE",
                "requestedOrientation =",
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
            taskbar_start = text.find("fun TaskbarV2(")
            taskbar_end = text.find(
                "@Composable\nprivate fun UpdateAttentionChip",
                taskbar_start,
            )
            taskbar_section = (
                text[taskbar_start:taskbar_end]
                if taskbar_start >= 0 and taskbar_end > taskbar_start
                else ""
            )
            if "DropdownMenu(" in taskbar_section:
                failures.append(
                    "taskbar section must not use detached DropdownMenu; "
                    "anchored Popup positioning is required"
                )
            if (
                "import androidx.compose.ui.input.pointer."
                "awaitPointerEventScope" in text
            ):
                failures.append(
                    "DesktopChrome must call PointerInputScope."
                    "awaitPointerEventScope without importing a nonexistent "
                    "top-level symbol"
                )

        if relative.endswith("InstalledAppsApp.kt"):
            if "import androidx.compose.foundation.layout.weight" in text:
                failures.append(
                    "InstalledAppsApp must use RowScope/ColumnScope weight "
                    "without importing the internal layout.weight symbol"
                )

        if relative.endswith("PocketPcProfileBackup.kt"):
            for forbidden in (
                "custom_wallpaper_uri",
                "pocketpc-storage",
                "pocketpc-updater",
                "root-uri",
            ):
                if forbidden in text:
                    failures.append(
                        "Profile backup must not persist Android grants, " +
                        "storage roots, updater state, or custom URI grants: " +
                        forbidden
                    )

        if relative.endswith("AndroidManifest.xml"):
            if 'android:screenOrientation="sensorLandscape"' in text:
                failures.append(
                    "PocketPC manifests must not force landscape; "
                    "adaptive phone/desktop orientation is required"
                )

        if relative.endswith("first-physical-test-core-windows.ps1"):
            for forbidden in (
                "Desktop host nao foi validado em landscape.",
                'Write-Host "Landscape    : PASS"',
            ):
                if forbidden in text:
                    failures.append(
                        "First physical orchestration still contains "
                        f"landscape-only regression: {forbidden}"
                    )

        if relative.endswith("validate-device-windows.ps1"):
            if "if (-not $desktopLandscape)" in text:
                failures.append(
                    "Physical validation must not reject portrait-only evidence "
                    "for the adaptive mobile host"
                )

        if relative.endswith("BrowserApp.kt"):
            if 'label = { Text("Endereço ou pesquisa") }' in text:
                failures.append(
                    "Browser address field must stay compact and label-free"
                )
            if 'Text(text = pageTitle' in text:
                failures.append(
                    "Browser must not waste a full row on page title"
                )

        if relative.endswith("PocketPcApp.kt"):
            if ".padding(16.dp)" in text and "spec.contentPaddingDp" not in text:
                failures.append(
                    "Window content padding must be controlled by app spec"
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
