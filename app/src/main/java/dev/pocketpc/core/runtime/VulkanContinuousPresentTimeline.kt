package dev.pocketpc.core.runtime

/**
 * Arithmetic/validation contract for the proposed v52 continuous Vulkan path.
 *
 * This helper is deliberately not wired into the active v51 runtime yet. It
 * centralizes the ownership timeline values so guest and Android host code do
 * not grow subtly different formulas while v52 is implemented.
 *
 * Timeline ownership for frame N:
 *   guest ready   = 2*N - 1 (odd)
 *   host consumed = 2*N     (even)
 *
 * Kotlin/JNI transports these values through signed [Long]/jlong, therefore
 * the bridge intentionally fails closed before crossing [Long.MAX_VALUE].
 */
object VulkanContinuousPresentTimeline {
    const val INITIAL_VALUE = 0L
    const val FIRST_FRAME_SEQUENCE = 1L
    const val MAX_FRAME_SEQUENCE = Long.MAX_VALUE / 2L
    const val MAX_GUEST_READY_VALUE = Long.MAX_VALUE - 2L
    const val MAX_HOST_CONSUMED_VALUE = Long.MAX_VALUE - 1L

    fun guestReadyValue(frameSequence: Long): Long {
        requireFrameSequence(frameSequence)
        return Math.subtractExact(
            Math.multiplyExact(frameSequence, 2L),
            1L,
        )
    }

    fun hostConsumedValue(frameSequence: Long): Long {
        requireFrameSequence(frameSequence)
        return Math.multiplyExact(frameSequence, 2L)
    }

    fun previousHostConsumedValue(frameSequence: Long): Long {
        requireFrameSequence(frameSequence)
        return if (frameSequence == FIRST_FRAME_SEQUENCE) {
            INITIAL_VALUE
        } else {
            hostConsumedValue(frameSequence - 1L)
        }
    }

    fun frameSequenceFromGuestReady(value: Long): Long? {
        if (
            value <= INITIAL_VALUE ||
            value > MAX_GUEST_READY_VALUE ||
            value and 1L == 0L
        ) {
            return null
        }
        val frameSequence = (value / 2L) + 1L
        return frameSequence.takeIf { it in FIRST_FRAME_SEQUENCE..MAX_FRAME_SEQUENCE }
    }

    fun frameSequenceFromHostConsumed(value: Long): Long? {
        if (
            value <= INITIAL_VALUE ||
            value > MAX_HOST_CONSUMED_VALUE ||
            value and 1L != 0L
        ) {
            return null
        }
        val frameSequence = value / 2L
        return frameSequence.takeIf { it in FIRST_FRAME_SEQUENCE..MAX_FRAME_SEQUENCE }
    }

    fun isGuestReadyValue(value: Long): Boolean =
        frameSequenceFromGuestReady(value) != null

    fun isHostConsumedValue(value: Long): Boolean =
        value == INITIAL_VALUE || frameSequenceFromHostConsumed(value) != null

    fun canAdvanceAfterHostConsumed(
        frameSequence: Long,
        observedTimelineValue: Long,
    ): Boolean {
        if (frameSequence !in FIRST_FRAME_SEQUENCE..MAX_FRAME_SEQUENCE) return false
        val required = previousHostConsumedValue(frameSequence)
        return observedTimelineValue >= required && observedTimelineValue >= INITIAL_VALUE
    }

    private fun requireFrameSequence(frameSequence: Long) {
        require(frameSequence in FIRST_FRAME_SEQUENCE..MAX_FRAME_SEQUENCE) {
            "VULKAN_CONTINUOUS_PRESENT_FRAME_SEQUENCE_OUT_OF_RANGE"
        }
    }
}
