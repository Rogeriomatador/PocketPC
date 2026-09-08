#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

CHECKS = {
    "app/src/main/AndroidManifest.xml": (
        "android.permission.REQUEST_INSTALL_PACKAGES", "com.termux.permission.RUN_COMMAND",
        "android.permission.BLUETOOTH_CONNECT", 'android:name="com.termux"',
        'android:name="moe.shizuku.privileged.api"', ".storage.PocketDownloadReceiver",
        "android.intent.action.DOWNLOAD_COMPLETE", 'android:exported="false"',
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDrive.kt": (
        'const val POCKET_DRIVE_LETTER = "P:"', 'const val POCKET_SYSTEM_LETTER = "C:"',
        "PocketDriveDirectory", "PocketDriveMetadata", "newPocketDriveMetadata",
        "validatePocketDriveMetadata", "POCKET_DRIVE_METADATA_FILE", 'DOWNLOADS("Downloads"', 'APPLICATIONS("Apps"', 'GAMES("Games"',
        'BACKUPS("Backups"', "sanitizePocketImportedFileName", "validateStorageName", '"CON"', '"LPT1"',
        "PocketFileClass.PC_INSTALLER", "PocketFileClass.ANDROID_PACKAGE",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt": (
        "ensurePocketDrive", "readPocketDriveMetadata", "createPocketDriveMetadata",
        "KEY_DRIVE_VOLUME_ID", "DRIVE_MUTEX", "systemVolume", "importIntoPocketDrive", "bufferSize = 256 * 1024",
        '".pocketpc-part-"', "renameTo(finalName)", "PocketPcPackageRegistry(context)",
        "requestAndroidPackageInstall", "canRequestPackageInstalls", "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
        "Intent.ACTION_INSTALL_PACKAGE", "Pacote de PC detectado",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDownloadBridge.kt": (
        "class PocketDownloadReceiver", "BroadcastReceiver", "ACTION_DOWNLOAD_COMPLETE", "goAsync()",
        "PocketDownloadImporter", "importReady", "PocketDownloadRegistry", "STATUS_FAILED",
        "STATUS_SUCCESSFUL", "manager.remove(downloadId)", "registry.remove(downloadId)",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketPcPackageRegistry.kt": (
        "PocketPcPackageState", "RUNTIME_REQUIRED", "class PocketPcPackageRegistry",
        "classifyPocketFile(entry.name)", "MAX_RECORDS = 512",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketPcProfileBackup.kt": (
        "PocketDriveDirectory.BACKUPS", "PocketPC-profile-", "P:\\Backups",
        "pocketpc-window-layout-v2", "pocketpc-game-compatibility",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt": (
        "PocketDownloadRegistry", "setDestinationInExternalFilesDir",
        "será importado para P:\\Downloads quando concluir", "PocketDownloadRegistry(context)",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/DownloadsApp.kt": (
        "Destino padrão: P:\\Downloads", "Programas de PC detectados", "PocketPcPackageRegistry",
        "RUNTIME_REQUIRED", "Runtime necessário", "P:\\Downloads está vazio.",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/FilesApp.kt": (
        "PocketDriveMount", "PocketDriveDirectory.DOWNLOADS", "PocketDriveDirectory.APPLICATIONS",
        "PocketDriveDirectory.GAMES", "Trocar PocketDrive", "Desconectar P:",
    ),
    "app/src/main/java/dev/pocketpc/core/research/PocketPcResearchProbe.kt": (
        "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY", "createVirtualDisplay", "MediaCodecList",
        "isHardwareAccelerated", "COLOR_FormatSurface", "surfaceInput", "HardwareBuffer.isSupported",
        "USAGE_VIDEO_ENCODE", "VirtualDeviceManager", "CREATE_VIRTUAL_DEVICE", "ACCESS_COMPUTER_CONTROL",
        "FEATURE_WIFI_DIRECT", "FEATURE_WIFI_AWARE", "choosePreferredRemoteCodec",
        "chooseAdvertisedRemoteStreamProfile", "areSizeAndRateSupported", "supports720p60", "supports1080p60",
        "supports1440p60", "supports4k30", "FEATURE_IntraRefresh", "AdvertisedRemoteStreamProfile",
        "unknownSourceInstallAllowed", "termuxInstalled", "termuxRunCommandPermissionGranted",
        "shizukuInstalled", "bluetoothAdapterAvailable", "bluetoothConnectPermissionGranted",
        "bluetoothHidDeviceApiCandidate", "BluetoothManager", '"com.termux"', '"moe.shizuku.privileged.api"',
    ),
    "app/src/main/java/dev/pocketpc/core/ui/ResearchLabApp.kt": (
        "Laboratório PocketPC", "Executar laboratório", "Virtual Display privado",
        "Encoders para desktop remoto", "Perfil anunciado", "não é benchmark de FPS/latência", "1080p60",
        "Pipeline gráfico sem cópia pela CPU", "Virtual Device / Companion", "Encoder HW + Surface",
        "CREATE_VIRTUAL_DEVICE", "Hipótese: monitor remoto PocketPC", "Bridges locais / shell",
        "Bluetooth HID / controle remoto", "HID Device API", "API CANDIDATE", "Termux RUN_COMMAND",
        "Shizuku", "AGUARDANDO AUTORIZAÇÃO", "NÃO é um desktop remoto validado",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/SystemApp.kt": ('RESEARCH("Pesquisa")', "PcInfoTab.RESEARCH", "ResearchLabApp()"),
    "app/src/test/java/dev/pocketpc/core/storage/PocketDriveTest.kt": (
        "pocketDriveMetadataRoundTripsWithoutLosingIdentity", "rejectsInvalidPocketDriveVolumeIdentity",
        "rejectsFuturePocketDriveSchemaFailClosed", "buildsStablePocketPaths",
        "classifiesWindowsInstallerAsPcPackage", "distinguishesAndroidPackage",
        "userVolumeContainsExpectedDesktopFolders", "sanitizesImportedLeafNamesForPcNamespace",
        "prefixesWindowsReservedDeviceNames", "importedFilenameNeverCarriesParentPath",
    ),
    "app/src/test/java/dev/pocketpc/core/research/PocketPcResearchProbeTest.kt": (
        "prefersHardwareAv1OverOtherCodecs", "surfaceInput = true", "ignoresSoftwareOnlyCodecForRemotePreference",
        "reportsNoneWithoutConfirmedHardwareEncoder", "choosesHighestAdvertisedProfileForPreferredCodec",
        "doesNotBorrowResolutionSupportFromLowerPriorityCodec", "reportsNoAdvertisedProfileWithoutHardwareSurfaceEncoder",
    ),
    "scripts/termux-static-check.sh": (
        "STATIC_SOURCE_VALIDATION_ONLY", "PYTHON_COMPILEALL_OK", "TERMUX_STATIC_POLICY_PASS",
        "test-desktop-mode-policy.py", "test-desktop-enum-coverage.py", "test-ci-version-policy.py",
        "test-update-feed-policy.py", "test-pocketdrive-research-policy.py", "This does not compile Kotlin",
    ),
    "scripts/termux-install-published.sh": (
        "POCKETPC_ON_DEVICE_NO_PUBLISHED_APK", "POCKETPC_APK_DOWNLOAD_REJECTED",
        "POCKETPC_APK_VERIFIED_INSTALLER_REQUESTED", "sha256sum", "published", "apkSha256",
        "termux-open", "does not bypass the package installer or signature checks",
    ),
    "scripts/termux-on-device-preflight.sh": (
        "DIAGNOSTIC_ONLY_NOT_A_BUILD", "TERMUX_FULL_BUILD_CANDIDATE_NOT_EXECUTED",
        "TERMUX_JAVA_UI_CANDIDATE_NATIVE_BLOCKED", "TERMUX_STATIC_TEST_READY", "BLOCKED_NO_MATCHING_SIGNING_KEY",
        "This script does not build, install, sign, or update PocketPC.",
    ),
}

FORBIDDEN = {
    "app/src/main/java/dev/pocketpc/core/ui/PocketPcApp.kt": ("delay(3_000)",),
    "scripts/termux-on-device-preflight.sh": ("assembleDebug", "adb install", "pm install"),
    "scripts/termux-install-published.sh": ("pm install", "adb install", "--ignore-checks", "--bypass"),
    "scripts/termux-static-check.sh": ("assembleDebug", "adb install", "pm install"),
    "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt": ("Download iniciado → P:",),
}

def main() -> int:
    failures: list[str] = []
    total = 0
    for relative, sentinels in CHECKS.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing file: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        for sentinel in sentinels:
            total += 1
            if sentinel not in text:
                failures.append(f"{relative} missing sentinel: {sentinel}")
    for relative, forbidden_values in FORBIDDEN.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing file: {relative}")
            continue
        text = path.read_text(encoding="utf-8")
        for forbidden in forbidden_values:
            total += 1
            if forbidden in text:
                failures.append(f"{relative} contains forbidden regression: {forbidden}")
    if failures:
        print("POCKETDRIVE_RESEARCH_POLICY_FAILED", file=sys.stderr)
        for failure in failures: print(f"- {failure}", file=sys.stderr)
        return 1
    print("POCKETDRIVE_RESEARCH_POLICY_OK")
    print(f"checks={total}")
    print(f"files={len(CHECKS) + len(FORBIDDEN)}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
