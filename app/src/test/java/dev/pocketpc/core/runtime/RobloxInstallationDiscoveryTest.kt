package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RobloxInstallationDiscoveryTest {
    @Test
    fun discoversValidClassicPlayerAndChoosesNewestObservedCandidate() {
        val root =
            Files.createTempDirectory(
                "pocketpc-roblox-discovery-",
            ).toFile()
        try {
            val layout =
                requireNotNull(
                    WindowsPrefixPlanner
                        .plan(root, "default")
                        .layout,
                )
            val versions =
                File(
                    layout.pocketUser,
                    "AppData/Local/Roblox/Versions",
                )
            val older =
                File(versions, "version-aaa")
            val newer =
                File(versions, "version-bbb")
            assertTrue(older.mkdirs())
            assertTrue(newer.mkdirs())

            val olderPlayer =
                File(
                    older,
                    "RobloxPlayerBeta.exe",
                )
            val newerPlayer =
                File(
                    newer,
                    "RobloxPlayerBeta.exe",
                )
            olderPlayer.writeBytes(byteArrayOf(1, 2, 3))
            newerPlayer.writeBytes(byteArrayOf(4, 5, 6, 7))
            assertTrue(olderPlayer.setLastModified(1_000L))
            assertTrue(newerPlayer.setLastModified(2_000L))

            val result =
                RobloxInstallationDiscoveryProbe
                    .discover(layout)

            assertTrue(result.installed)
            assertEquals(2, result.installations.size)
            val selected = requireNotNull(result.selected)
            assertEquals(
                "version-bbb",
                selected.versionDirectoryName,
            )
            assertEquals(4L, selected.bytes)
            assertEquals(
                "C:\\users\\pocket\\AppData\\Local\\Roblox\\Versions\\" +
                    "version-bbb\\RobloxPlayerBeta.exe",
                selected.windowsExecutable,
            )
            assertTrue(result.blockers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ignoresWrongNamesZeroBytePlayersAndNonVersionDirectories() {
        val root =
            Files.createTempDirectory(
                "pocketpc-roblox-invalid-",
            ).toFile()
        try {
            val layout =
                requireNotNull(
                    WindowsPrefixPlanner
                        .plan(root, "default")
                        .layout,
                )
            val versions =
                File(
                    layout.pocketUser,
                    "AppData/Local/Roblox/Versions",
                )
            val emptyVersion =
                File(versions, "version-empty")
            val wrongDirectory =
                File(versions, "current")
            assertTrue(emptyVersion.mkdirs())
            assertTrue(wrongDirectory.mkdirs())
            File(
                emptyVersion,
                "RobloxPlayerBeta.exe",
            ).writeBytes(byteArrayOf())
            File(
                wrongDirectory,
                "RobloxPlayerBeta.exe",
            ).writeBytes(byteArrayOf(1))
            File(
                versions,
                "version-file",
            ).writeText("not a directory")

            val result =
                RobloxInstallationDiscoveryProbe
                    .discover(layout)

            assertFalse(result.installed)
            assertNull(result.selected)
            assertTrue(
                result.blockers.contains(
                    "ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED",
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsCanonicalPathsOutsidePocketUser() {
        val root =
            Files.createTempDirectory(
                "pocketpc-roblox-boundary-",
            ).toFile()
        try {
            val layout =
                requireNotNull(
                    WindowsPrefixPlanner
                        .plan(root, "default")
                        .layout,
                )
            val pocket = layout.pocketUser.canonicalFile
            val outside =
                File(
                    pocket.parentFile,
                    "other-user/AppData/Local/Roblox/Versions",
                ).canonicalFile

            assertTrue(
                RobloxInstallationDiscoveryProbe
                    .isStrictDescendant(
                        pocket,
                        File(pocket, "AppData/Local/Roblox/Versions"),
                    ),
            )
            assertFalse(
                RobloxInstallationDiscoveryProbe
                    .isStrictDescendant(
                        pocket,
                        outside,
                    ),
            )
            assertFalse(
                RobloxInstallationDiscoveryProbe
                    .isStrictDescendant(
                        pocket,
                        pocket,
                    ),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
