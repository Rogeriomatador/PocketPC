package dev.pocketpc.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSessionStoreTest {
    @Test
    fun durableUrlsAllowWebPagesButRejectLocalOrExecutableSchemes() {
        assertEquals(
            "https://accounts.google.com/",
            BrowserSessionStore.persistentUrl(
                "https://accounts.google.com/"
            ),
        )
        assertEquals(
            "http://example.test/path",
            BrowserSessionStore.persistentUrl(
                "http://example.test/path"
            ),
        )
        assertEquals(
            "about:blank",
            BrowserSessionStore.persistentUrl(
                "about:blank"
            ),
        )

        listOf(
            "file:///data/user/0/dev.pocketpc.core/private",
            "javascript:alert(1)",
            "data:text/html,hello",
            "content://dev.pocketpc/private",
            "intent://example/#Intent;end",
            "",
        ).forEach { unsafe ->
            assertEquals(
                POCKETPC_HOME,
                BrowserSessionStore.persistentUrl(unsafe),
            )
        }
    }

    @Test
    fun durableUrlMetadataIsBounded() {
        val oversized =
            "https://example.test/" +
                "a".repeat(10_000)
        val persisted =
            BrowserSessionStore.persistentUrl(
                oversized
            )

        assertTrue(persisted.length <= 4096)
        assertTrue(
            persisted.startsWith("https://")
        )
    }

    @Test
    fun desktopUsesDurableBrowserSessionInsteadOfMemoryOnlySession() {
        val sourceRoot = locateMainSourceRoot()
        val pocketPcApp =
            File(
                sourceRoot,
                "dev/pocketpc/core/ui/PocketPcApp.kt",
            ).readText()
        val store =
            File(
                sourceRoot,
                "dev/pocketpc/core/ui/BrowserSessionStore.kt",
            ).readText()

        assertTrue(
            "PocketPcApp must restore the browser workspace from durable package-private storage",
            pocketPcApp.contains(
                "rememberPersistentBrowserSession(appContext)"
            ),
        )
        assertTrue(
            "Browser session metadata must use package-private SharedPreferences",
            store.contains("getSharedPreferences(") &&
                store.contains("Context.MODE_PRIVATE"),
        )
        assertTrue(
            "Durable browser session must preserve tabs, active tab and desktop-mode metadata",
            store.contains("session.tabs.map") &&
                store.contains("activeIndex = activeIndex") &&
                store.contains("desktopMode = session.desktopMode"),
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
