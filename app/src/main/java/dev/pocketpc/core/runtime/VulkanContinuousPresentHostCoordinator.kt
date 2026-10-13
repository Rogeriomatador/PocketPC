package dev.pocketpc.core.runtime

/**
 * Result of one future v52 native host consume.
 *
 * [returnedExternal] is deliberately independent from [frame]: a failed readback
 * may still have safely returned the exported image to VK_QUEUE_FAMILY_EXTERNAL.
 * That distinction decides whether the same frame can be retried or the whole
 * resource generation must be poisoned/recreated.
 */
data class VulkanContinuousPresentHostReadback(
    val frame: RuntimeDisplayFramePixels?,
    val returnedExternal: Boolean,
    val status: String,
) {
    val frameStructurallyValid: Boolean
        get() {
            val value = frame ?: return false
            if (value.width <= 0 || value.height <= 0) return false
            val expectedPixels =
                runCatching {
                    Math.multiplyExact(value.width, value.height)
                }.getOrNull() ?: return false
            return value.argb.size == expectedPixels
        }
}

/**
 * Native/transport boundary required by the v52 host coordinator.
 *
 * No implementation is selected by the active v51 runtime yet. Implementations
 * must bind all operations to the same PVI1 resource identity and PVS1 ownership
 * timeline. Merely implementing this interface is not execution evidence.
 */
interface VulkanContinuousPresentHostPort {
    suspend fun awaitGuestReady(
        resource: VulkanContinuousPresentResourceIdentity,
        expectedFrameSequence: Long,
        expectedGuestReadyValue: Long,
        timeoutMillis: Long,
    ): Result<Long>

    suspend fun readbackFrame(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        timeoutMillis: Long,
    ): VulkanContinuousPresentHostReadback

    suspend fun signalHostConsumed(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        hostConsumedValue: Long,
    ): Result<Unit>
}

enum class VulkanContinuousPresentHostStepStatus {
    MODEL_DELIVERED_GUEST_RELEASED,
    MODEL_DELIVERED_SIGNAL_PENDING,
    WAIT_FAILED,
    TIMELINE_REJECTED,
    READBACK_RETRYABLE,
    READBACK_POISONED_GENERATION,
    MODEL_DELIVERY_RETRYABLE,
    INTERNAL_STATE_REJECTED,
}

/**
 * Source-level result only. MODEL_DELIVERED means the PocketPC desktop model
 * accepted the ARGB frame. It never means an Android Surface physically showed
 * that frame and never proves Wine/Roblox execution or playability.
 */
data class VulkanContinuousPresentHostStep(
    val status: VulkanContinuousPresentHostStepStatus,
    val resource: VulkanContinuousPresentResourceIdentity,
    val frameSequence: Long,
    val guestReadyValue: Long,
    val hostConsumedValue: Long,
    val detail: String,
    val frameFingerprint: Long? = null,
) {
    val generationMustBeRecreated: Boolean
        get() =
            status ==
                VulkanContinuousPresentHostStepStatus.READBACK_POISONED_GENERATION

    val modelDeliveryAccepted: Boolean
        get() =
            status == VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED ||
                status == VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_SIGNAL_PENDING

    // Fail closed: source-level model delivery is not physical presentation.
    val hostVisibleFrameValidated: Boolean
        get() = false

    val robloxValidated: Boolean
        get() = false
}

/**
 * Fail-closed Android-host coordinator for the proposed continuous-present v52
 * protocol.
 *
 * This class turns the v52 ownership arithmetic/state machine into a repeatable
 * host-side transaction:
 *
 *  1. wait for the exact odd guest-ready value for frame N;
 *  2. acquire a host lease for the exact resource generation;
 *  3. ask the native port to read back N and return external ownership;
 *  4. publish N to the exact PocketPC desktop window;
 *  5. commit host ownership and signal the exact even host-consumed value;
 *  6. only then allow frame N+1.
 *
 * A failed even-value signal is retained as a pending release. The coordinator
 * retries that signal before it is allowed to wait for another frame, preventing
 * an already-delivered frame from being read/delivered a second time merely
 * because the transport acknowledgement failed.
 *
 * This is source implementation groundwork only. It is intentionally not wired
 * into the active v51 runtime and does not claim Vulkan/JNI execution.
 */
