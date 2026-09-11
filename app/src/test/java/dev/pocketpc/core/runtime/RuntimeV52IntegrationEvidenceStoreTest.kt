package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeV52IntegrationEvidenceStoreTest {
    private val resource =
        VulkanContinuousPresentResourceIdentity(
            resourceId = 41L,
            generation = 7L,
            offerSequence = 3L,
        )

    @Test
    fun recordsOnlyReleasedFramesAndPreservesTimelineEvidence() {
        RuntimeV52IntegrationEvidenceStore.reset(
            resourceId = 41L,
            generation = 7L,
            windowId = 99L,
            reason = "TEST",
        )

        RuntimeV52IntegrationEvidenceStore.record(
            step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_SIGNAL_PENDING,
                frame = 1L,
                fingerprint = 111L,
            ),
        )
        RuntimeV52IntegrationEvidenceStore.record(
            step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED,
                frame = 1L,
                fingerprint = 111L,
            ),
        )
        RuntimeV52IntegrationEvidenceStore.record(
            step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED,
                frame = 2L,
                fingerprint = 222L,
            ),
        )

        val snapshot = RuntimeV52IntegrationEvidenceStore.snapshot()
        assertEquals(2L, snapshot.framesDelivered)
        assertEquals(listOf(1L, 2L), snapshot.frameSequences)
        assertEquals(listOf(1L, 3L), snapshot.guestReadyValues)
        assertEquals(listOf(2L, 4L), snapshot.hostConsumedValues)
        assertEquals(listOf(111L, 222L), snapshot.frameFingerprints)
        assertTrue(snapshot.allFingerprintsDistinct)
        assertFalse(snapshot.physicalVisibleFrameValidated)
        assertFalse(snapshot.robloxValidated)
    }

    @Test
    fun repeatedContentIsVisibleToEvidenceConsumer() {
        RuntimeV52IntegrationEvidenceStore.reset(
            resourceId = 41L,
            generation = 7L,
            windowId = 99L,
            reason = "TEST_FROZEN",
        )
        RuntimeV52IntegrationEvidenceStore.record(
            step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED,
                frame = 1L,
                fingerprint = 999L,
            ),
        )
        RuntimeV52IntegrationEvidenceStore.record(
            step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED,
                frame = 2L,
                fingerprint = 999L,
            ),
        )

        val snapshot = RuntimeV52IntegrationEvidenceStore.snapshot()
        assertEquals(2L, snapshot.framesDelivered)
        assertFalse(snapshot.allFingerprintsDistinct)
    }

    private fun step(
        status: VulkanContinuousPresentHostStepStatus,
        frame: Long,
        fingerprint: Long,
    ) =
        VulkanContinuousPresentHostStep(
            status = status,
            resource = resource,
            frameSequence = frame,
            guestReadyValue = frame * 2L - 1L,
            hostConsumedValue = frame * 2L,
            detail = "TEST",
            frameFingerprint = fingerprint,
        )
}
