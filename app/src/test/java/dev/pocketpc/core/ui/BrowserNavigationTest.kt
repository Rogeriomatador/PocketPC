package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTest {
    @Test
    fun fullHttpsUrlIsPreserved() {
        assertEquals(
            "https://example.com/path?q=1",
            browserTarget("https://example.com/path?q=1"),
        )
    }

    @Test
    fun bareHostnameGetsHttps() {
        assertEquals(
            "https://example.com",
            browserTarget("example.com"),
        )
    }

    @Test
    fun freeTextBecomesGoogleSearch() {
        val target = browserTarget("pocket pc android")
        assertTrue(target.startsWith("https://www.google.com/search?q="))
        assertTrue(target.contains("pocket+pc+android"))
    }

    @Test
    fun desktopUserAgentDropsMobileAndWebViewMarkers() {
        val input =
            "Mozilla/5.0 (Linux; Android 16; Device) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Version/4.0 Chrome/150.0 Mobile Safari/537.36; wv"

        val desktop = desktopUserAgent(input)

        assertFalse(desktop.contains("; wv"))
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains(" Mobile "))
        assertTrue(desktop.contains("Windows NT 10.0"))
        assertTrue(desktop.contains("Win64; x64"))
        assertTrue(desktop.contains("Chrome/150.0"))
    }

    @Test
    fun tabSessionCreatesSelectsAndClosesTabsDeterministically() {
        val session = BrowserSessionState()
        assertEquals(1, session.tabs.size)

        val second = session.newTab("https://example.com")
        assertEquals(2, session.tabs.size)
        assertEquals(second.id, session.activeTabId)
        assertEquals("https://example.com", session.activeTab.url)

        session.updateActive(
            url = "https://example.com/page",
            title = "Example",
        )
        assertEquals("Example", session.activeTab.title)

        session.closeTab(second.id)
        assertEquals(1, session.tabs.size)
        assertEquals(POCKETPC_HOME, session.activeTab.url)
    }

    @Test
    fun newBrowserSessionStartsInDesktopMode() {
        val session = BrowserSessionState()
        assertTrue(session.desktopMode)
    }

}
