package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RobloxPlayerDeepLinkTest {
    @Test
    fun acceptsRobloxPlayerSchemeAsOpaqueSingleValue() {
        val raw =
            "roblox-player:1+launchmode:play+gameinfo:opaque-token+placelauncherurl:https%3A%2F%2Fexample.invalid%2FGame%2FPlaceLauncher.ashx%3Frequest%3DRequestGame"
        val parsed =
            requireNotNull(
                RobloxPlayerDeepLink.parse(raw),
            )

        assertEquals(raw, parsed.raw)
        val route =
            PocketBrowserExternalRouteProbe.route(raw)
        assertEquals(
            PocketBrowserExternalRouteKind.ROBLOX_PLAYER,
            route.kind,
        )
        assertEquals(raw, route.robloxDeepLink?.raw)
    }

    @Test
    fun rejectsOtherSchemesWhitespaceControlsAndEmptyPayload() {
        listOf(
            "https://www.roblox.com/games/1",
            "intent://roblox",
            "roblox-player:",
            " roblox-player:1+foo:bar",
            "roblox-player:1+foo:bar ",
            "roblox-player:1+foo:\nbar",
            "roblox-player:1+foo:\u0000bar",
        ).forEach { value ->
            assertNull(value, RobloxPlayerDeepLink.parse(value))
        }
    }

    @Test
    fun blocksOversizedProtocolPayload() {
        val raw =
            "roblox-player:" + "a".repeat(8_192)
        assertNull(RobloxPlayerDeepLink.parse(raw))

        val route =
            PocketBrowserExternalRouteProbe.route("mailto:test@example.com")
        assertEquals(
            PocketBrowserExternalRouteKind.BLOCKED_EXTERNAL,
            route.kind,
        )
        assertTrue(route.robloxDeepLink == null)
    }
}
