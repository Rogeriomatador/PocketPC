package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketDriveTest {
    @Test
    fun buildsStablePocketPaths() {
        assertEquals(
            "P:\\Downloads\\RobloxPlayerInstaller.exe",
            pocketPath(
                PocketDriveDirectory.DOWNLOADS,
                "RobloxPlayerInstaller.exe",
            ),
        )
    }

    @Test
    fun classifiesWindowsInstallerAsPcPackage() {
        assertEquals(
            PocketFileClass.PC_INSTALLER,
            classifyPocketFile("setup.MSIX"),
        )
        assertEquals(
            PocketFileClass.PC_INSTALLER,
            classifyPocketFile("game.exe"),
        )
    }

    @Test
    fun distinguishesAndroidPackage() {
        assertEquals(
            PocketFileClass.ANDROID_PACKAGE,
            classifyPocketFile("roblox.apk"),
        )
    }

    @Test
    fun keepsGenericFilesGeneric() {
        assertEquals(
            PocketFileClass.GENERIC,
            classifyPocketFile("trabalho.docx"),
        )
    }

    @Test
    fun userVolumeContainsExpectedDesktopFolders() {
        assertEquals(
            listOf(
                "Desktop",
                "Documents",
                "Downloads",
                "Apps",
                "Games",
                "Projects",
                "Pictures",
                "Videos",
                "Music",
                "Shared",
                "Backups",
            ),
            PocketDriveDirectory.entries
                .map { it.folderName },
        )
    }

    @Test
    fun sanitizesDownloadedNamesWithoutChangingPackageType() {
        assertEquals(
            "Roblox_PlayerInstaller.exe",
            sanitizePocketImportedFileName(
                "Roblox/PlayerInstaller.exe"
            ),
        )
        assertEquals(
            PocketFileClass.PC_INSTALLER,
            classifyPocketFile(
                sanitizePocketImportedFileName(
                    "Roblox/PlayerInstaller.exe"
                )
            ),
        )
    }

    @Test
    fun sanitizesImportedLeafNamesForPcNamespace() {
        assertEquals(
            "setup_.exe",
            sanitizePocketImportedFileName(
                "folder/setup?.exe",
            ),
        )
    }

    @Test
    fun prefixesWindowsReservedDeviceNames() {
        assertEquals(
            "_CON.txt",
            sanitizePocketImportedFileName(
                "CON.txt",
            ),
        )
        assertEquals(
            "_LPT1",
            sanitizePocketImportedFileName(
                "LPT1",
            ),
        )
    }

    @Test
    fun importedFilenameNeverCarriesParentPath() {
        assertEquals(
            "game.msi",
            sanitizePocketImportedFileName(
                "../downloads/game.msi",
            ),
        )
    }
}
