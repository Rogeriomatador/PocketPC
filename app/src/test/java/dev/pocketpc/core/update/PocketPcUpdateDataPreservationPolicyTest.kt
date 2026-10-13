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
    fun foregroundReplacementFlushesSessionStateBeforeInstallHandoff() {
        val sourceRoot = locateMainSourceRoot()
        val postUpdate =
            File(
                sourceRoot,
                "dev/pocketpc/core/update/PocketPcPostUpdateReceiver.kt",
            ).readText()
        val foregroundFlow =
            File(
                sourceRoot,
                "dev/pocketpc/core/ui/PocketPcForegroundUpdateFlow.kt",
            ).readText()

        val reopenFunctionStart =
            postUpdate.indexOf(
                "internal fun requestPocketPcReopenAfterUpdate("
            )
        val continuityCall =
            postUpdate.indexOf(
                "PocketPcDataContinuity.prepareForPackageReplacement(",
                startIndex = reopenFunctionStart.coerceAtLeast(0),
            )
        val reopenPreferenceWrite =
            Regex(
                """\.putBoolean\(\s*REOPEN_AFTER_UPDATE_KEY,"""
            ).find(
                postUpdate,
                startIndex = reopenFunctionStart.coerceAtLeast(0),
            )?.range?.first ?: -1

        assertTrue(
            "Foreground update handoff must flush WebView/session state before marking the app for replacement/reopen.",
            reopenFunctionStart >= 0 &&
                continuityCall > reopenFunctionStart &&
                reopenPreferenceWrite > continuityCall,
        )

        val requestInstallFunction =
            foregroundFlow.indexOf(
                "suspend fun requestInstallNow("
            )
        val reopenCall =
            foregroundFlow.indexOf(
                "requestPocketPcReopenAfterUpdate(",
                startIndex = requestInstallFunction.coerceAtLeast(0),
            )
        val installerCall =
            foregroundFlow.indexOf(
                "updater.requestInstall(current)",
                startIndex = requestInstallFunction.coerceAtLeast(0),
            )

        assertTrue(
            "The real foreground install path must prepare data continuity before handing the APK to PackageInstaller.",
            requestInstallFunction >= 0 &&
                reopenCall > requestInstallFunction &&
                installerCall > reopenCall,
        )
    }

    @Test
    fun replacementReceiverRecordsContinuityBeforeMaintenance() {
        val sourceRoot = locateMainSourceRoot()
        val postUpdate =
            File(
                sourceRoot,
                "dev/pocketpc/core/update/PocketPcPostUpdateReceiver.kt",
            ).readText()

        val receiverStart =
            postUpdate.indexOf(
                "class PocketPcPostUpdateReceiver"
            )
        val continuityRecord =
            postUpdate.indexOf(
                "PocketPcDataContinuity.recordPackageReplacement(",
                startIndex = receiverStart.coerceAtLeast(0),
            )
        val maintenance =
            postUpdate.indexOf(
                "AppOwnedStorageLifecycle",
                startIndex = continuityRecord.coerceAtLeast(0),
            )

        assertTrue(
            "Post-update continuity must be recorded before any maintenance cleanup runs.",
            receiverStart >= 0 &&
                continuityRecord > receiverStart &&
                maintenance > continuityRecord,
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
