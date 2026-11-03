package dev.pocketpc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopEndpointTest {
    @Test
    fun acceptsSecureWebGateway() {
        assertEquals(
            "https://pc.example.net/vnc.html",
            RemoteDesktopEndpoint.fromUserInput(" https://pc.example.net/vnc.html ")?.url,
        )
        assertEquals(
            "https://remote.example.net/guacamole/",
            RemoteDesktopEndpoint.fromUserInput("https://remote.example.net/guacamole/")?.url,
        )
    }

    @Test
    fun acceptsLocalhostOnlyOverPlainHttp() {
        assertEquals(
            "http://127.0.0.1:6080/vnc.html",
            RemoteDesktopEndpoint.fromUserInput("http://127.0.0.1:6080/vnc.html")?.url,
        )
        assertNull(RemoteDesktopEndpoint.fromUserInput("http://192.168.1.5:6080/vnc.html"))
        assertNull(RemoteDesktopEndpoint.fromUserInput("http://pc.example.net"))
    }

    @Test
    fun refusesCredentialsTokensAndUnsafeSchemes() {
        listOf(
            "",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://user:pass@pc.example.net",
            "https://pc.example.net/?token=secret",
            "https://pc.example.net/#secret",
            "https://pc.example.net:0/vnc.html",
            "https://pc.example.net:99999/vnc.html",
            "https://pc.example.net/space here",
            "//pc.example.net/vnc.html",
        ).forEach { candidate ->
            assertNull("Unexpected accepted URL: $candidate", RemoteDesktopEndpoint.fromUserInput(candidate))
        }
    }

    @Test
    fun navigationAllowsHttpsLoginRedirectsAndBlocksInsecureSchemes() {
        assertTrue(RemoteDesktopEndpoint.isSafeNavigation("https://login.example.net/auth?code=abc"))
        assertTrue(RemoteDesktopEndpoint.isSafeNavigation("http://localhost:6080/vnc.html?autoconnect=1"))
        assertFalse(RemoteDesktopEndpoint.isSafeNavigation("http://192.168.1.5/vnc.html"))
        assertFalse(RemoteDesktopEndpoint.isSafeNavigation("intent://open"))
        assertFalse(RemoteDesktopEndpoint.isSafeNavigation("data:text/html,hi"))
        assertFalse(RemoteDesktopEndpoint.isSafeNavigation("https://user:pass@example.net"))
    }
}
