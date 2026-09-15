package dev.pocketpc.core.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionPersistencePolicyTest {
    @Test
    fun productionCodeDoesNotGloballyClearWebAuthenticationState() {
        val sourceRoot = locateMainSourceRoot()
        val forbiddenTokens =
            listOf(
                "removeAllCookies(",
                "removeSessionCookies(",
                "WebStorage.getInstance().deleteAllData(",
            )

        val offenders =
            sourceRoot
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
                            "${file.relativeTo(sourceRoot).invariantSeparatorsPath}: $token"
                        }
                }
                .toList()

        assertTrue(
            "PocketPC update/runtime code must preserve WebView authentication state; destructive calls found: ${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun applicationFlushesCookiesBeforeBackgroundProcessMayBeKilled() {
        val sourceRoot = locateMainSourceRoot()
        val application =
            File(
                sourceRoot,
                "dev/pocketpc/core/PocketPcApplication.kt",
            ).readText()
        val continuity =
            File(
                sourceRoot,
                "dev/pocketpc/core/update/PocketPcDataContinuity.kt",
            ).readText()

        assertTrue(
            "PocketPcApplication must route browser auth persistence through the data-continuity coordinator",
            application.contains("PocketPcDataContinuity.flushWebAuthenticationState()"),
        )
        assertTrue(
            "CookieManager state must be flushed to persistent storage",
            continuity.contains("CookieManager.getInstance().flush()"),
        )
        assertTrue(
            "Cookie flush must run when PocketPC leaves the foreground",
            application.contains("TRIM_MEMORY_UI_HIDDEN"),
        )
    }

    @Test
    fun foregroundPackageReplacementFlushesBeforeAndAfterUpdate() {
        val sourceRoot = locateMainSourceRoot()
        val receiver =
            File(
                sourceRoot,
                "dev/pocketpc/core/update/PocketPcPostUpdateReceiver.kt",
            ).readText()

        assertTrue(
            "Foreground update preparation must flush persisted web authentication before package replacement",
            receiver.contains("PocketPcDataContinuity.prepareForPackageReplacement("),
        )
        assertTrue(
            "MY_PACKAGE_REPLACED must record continuity without clearing browser state",
            receiver.contains("PocketPcDataContinuity.recordPackageReplacement("),
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
