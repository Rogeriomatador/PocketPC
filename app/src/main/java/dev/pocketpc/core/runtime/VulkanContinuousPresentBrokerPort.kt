package dev.pocketpc.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * v52 host port opened from the broker's immutable PVI1 identity rather than
 * from GuestGraphicsSessionOrchestrator private lease state.
 *
 * The native bridge re-exports PVI1 once to recover and validate the broker
 * metadata, then delegates to the persistent v52 native host session. Source
 * presence is not execution evidence.
 */
class VulkanContinuousPresentBrokerPort private constructor(
    private val resource: VulkanContinuousPresentResourceIdentity,
    private val width: Int,
    private val height: Int,
) : VulkanContinuousPresentHostPort, AutoCloseable {
    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        private const val MAX_TIMEOUT_MILLIS = 30_000L

        fun open(
            offer: GuestGraphicsSessionOrchestrator.ResourceOffer,
            timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        ): Result<VulkanContinuousPresentBrokerPort> =
            runCatching {
                require(NativeRuntimeHost.loaded) {
                    "VULKAN_CONTINUOUS_PRESENT_BROKER_NATIVE_NOT_LOADED"
                }
                require(offer.hostOfferComplete && offer.guestImportConfirmed) {
                    "VULKAN_CONTINUOUS_PRESENT_BROKER_GUEST_IMPORT_NOT_CONFIRMED"
                }
                require(
                    offer.width in 1..VulkanExternalImageFdBroker.MAX_DIMENSION &&
                        offer.height in 1..VulkanExternalImageFdBroker.MAX_DIMENSION
                ) {
                    "VULKAN_CONTINUOUS_PRESENT_BROKER_EXTENT_INVALID"
                }
                requireTimeout(timeoutMillis)

                val resource =
                    VulkanContinuousPresentResourceIdentity(
                        resourceId = offer.resourceId,
                        generation = offer.generation,
                        offerSequence = offer.ownership.sequence,
                    )
                require(resource.structurallyValid) {
                    "VULKAN_CONTINUOUS_PRESENT_BROKER_RESOURCE_INVALID"
                }

                VulkanContinuousPresentBrokerPort(
                    resource = resource,
                    width = offer.width,
                    height = offer.height,
                ).also { port ->
                    port.openPersistentSession(timeoutMillis)
                }
            }

        private fun requireTimeout(timeoutMillis: Long) {
            require(timeoutMillis in 1L..MAX_TIMEOUT_MILLIS) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_TIMEOUT_INVALID"
            }
        }
    }

    private external fun nativeOpenFromBroker(
        resourceId: Long,
        generation: Long,
        offerSequence: Long,
        timeoutNanos: Long,
    ): String

    private external fun nativeAwaitGuestReady(
        sessionId: Long,
        resourceId: Long,
        generation: Long,
        frameSequence: Long,
        targetValue: Long,
        timeoutNanos: Long,
    ): String

    private external fun nativeReadback(
        sessionId: Long,
        resourceId: Long,
        generation: Long,
        frameSequence: Long,
        outputArgb: IntArray,
    ): String

    private external fun nativeSignalHostConsumed(
        sessionId: Long,
        resourceId: Long,
        generation: Long,
        frameSequence: Long,
        signalValue: Long,
    ): String

    private external fun nativeClose(sessionId: Long): String

    private val closed = AtomicBoolean(false)

    @Volatile
    private var sessionId: Long = 0L

    private fun openPersistentSession(timeoutMillis: Long) {
        check(sessionId == 0L && !closed.get()) {
            "VULKAN_CONTINUOUS_PRESENT_BROKER_ALREADY_OPEN"
        }
        val raw =
            nativeOpenFromBroker(
                resourceId = resource.resourceId,
                generation = resource.generation,
                offerSequence = resource.offerSequence,
                timeoutNanos = timeoutMillis * 1_000_000L,
            )
        val fields = parseFields(raw)
            ?: error("VULKAN_CONTINUOUS_PRESENT_BROKER_OPEN_RESPONSE_INVALID:${safe(raw)}")
        require(
            fields["vulkan-continuous-present-host-open"] == "ok" &&
                fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                fields["resource_id"]?.toLongOrNull() == resource.resourceId &&
                fields["generation"]?.toLongOrNull() == resource.generation &&
                fields["offer_sequence"]?.toLongOrNull() == resource.offerSequence &&
                fields["visible_frame"] == "0"
        ) {
            "VULKAN_CONTINUOUS_PRESENT_BROKER_OPEN_FAILED:${safe(raw)}"
        }
        sessionId = fields["session_id"]?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: error("VULKAN_CONTINUOUS_PRESENT_BROKER_SESSION_ID_INVALID")
    }

    override suspend fun awaitGuestReady(
        resource: VulkanContinuousPresentResourceIdentity,
        expectedFrameSequence: Long,
        expectedGuestReadyValue: Long,
        timeoutMillis: Long,
    ): Result<Long> =
        runCatching {
            requireOpen(resource)
            requireTimeout(timeoutMillis)
            require(
                expectedGuestReadyValue ==
                    VulkanContinuousPresentTimeline.guestReadyValue(expectedFrameSequence)
            ) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_GUEST_READY_TARGET_INVALID"
            }

            val raw =
                nativeAwaitGuestReady(
                    sessionId = sessionId,
                    resourceId = resource.resourceId,
                    generation = resource.generation,
                    frameSequence = expectedFrameSequence,
                    targetValue = expectedGuestReadyValue,
                    timeoutNanos = timeoutMillis * 1_000_000L,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_BROKER_AWAIT_RESPONSE_INVALID:${safe(raw)}")
            val observed = fields["observed"]?.toLongOrNull()
                ?: error("VULKAN_CONTINUOUS_PRESENT_BROKER_AWAIT_OBSERVED_MISSING")
            require(
                fields["vulkan-continuous-present-host-await"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == sessionId &&
                    fields["resource_id"]?.toLongOrNull() == resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == resource.generation &&
                    fields["frame_sequence"]?.toLongOrNull() == expectedFrameSequence &&
                    fields["target"]?.toLongOrNull() == expectedGuestReadyValue &&
                    observed == expectedGuestReadyValue &&
                    fields["visible_frame"] == "0"
            ) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_AWAIT_FAILED:${safe(raw)}"
            }
            observed
        }

    override suspend fun readbackFrame(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        timeoutMillis: Long,
    ): VulkanContinuousPresentHostReadback {
        val preflight = runCatching {
            requireOpen(resource)
            requireTimeout(timeoutMillis)
            require(
                frameSequence in
                    VulkanContinuousPresentTimeline.FIRST_FRAME_SEQUENCE..
                        VulkanContinuousPresentTimeline.MAX_FRAME_SEQUENCE
            ) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_FRAME_SEQUENCE_INVALID"
            }
        }.exceptionOrNull()
        if (preflight != null) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = false,
                status = safe(preflight.message.orEmpty()),
            )
        }

        val pixelCount =
            runCatching { Math.multiplyExact(width, height) }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?: return VulkanContinuousPresentHostReadback(
                    frame = null,
                    returnedExternal = false,
                    status = "PIXEL_COUNT_INVALID",
                )
        val argb = IntArray(pixelCount)
        val raw =
            runCatching {
                nativeReadback(
                    sessionId = sessionId,
                    resourceId = resource.resourceId,
                    generation = resource.generation,
                    frameSequence = frameSequence,
                    outputArgb = argb,
                )
            }.getOrElse { failure ->
                return VulkanContinuousPresentHostReadback(
                    frame = null,
                    returnedExternal = false,
                    status = "JNI_THROW_${safe(failure.message.orEmpty())}",
                )
            }
        val fields = parseFields(raw)
            ?: return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = false,
                status = "READBACK_RESPONSE_INVALID",
            )

        val identityMatches =
            fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                fields["session_id"]?.toLongOrNull() == sessionId &&
                fields["resource_id"]?.toLongOrNull() == resource.resourceId &&
                fields["generation"]?.toLongOrNull() == resource.generation &&
                fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                fields["visible_frame"] == "0"
        val returnedExternal =
            identityMatches && fields["returned_external"] == "1"
        if (
            fields["vulkan-continuous-present-host-readback"] != "ok" ||
            !identityMatches ||
            !returnedExternal
        ) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = returnedExternal,
                status = safe(raw),
            )
        }

        val bytes = fields["bytes"]?.toLongOrNull()
        val expectedBytes = pixelCount.toLong() * 4L
        if (bytes != expectedBytes) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = true,
                status = "READBACK_BYTE_COUNT_INVALID",
            )
        }

        return VulkanContinuousPresentHostReadback(
            frame = RuntimeDisplayFramePixels(width = width, height = height, argb = argb),
            returnedExternal = true,
            status = "READBACK_OK",
        )
    }

    override suspend fun signalHostConsumed(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        hostConsumedValue: Long,
    ): Result<Unit> =
        runCatching {
            requireOpen(resource)
            require(
                hostConsumedValue ==
                    VulkanContinuousPresentTimeline.hostConsumedValue(frameSequence)
            ) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_HOST_CONSUMED_TARGET_INVALID"
            }
            val raw =
                nativeSignalHostConsumed(
                    sessionId = sessionId,
                    resourceId = resource.resourceId,
                    generation = resource.generation,
                    frameSequence = frameSequence,
                    signalValue = hostConsumedValue,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_BROKER_SIGNAL_RESPONSE_INVALID:${safe(raw)}")
            require(
                fields["vulkan-continuous-present-host-signal"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == sessionId &&
                    fields["resource_id"]?.toLongOrNull() == resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == resource.generation &&
                    fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                    fields["signal_value"]?.toLongOrNull() == hostConsumedValue &&
                    fields["returned_external"] == "1" &&
                    fields["visible_frame"] == "0"
            ) {
                "VULKAN_CONTINUOUS_PRESENT_BROKER_SIGNAL_FAILED:${safe(raw)}"
            }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = sessionId
        sessionId = 0L
        if (current > 0L) {
            runCatching { nativeClose(current) }
        }
    }

    private fun requireOpen(observed: VulkanContinuousPresentResourceIdentity) {
        check(!closed.get() && sessionId > 0L) {
            "VULKAN_CONTINUOUS_PRESENT_BROKER_PORT_CLOSED"
        }
        require(observed == resource) {
            "VULKAN_CONTINUOUS_PRESENT_BROKER_RESOURCE_MISMATCH"
        }
    }

    private fun parseFields(raw: String): LinkedHashMap<String, String>? {
        if (
            raw.isBlank() ||
            raw.length > 4_096 ||
            raw.any {
                it == '\u0000' || it == '\r' || it == '\n' ||
                    it.code < 0x20 || it.code == 0x7f
            }
        ) {
            return null
        }

        val result = linkedMapOf<String, String>()
        raw.split(';').forEach { token ->
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return null
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (!FIELD_NAME.matches(key) || !FIELD_VALUE.matches(value) || key in result) {
                return null
            }
            result[key] = value
        }
        return result
    }

    private fun safe(value: String): String =
        value.take(512)
            .map { character ->
                if (character.code in 0x21..0x7e) character else '_'
            }.joinToString(separator = "")
            .ifBlank { "UNKNOWN" }

    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val FIELD_VALUE = Regex("^[A-Za-z0-9_.:+-]{1,512}$")
}
