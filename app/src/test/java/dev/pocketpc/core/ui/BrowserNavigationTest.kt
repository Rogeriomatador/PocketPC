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
        assertFalse(desktop.contains(" Mobile "))
        assertTrue(desktop.contains("Chrome/150.0"))
    }
}
