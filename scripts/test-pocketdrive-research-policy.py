#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

CHECKS = {
    "app/src/main/AndroidManifest.xml": (
        "android.permission.REQUEST_INSTALL_PACKAGES",
        ".storage.PocketDownloadReceiver",
        "android.intent.action.DOWNLOAD_COMPLETE",
        'android:exported="false"',
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDrive.kt": (
        'const val POCKET_DRIVE_LETTER = "P:"',
        'const val POCKET_SYSTEM_LETTER = "C:"',
        "PocketDriveDirectory",
        'DOWNLOADS("Downloads"',
        'APPLICATIONS("Apps"',
        'GAMES("Games"',
        "PocketFileClass.PC_INSTALLER",
        "PocketFileClass.ANDROID_PACKAGE",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/StorageRepository.kt": (
        "ensurePocketDrive",
        "systemVolume",
        "importIntoPocketDrive",
        "bufferSize = 256 * 1024",
        "requestAndroidPackageInstall",
        "canRequestPackageInstalls",
        "ACTION_MANAGE_UNKNOWN_APP_SOURCES",
        "Intent.ACTION_INSTALL_PACKAGE",
        "Pacote de PC detectado",
    ),
    "app/src/main/java/dev/pocketpc/core/storage/PocketDownloadBridge.kt": (
        "class PocketDownloadReceiver",
        "BroadcastReceiver",
        "ACTION_DOWNLOAD_COMPLETE",
        "goAsync()",
        "PocketDownloadImporter",
        "importReady",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/BrowserApp.kt": (
        "PocketDownloadRegistry",
        "setDestinationInExternalFilesDir",
        "P:\\Downloads",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/FilesApp.kt": (
        "PocketDriveMount",
        "PocketDriveDirectory.DOWNLOADS",
        "PocketDriveDirectory.APPLICATIONS",
        "PocketDriveDirectory.GAMES",
        "Trocar PocketDrive",
        "Desconectar P:",
    ),
    "app/src/main/java/dev/pocketpc/core/research/PocketPcResearchProbe.kt": (
        "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
        "createVirtualDisplay",
        "MediaCodecList",
        "isHardwareAccelerated",
        "COLOR_FormatSurface",
        "surfaceInput",
        "HardwareBuffer.isSupported",
        "USAGE_VIDEO_ENCODE",
        "VirtualDeviceManager",
        "CREATE_VIRTUAL_DEVICE",
        "ACCESS_COMPUTER_CONTROL",
        "FEATURE_WIFI_DIRECT",
        "FEATURE_WIFI_AWARE",
        "choosePreferredRemoteCodec",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/ResearchLabApp.kt": (
        "Laboratório PocketPC",
        "Executar laboratório",
        "Virtual Display privado",
        "Encoders para desktop remoto",
        "Pipeline gráfico sem cópia pela CPU",
        "Virtual Device / Companion",
        "Encoder HW + Surface",
        "CREATE_VIRTUAL_DEVICE",
        "Hipótese: monitor remoto PocketPC",
        "NÃO é um desktop remoto validado",
    ),
    "app/src/main/java/dev/pocketpc/core/ui/SystemApp.kt": (
        'RESEARCH("Pesquisa")',
        "PcInfoTab.RESEARCH",
        "ResearchLabApp()",
    ),
    "app/src/test/java/dev/pocketpc/core/storage/PocketDriveTest.kt": (
        "buildsStablePocketPaths",
        "classifiesWindowsInstallerAsPcPackage",
        "distinguishesAndroidPackage",
        "userVolumeContainsExpectedDesktopFolders",
    ),
    "app/src/test/java/dev/pocketpc/core/research/PocketPcResearchProbeTest.kt": (
        "prefersHardwareAv1OverOtherCodecs",
        "surfaceInput = true",
        "ignoresSoftwareOnlyCodecForRemotePreference",
        "reportsNoneWithoutConfirmedHardwareEncoder",
    ),
    "scripts/termux-on-device-preflight.sh": (
        "DIAGNOSTIC_ONLY_NOT_A_BUILD",
        "TERMUX_FULL_BUILD_CANDIDATE_NOT_EXECUTED",
        "TERMUX_JAVA_UI_CANDIDATE_NATIVE_BLOCKED",
        "TERMUX_STATIC_TEST_READY",
        "BLOCKED_NO_MATCHING_SIGNING_KEY",
        "This script does not build, install, sign, or update PocketPC.",
    ),
}

FORBIDDEN = {
    "app/src/main/java/dev/pocketpc/core/ui/PocketPcApp.kt": (
        "delay(3_000)",
    ),
    "scripts/termux-on-device-preflight.sh": (
        "assembleDebug",
        "adb install",
        "pm install",
    ),
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
                failures.append(
                    f"{relative} missing sentinel: {sentinel}"
                )

    for relative, forbidden_values in FORBIDDEN.items():
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"missing file: {relative}")
            continue

        text = path.read_text(encoding="utf-8")
        for forbidden in forbidden_values:
            total += 1
            if forbidden in text:
                failures.append(
                    f"{relative} contains forbidden regression: {forbidden}"
                )

    if failures:
        print("POCKETDRIVE_RESEARCH_POLICY_FAILED", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("POCKETDRIVE_RESEARCH_POLICY_OK")
    print(f"checks={total}")
    print(f"files={len(CHECKS) + len(FORBIDDEN)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
