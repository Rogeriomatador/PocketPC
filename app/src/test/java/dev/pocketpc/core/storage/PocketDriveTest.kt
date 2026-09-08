package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketDriveTest {

    @Test
    fun pocketDriveMetadataRoundTripsWithoutLosingIdentity() {
        val metadata =
            newPocketDriveMetadata(
                volumeId =
                    "123e4567-e89b-12d3-a456-426614174000",
                label = "PocketDrive",
            )

        assertEquals(
            metadata,
            PocketDriveMetadata.decode(metadata.encode()),
        )
    }

    @Test
    fun rejectsInvalidPocketDriveVolumeIdentity() {
        val result =
            runCatching {
                validatePocketDriveMetadata(
                    PocketDriveMetadata(
                        schemaVersion =
                            POCKET_DRIVE_SCHEMA_VERSION,
                        volumeId = "not-a-uuid",
                        label = "PocketDrive",
                    )
                )
            }

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is
                IllegalArgumentException
        )
    }

    @Test
    fun rejectsFuturePocketDriveSchemaFailClosed() {
        val result =
            runCatching {
                validatePocketDriveMetadata(
                    PocketDriveMetadata(
                        schemaVersion =
                            POCKET_DRIVE_SCHEMA_VERSION + 1,
                        volumeId =
                            "123e4567-e89b-12d3-a456-426614174000",
                        label = "PocketDrive",
                    )
                )
            }

        assertTrue(result.isFailure)
    }

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
