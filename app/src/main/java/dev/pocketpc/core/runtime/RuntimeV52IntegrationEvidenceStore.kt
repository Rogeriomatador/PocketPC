package dev.pocketpc.core.runtime

/**
 * Process-local evidence captured from the experimental v52 host loop.
 *
 * This store records only MODEL_DELIVERED_GUEST_RELEASED steps. Its snapshot is
 * software integration evidence input, never physical-visible or Roblox proof.
 */
object RuntimeV52IntegrationEvidenceStore {
    private const val MAX_RECORDS = 256

    data class FrameRecord(
        val frameSequence: Long,
        val guestReadyValue: Long,
        val hostConsumedValue: Long,
        val frameFingerprint: Long,
    )

    data class Snapshot(
        val resourceId: Long?,
        val generation: Long?,
        val windowId: Long?,
        val records: List<FrameRecord>,
        val resetReason: String,
        val physicalVisibleFrameValidated: Boolean = false,
        val robloxValidated: Boolean = false,
    ) {
        val framesDelivered: Long
            get() = records.size.toLong()

        val frameFingerprints: List<Long>
            get() = records.map { it.frameFingerprint }

        val frameSequences: List<Long>
            get() = records.map { it.frameSequence }

        val guestReadyValues: List<Long>
            get() = records.map { it.guestReadyValue }

        val hostConsumedValues: List<Long>
            get() = records.map { it.hostConsumedValue }

        val allFingerprintsDistinct: Boolean
            get() =
                records.isNotEmpty() &&
                    frameFingerprints.toSet().size == records.size
    }

    private val lock = Any()
    private var resourceId: Long? = null
    private var generation: Long? = null
    private var windowId: Long? = null
    private var resetReason: String = "NOT_STARTED"
    private val records = mutableListOf<FrameRecord>()

    fun reset(
        resourceId: Long?,
        generation: Long?,
        windowId: Long?,
        reason: String,
    ) {
        synchronized(lock) {
            this.resourceId = resourceId
            this.generation = generation
            this.windowId = windowId
            resetReason = reason.take(128)
            records.clear()
        }
    }

    fun record(step: VulkanContinuousPresentHostStep) {
        if (
            step.status !=
                VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED
        ) {
            return
        }
        val fingerprint = step.frameFingerprint ?: return
        synchronized(lock) {
            if (records.size >= MAX_RECORDS) return
            records +=
                FrameRecord(
                    frameSequence = step.frameSequence,
                    guestReadyValue = step.guestReadyValue,
                    hostConsumedValue = step.hostConsumedValue,
                    frameFingerprint = fingerprint,
                )
        }
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                resourceId = resourceId,
                generation = generation,
                windowId = windowId,
                records = records.toList(),
                resetReason = resetReason,
            )
        }
}
