package dev.pocketpc.core.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcUpdateDataPreservationPolicyTest {
    @Test
    fun updaterUsesSignedInPlacePackageReplacement() {
        val sourceRoot = locateMainSourceRoot()
        val updater =
            File(
                sourceRoot,
                "dev/pocketpc/core/update/PocketPcUpdater.kt",
            ).readText()

        assertTrue(
            "Updater must use a PackageInstaller full-install session so an existing PocketPC package is replaced in place.",
            updater.contains("PackageInstaller.SessionParams") &&
                updater.contains("MODE_FULL_INSTALL"),
        )
        assertTrue(
            "Update session must target the currently installed PocketPC package.",
            updater.contains("setAppPackageName(") &&
                updater.contains("appContext.packageName"),
        )
        assertTrue(
            "Updater must verify candidate and installed signing identities before replacement.",
            updater.contains("signaturesCompatible(") &&
                updater.contains("installedPackageInfo()"),
        )
    }

    @Test
    fun productionUpdateCodeNeverUninstallsPocketPcBeforeInstall() {
        val updateRoot =
            File(
                locateMainSourceRoot(),
                "dev/pocketpc/core/update",
            )
        val forbiddenTokens =
            listOf(
                "packageInstaller.uninstall",
                ".uninstall(",
                "ACTION_UNINSTALL_PACKAGE",
                "Intent.ACTION_DELETE",
                "ACTION_DELETE",
                "pm uninstall",
                "deletePackage(",
            )

        val offenders =
            updateRoot
                .walkTopDown()
                .filter { file ->
                    file.isFile && file.extension == "kt"
                }
                .flatMap { file ->
                    val text = file.readText()
                    forbiddenTokens
                        .asSequence()
                        .filter(text::contains)
                        .map { token ->
                            "${file.relativeTo(updateRoot).invariantSeparatorsPath}: $token"
                        }
                }
                .toList()

        assertFalse(
            "PocketPC update code must never uninstall the app before replacement because that would erase app-private sessions/data: ${offenders.joinToString()}",
            offenders.isNotEmpty(),
        )
    }

    private fun locateMainSourceRoot(): File {
        var cursor: File? =
            File(
                System.getProperty("user.dir"),
            ).canonicalFile

        while (cursor != null) {
            val rootProjectCandidate =
                File(
                    cursor,
                    "app/src/main/java",
                )
            if (rootProjectCandidate.isDirectory) {
                return rootProjectCandidate
            }

            val appModuleCandidate =
                File(
                    cursor,
                    "src/main/java",
                )
            if (appModuleCandidate.isDirectory) {
                return appModuleCandidate
            }

            cursor = cursor.parentFile
        }

        error(
            "Could not locate PocketPC main Kotlin source tree from ${System.getProperty("user.dir")}",
        )
    }
}