class VulkanContinuousPresentHostCoordinator(
    initialResource: VulkanContinuousPresentResourceIdentity,
    private val windowId: Long,
    private val port: VulkanContinuousPresentHostPort,
    private val desktopFrameSender:
        (RuntimeDesktopExternalVulkanFrame) -> Result<Unit>,
) {
    companion object {
        const val MAX_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }

    private data class PendingHostConsumedSignal(
        val resource: VulkanContinuousPresentResourceIdentity,
        val frameSequence: Long,
        val hostConsumedValue: Long,
        val guestReadyValue: Long,
        val frameFingerprint: Long,
    )

    private val ownership =
        VulkanContinuousPresentOwnershipState(initialResource)
    private var pendingSignal: PendingHostConsumedSignal? = null

    init {
        require(initialResource.structurallyValid) {
            "VULKAN_CONTINUOUS_PRESENT_COORDINATOR_RESOURCE_INVALID"
        }
        require(windowId > 0L) {
            "VULKAN_CONTINUOUS_PRESENT_COORDINATOR_WINDOW_INVALID"
        }
    }

    val currentResource: VulkanContinuousPresentResourceIdentity
        get() = ownership.currentResource

    val expectedFrameSequence: Long
        get() = ownership.expectedFrameSequence

    val generationMustBeRecreated: Boolean
        get() = ownership.poisoned

    val hostConsumedSignalPending: Boolean
        get() = pendingSignal != null

    /**
     * Processes at most one frame transaction. Callers may invoke this repeatedly
     * from a lifecycle-owned coroutine to form the host loop.
     */
    suspend fun consumeNextFrame(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): VulkanContinuousPresentHostStep {
        requireTimeout(timeoutMillis)

        pendingSignal?.let {
            return retryPendingSignal(it)
        }

        val resource = ownership.currentResource
        val frameSequence = ownership.expectedFrameSequence
        val guestReadyValue =
            VulkanContinuousPresentTimeline.guestReadyValue(frameSequence)
        val hostConsumedValue =
            VulkanContinuousPresentTimeline.hostConsumedValue(frameSequence)

        val observedTimeline =
            port.awaitGuestReady(
                resource = resource,
                expectedFrameSequence = frameSequence,
                expectedGuestReadyValue = guestReadyValue,
                timeoutMillis = timeoutMillis,
            ).getOrElse { failure ->
                return step(
                    status = VulkanContinuousPresentHostStepStatus.WAIT_FAILED,
                    resource = resource,
                    frameSequence = frameSequence,
                    guestReadyValue = guestReadyValue,
                    hostConsumedValue = hostConsumedValue,
                    detail = safeDetail(failure),
                )
            }

        val lease =
            ownership.beginHostConsume(
                observedResource = resource,
                observedTimelineValue = observedTimeline,
            ).getOrElse { failure ->
                return step(
                    status = VulkanContinuousPresentHostStepStatus.TIMELINE_REJECTED,
                    resource = resource,
                    frameSequence = frameSequence,
                    guestReadyValue = guestReadyValue,
                    hostConsumedValue = hostConsumedValue,
                    detail = safeDetail(failure),
                )
            }

        val readback =
            runCatching {
                port.readbackFrame(
                    resource = resource,
                    frameSequence = frameSequence,
                    timeoutMillis = timeoutMillis,
                )
            }.getOrElse { failure ->
                ownership.abort(
                    lease = lease,
                    returnedExternal = false,
                )
                return step(
                    status =
                        VulkanContinuousPresentHostStepStatus.READBACK_POISONED_GENERATION,
                    resource = resource,
                    frameSequence = frameSequence,
                    guestReadyValue = guestReadyValue,
                    hostConsumedValue = hostConsumedValue,
                    detail = "READBACK_THROW:${safeDetail(failure)}",
                )
            }

        if (!readback.returnedExternal) {
            ownership.abort(
                lease = lease,
                returnedExternal = false,
            )
            return step(
                status =
                    VulkanContinuousPresentHostStepStatus.READBACK_POISONED_GENERATION,
                resource = resource,
                frameSequence = frameSequence,
                guestReadyValue = guestReadyValue,
                hostConsumedValue = hostConsumedValue,
                detail = safeToken(readback.status),
            )
        }

        val frame = readback.frame
        if (frame == null || !readback.frameStructurallyValid) {
            ownership.abort(
                lease = lease,
                returnedExternal = true,
            )
            return step(
                status = VulkanContinuousPresentHostStepStatus.READBACK_RETRYABLE,
                resource = resource,
                frameSequence = frameSequence,
                guestReadyValue = guestReadyValue,
                hostConsumedValue = hostConsumedValue,
                detail = safeToken(readback.status),
            )
        }

        val frameFingerprint = fingerprintArgb(frame.argb)

        val modelFrame =
            RuntimeDesktopExternalVulkanFrame(
                windowId = windowId,
                identity =
                    RuntimeDisplayExternalFrameIdentity(
                        resourceId = resource.resourceId,
                        generation = resource.generation,
                        sequence = frameSequence,
                    ),
                frame = frame,
            )
        if (!modelFrame.structurallyValid) {
            ownership.abort(
                lease = lease,
                returnedExternal = true,
            )
            return step(
                status = VulkanContinuousPresentHostStepStatus.INTERNAL_STATE_REJECTED,
                resource = resource,
                frameSequence = frameSequence,
                guestReadyValue = guestReadyValue,
                hostConsumedValue = hostConsumedValue,
                detail = "DESKTOP_FRAME_INVALID",
            )
        }

        desktopFrameSender(modelFrame).getOrElse { failure ->
            ownership.abort(
                lease = lease,
                returnedExternal = true,
            )
            return step(
                status = VulkanContinuousPresentHostStepStatus.MODEL_DELIVERY_RETRYABLE,
                resource = resource,
                frameSequence = frameSequence,
                guestReadyValue = guestReadyValue,
                hostConsumedValue = hostConsumedValue,
                detail = safeDetail(failure),
            )
        }

        val committedValue =
            ownership.complete(
                lease = lease,
                returnedExternal = true,
            ).getOrElse { failure ->
                return step(
                    status = VulkanContinuousPresentHostStepStatus.INTERNAL_STATE_REJECTED,
                    resource = resource,
                    frameSequence = frameSequence,
                    guestReadyValue = guestReadyValue,
                    hostConsumedValue = hostConsumedValue,
                    detail = safeDetail(failure),
                )
            }
        check(committedValue == hostConsumedValue) {
            "VULKAN_CONTINUOUS_PRESENT_COMMITTED_VALUE_MISMATCH"
        }

        val pending =
            PendingHostConsumedSignal(
                resource = resource,
                frameSequence = frameSequence,
                hostConsumedValue = hostConsumedValue,
                guestReadyValue = guestReadyValue,
                frameFingerprint = frameFingerprint,
            )
        pendingSignal = pending
        return retryPendingSignal(pending)
    }

    /**
     * Installs a newer resource generation after resize/recreation. A delivered
     * frame whose even host-consumed value has not yet been signalled blocks the
     * replacement so its ownership transition cannot silently disappear.
     */
    fun replaceGeneration(
        replacement: VulkanContinuousPresentResourceIdentity,
    ): Result<Unit> =
        runCatching {
            check(pendingSignal == null) {
                "VULKAN_CONTINUOUS_PRESENT_SIGNAL_PENDING_DURING_REPLACEMENT"
            }
            ownership.replaceGeneration(replacement).getOrThrow()
        }

    private suspend fun retryPendingSignal(
        pending: PendingHostConsumedSignal,
    ): VulkanContinuousPresentHostStep {
        val result =
            port.signalHostConsumed(
                resource = pending.resource,
                frameSequence = pending.frameSequence,
                hostConsumedValue = pending.hostConsumedValue,
            )
        return result.fold(
            onSuccess = {
                if (pendingSignal == pending) {
                    pendingSignal = null
                }
                step(
                    status =
                        VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED,
                    resource = pending.resource,
                    frameSequence = pending.frameSequence,
                    guestReadyValue = pending.guestReadyValue,
                    hostConsumedValue = pending.hostConsumedValue,
                    detail = "HOST_CONSUMED_SIGNALLED",
                    frameFingerprint = pending.frameFingerprint,
                )
            },
            onFailure = { failure ->
                step(
                    status =
                        VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_SIGNAL_PENDING,
                    resource = pending.resource,
                    frameSequence = pending.frameSequence,
                    guestReadyValue = pending.guestReadyValue,
                    hostConsumedValue = pending.hostConsumedValue,
                    detail = safeDetail(failure),
                    frameFingerprint = pending.frameFingerprint,
                )
            },
        )
    }

    private fun step(
        status: VulkanContinuousPresentHostStepStatus,
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        guestReadyValue: Long,
        hostConsumedValue: Long,
        detail: String,
        frameFingerprint: Long? = null,
    ) =
        VulkanContinuousPresentHostStep(
            status = status,
            resource = resource,
            frameSequence = frameSequence,
            guestReadyValue = guestReadyValue,
            hostConsumedValue = hostConsumedValue,
            detail = detail,
            frameFingerprint = frameFingerprint,
        )

    private fun fingerprintArgb(argb: IntArray): Long {
        var hash = -3750763034362895579L
        argb.forEach { pixel ->
            hash = (hash xor (pixel.toLong() and 0xffffffffL)) * 1099511628211L
        }
        return hash
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1L..MAX_TIMEOUT_MILLIS) {
            "VULKAN_CONTINUOUS_PRESENT_TIMEOUT_INVALID"
        }
    }

    private fun safeDetail(failure: Throwable): String =
        safeToken(
            failure.message
                ?: failure::class.java.simpleName,
        )

    private fun safeToken(value: String): String =
        value
            .take(256)
            .map { character ->
                if (
                    character.code in 0x21..0x7e &&
                    character != ';' &&
                    character != '='
                ) {
                    character
                } else {
                    '_'
                }
            }.joinToString(separator = "")
            .ifBlank { "UNSPECIFIED" }
}
