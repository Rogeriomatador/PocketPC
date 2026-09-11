package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static policy lock for the graphics-session boundary.
 *
 * This does not execute the Android native socket/JNI path. It prevents future
 * refactors from accidentally claiming that the authenticated session primitive
 * means Wine/DXVK import, synchronization, WSI presentation, or physical Roblox
 * execution are complete.
 */
class GuestGraphicsSessionPolicyTest {
    @Test
    fun transportContractRemainsFailClosedUntilRealGuestIntegrationExists() {
        assertTrue(GuestGraphicsTransportContract.authenticatedSessionHandshakePrimitiveImplemented)
        assertTrue(GuestGraphicsTransportContract.androidSeqpacketSessionHostImplemented)
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
