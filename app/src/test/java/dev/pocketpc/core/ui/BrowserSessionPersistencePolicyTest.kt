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

        assertTrue(
            "PocketPcApplication must flush CookieManager state to persistent storage",
            application.contains("CookieManager.getInstance().flush()"),
        )
        assertTrue(
            "Cookie flush must run when PocketPC leaves the foreground",
            application.contains("TRIM_MEMORY_UI_HIDDEN"),
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
