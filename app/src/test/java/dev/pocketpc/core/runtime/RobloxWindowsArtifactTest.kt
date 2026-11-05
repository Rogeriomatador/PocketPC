package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxWindowsArtifactTest {
    @Test
    fun recognizesOnlyKnownClassicPlayerNames() {
        val player =
            RobloxWindowsArtifactClassifier
                .classify("RobloxPlayerBeta.exe")
        assertEquals(
            RobloxWindowsArtifactKind.PLAYER_EXECUTABLE,
            player.kind,
        )
        assertEquals(
            RobloxWindowsChannel.CLASSIC_PLAYER,
            player.channel,
        )
        assertTrue(player.recognized)
        assertTrue(player.directlyLaunchableCandidate)
        assertFalse(player.installerCandidate)

        val installer =
            RobloxWindowsArtifactClassifier
                .classify("RobloxPlayerInstaller.exe")
        assertEquals(
            RobloxWindowsArtifactKind.PLAYER_INSTALLER,
            installer.kind,
        )
        assertTrue(installer.recognized)
        assertTrue(installer.installerCandidate)
        assertFalse(installer.directlyLaunchableCandidate)
    }

    @Test
    fun storePackagesAreRecognizedButNeverPromotedToInstallable() {
        listOf(
            "Roblox.msix",
            "Roblox-Windows.msixbundle",
            "roblox.appx",
            "roblox.appxbundle",
        ).forEach { name ->
            val artifact =
                RobloxWindowsArtifactClassifier
                    .classify(name)
            assertEquals(
                name,
                RobloxWindowsArtifactKind.STORE_PACKAGE,
                artifact.kind,
            )
            assertEquals(
                name,
                RobloxWindowsChannel.MICROSOFT_STORE,
                artifact.channel,
            )
            assertTrue(name, artifact.recognized)
            assertFalse(
                name,
                artifact.storePackageInstallSupported,
            )
            assertFalse(
                name,
                artifact.directlyLaunchableCandidate,
            )
        }
    }

    @Test
    fun studioAndUnknownRobloxNamesStayOutOfPlayerLaunchPath() {
        val studio =
            RobloxWindowsArtifactClassifier
                .classify("RobloxStudioBeta.exe")
        assertEquals(
            RobloxWindowsArtifactKind.STUDIO_EXECUTABLE,
            studio.kind,
        )
        assertEquals(
            RobloxWindowsChannel.STUDIO,
            studio.channel,
        )
        assertFalse(studio.directlyLaunchableCandidate)

        val unknown =
            RobloxWindowsArtifactClassifier
                .classify("RobloxHelper.exe")
        assertEquals(
            RobloxWindowsArtifactKind.UNKNOWN_ROBLOX,
            unknown.kind,
        )
        assertFalse(unknown.recognized)
        assertFalse(unknown.directlyLaunchableCandidate)

        val unrelated =
            RobloxWindowsArtifactClassifier
                .classify("setup.exe")
        assertEquals(
            RobloxWindowsArtifactKind.NOT_ROBLOX,
            unrelated.kind,
        )
        assertFalse(unrelated.recognized)
    }
}
