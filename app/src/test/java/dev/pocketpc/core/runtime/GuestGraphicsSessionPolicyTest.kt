package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static policy lock for the graphics-session boundary.
 *
 * This does not execute the Android native socket/JNI path. It prevents future
 * refactors from accidentally claiming that the authenticated host launch and
 * PVI1/PVS1 offer mean Wine/DXVK import, synchronization, visible presentation,
 * or physical Roblox execution are complete.
 */
class GuestGraphicsSessionPolicyTest {
    @Test
    fun hostLaunchFoundationExistsButGuestIntegrationRemainsFailClosed() {
        assertTrue(GuestGraphicsTransportContract.authenticatedSessionHandshakePrimitiveImplemented)
        assertTrue(GuestGraphicsTransportContract.androidSeqpacketSessionHostImplemented)
        assertTrue(GuestGraphicsTransportContract.nativeAuthenticationDeadlineImplemented)
        assertTrue(GuestGraphicsTransportContract.graphicsSessionOrchestratorImplemented)
        assertTrue(GuestGraphicsTransportContract.prootGraphicsEnvironmentInjectionImplemented)
        assertTrue(GuestGraphicsTransportContract.authenticatedHostPvi1Pvs1OfferImplemented)
        assertTrue(GuestGraphicsTransportContract.externalImagePvi1ProtocolImplemented)
        assertTrue(GuestGraphicsTransportContract.externalTimelineSemaphorePvs1ProtocolImplemented)

        assertFalse(GuestGraphicsTransportContract.guestReceiveImplemented)
        assertFalse(GuestGraphicsTransportContract.guestImportImplemented)
        assertFalse(GuestGraphicsTransportContract.synchronizationImplemented)
        assertFalse(GuestGraphicsTransportContract.readyForWsiImplementation())

        assertFalse(GuestGraphicsTransportContract.softwareTestExecuted)
        assertFalse(GuestGraphicsTransportContract.integrationTestExecuted)
        assertFalse(GuestGraphicsTransportContract.physicalTestExecuted)
    }
}
