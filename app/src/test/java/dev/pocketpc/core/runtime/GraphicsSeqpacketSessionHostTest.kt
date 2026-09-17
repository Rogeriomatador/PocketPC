package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsSeqpacketSessionHostTest {
    @Test
    fun launchEnvironmentUsesOnlyExplicitGraphicsSessionVariables() {
        val token = "ab".repeat(GraphicsSeqpacketSessionHost.TOKEN_BYTES)
        val environment =
            GraphicsSeqpacketSessionHost.LaunchEnvironment(
                socketName = "pocketpc-gfx-test",
                tokenHex = token,
            ).variables

        assertEquals(
            setOf(
                "POCKETPC_GRAPHICS_SOCKET_NAME",
                "POCKETPC_GRAPHICS_SESSION_TOKEN",
                "POCKETPC_GRAPHICS_SESSION_PROTOCOL",
            ),
            environment.keys,
        )
        assertEquals("pocketpc-gfx-test", environment["POCKETPC_GRAPHICS_SOCKET_NAME"])
        assertEquals(token, environment["POCKETPC_GRAPHICS_SESSION_TOKEN"])
        assertEquals("1", environment["POCKETPC_GRAPHICS_SESSION_PROTOCOL"])
        assertFalse(environment.containsKey("POCKETPC_VULKAN_HEADLESS_DIAGNOSTIC"))
    }

    @Test
    fun protocolContractDoesNotPromoteRuntimeIntegration() {
        assertTrue(GuestGraphicsTransportContract.authenticatedSessionHandshakePrimitiveImplemented)
        assertTrue(GuestGraphicsTransportContract.androidSeqpacketSessionHostImplemented)
        assertFalse(GuestGraphicsTransportContract.guestReceiveImplemented)
        assertFalse(GuestGraphicsTransportContract.guestImportImplemented)
        assertFalse(GuestGraphicsTransportContract.synchronizationImplemented)
        assertFalse(GuestGraphicsTransportContract.readyForWsiImplementation())
    }
}
