package dev.pocketpc.core.runtime

import java.util.concurrent.atomic.AtomicBoolean

/**
 * JNI bridge for the proposed Vulkan v52 continuous-present host session.
 *
 * The native side imports the immutable PVI1/PVS1 resource once per generation.
 * [offerSequence] therefore remains descriptor identity while [frameSequence]
 * advances only through the PVS1 odd/even ownership timeline.
 *
 * Source presence does not prove that the JNI entry points loaded or executed on
 * a device. All physical-visible/Roblox claims remain false until separate
 * executed evidence exists.
 */
object VulkanContinuousPresentNativeSession {
    const val PROTOCOL_VERSION = 1
    const val MAX_TIMEOUT_MILLIS = 30_000L
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    data class Handle(
        val sessionId: Long,
        val resource: VulkanContinuousPresentResourceIdentity,
    ) {
        val structurallyValid: Boolean
            get() = sessionId > 0L && resource.structurallyValid
    }

    data class ReadbackAttempt(
        val frame: RuntimeDisplayFramePixels?,
        val returnedExternal: Boolean,
        val status: String,
        val bytes: Long?,
        val nonzeroBytes: Long?,
        val fnv1a64: ULong?,
    )

    private external fun nativeOpen(
        resourceId: Long,
        generation: Long,
        offerSequence: Long,
        width: Int,
        height: Int,
        format: Int,
        allocationSize: Long,
        memoryTypeBits: Long,
        memoryTypeIndex: Int,
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

    private external fun nativeClose(
        sessionId: Long,
    ): String

    fun open(
        lease: VulkanExternalImageFdLease,
        resource: VulkanContinuousPresentResourceIdentity,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): Result<Handle> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_HOST_NOT_LOADED"
            }
            require(lease.structurallyValid) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_LEASE_INVALID"
            }
            require(resource.structurallyValid) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_RESOURCE_INVALID"
            }
            require(
                resource.resourceId == lease.resourceId &&
                    resource.generation == lease.generation
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_RESOURCE_LEASE_MISMATCH"
            }
            requireTimeout(timeoutMillis)

            val raw =
                nativeOpen(
                    resourceId = resource.resourceId,
                    generation = resource.generation,
                    offerSequence = resource.offerSequence,
                    width = lease.width,
                    height = lease.height,
                    format = lease.format,
                    allocationSize = lease.allocationSize,
                    memoryTypeBits = lease.memoryTypeBits,
                    memoryTypeIndex = lease.memoryTypeIndex,
                    timeoutNanos = timeoutMillis * 1_000_000L,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_OPEN_RESPONSE_INVALID:${safeStatus(raw)}")
            require(fields.keys == OPEN_SUCCESS_FIELDS) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_OPEN_FIELDS_INVALID"
            }
            require(
                fields["vulkan-continuous-present-host-open"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["resource_id"]?.toLongOrNull() == resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == resource.generation &&
                    fields["offer_sequence"]?.toLongOrNull() == resource.offerSequence &&
                    fields["visible_frame"] == "0"
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_OPEN_FAILED:${safeStatus(raw)}"
            }
            Handle(
                sessionId = fields["session_id"]?.toLongOrNull()
                    ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_SESSION_ID_MISSING"),
                resource = resource,
            ).also {
                require(it.structurallyValid) {
                    "VULKAN_CONTINUOUS_PRESENT_NATIVE_HANDLE_INVALID"
                }
            }
        }

    fun awaitGuestReady(
        handle: Handle,
        frameSequence: Long,
        targetValue: Long,
        timeoutMillis: Long,
    ): Result<Long> =
        runCatching {
            requireHandle(handle)
            requireTimeout(timeoutMillis)
            require(
                targetValue == VulkanContinuousPresentTimeline.guestReadyValue(frameSequence)
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_GUEST_READY_TARGET_INVALID"
            }

            val raw =
                nativeAwaitGuestReady(
                    sessionId = handle.sessionId,
                    resourceId = handle.resource.resourceId,
                    generation = handle.resource.generation,
                    frameSequence = frameSequence,
                    targetValue = targetValue,
                    timeoutNanos = timeoutMillis * 1_000_000L,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_AWAIT_RESPONSE_INVALID:${safeStatus(raw)}")
            require(fields.keys == AWAIT_SUCCESS_FIELDS) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_AWAIT_FIELDS_INVALID"
            }
            val observed = fields["observed"]?.toLongOrNull()
                ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_AWAIT_OBSERVED_MISSING")
            require(
                fields["vulkan-continuous-present-host-await"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == handle.sessionId &&
                    fields["resource_id"]?.toLongOrNull() == handle.resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == handle.resource.generation &&
                    fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                    fields["target"]?.toLongOrNull() == targetValue &&
                    observed == targetValue &&
                    fields["visible_frame"] == "0"
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_AWAIT_FAILED:${safeStatus(raw)}"
            }
            observed
        }

    fun readback(
        handle: Handle,
        lease: VulkanExternalImageFdLease,
        frameSequence: Long,
    ): ReadbackAttempt {
        if (!handle.structurallyValid || !lease.structurallyValid) {
            return failedReadback("INVALID_HANDLE_OR_LEASE", returnedExternal = false)
        }
        if (
            handle.resource.resourceId != lease.resourceId ||
            handle.resource.generation != lease.generation
        ) {
            return failedReadback("RESOURCE_LEASE_MISMATCH", returnedExternal = false)
        }
        if (
            frameSequence !in
                VulkanContinuousPresentTimeline.FIRST_FRAME_SEQUENCE..
                    VulkanContinuousPresentTimeline.MAX_FRAME_SEQUENCE
        ) {
            return failedReadback("FRAME_SEQUENCE_INVALID", returnedExternal = false)
        }

        val pixelCount =
            runCatching {
                Math.multiplyExact(lease.width, lease.height)
            }.getOrElse {
                return failedReadback("PIXEL_COUNT_OVERFLOW", returnedExternal = false)
            }
        if (pixelCount <= 0) {
            return failedReadback("PIXEL_COUNT_INVALID", returnedExternal = false)
        }
        val argb = IntArray(pixelCount)

        val raw =
            runCatching {
                nativeReadback(
                    sessionId = handle.sessionId,
                    resourceId = handle.resource.resourceId,
                    generation = handle.resource.generation,
                    frameSequence = frameSequence,
                    outputArgb = argb,
                )
            }.getOrElse {
                return failedReadback(
                    "JNI_THROW_${safeStatus(it.message.orEmpty())}",
                    returnedExternal = false,
                )
            }
        val fields = parseFields(raw)
            ?: return failedReadback(
                "RESPONSE_INVALID_${safeStatus(raw)}",
                returnedExternal = false,
            )

        if (fields["vulkan-continuous-present-host-readback"] != "ok") {
            val identityMatches =
                fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == handle.sessionId &&
                    fields["resource_id"]?.toLongOrNull() == handle.resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == handle.resource.generation &&
                    fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                    fields["visible_frame"] == "0"
            val returnedExternal = identityMatches && fields["returned_external"] == "1"
            return failedReadback(
                status = safeStatus(raw),
                returnedExternal = returnedExternal,
            )
        }

        if (fields.keys != READBACK_SUCCESS_FIELDS) {
            return failedReadback("SUCCESS_FIELDS_INVALID", returnedExternal = false)
        }
        val bytes = fields["bytes"]?.toLongOrNull()
            ?: return failedReadback("BYTES_INVALID", returnedExternal = false)
        val nonzeroBytes = fields["nonzero_bytes"]?.toLongOrNull()
            ?: return failedReadback("NONZERO_BYTES_INVALID", returnedExternal = false)
        val checksum = fields["fnv1a64"]?.toULongOrNull()
            ?: return failedReadback("CHECKSUM_INVALID", returnedExternal = false)
        val expectedBytes = pixelCount.toLong() * 4L
        val valid =
            fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                fields["session_id"]?.toLongOrNull() == handle.sessionId &&
                fields["resource_id"]?.toLongOrNull() == handle.resource.resourceId &&
                fields["generation"]?.toLongOrNull() == handle.resource.generation &&
                fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                fields["returned_external"] == "1" &&
                fields["visible_frame"] == "0" &&
                bytes == expectedBytes &&
                nonzeroBytes in 0L..bytes
        if (!valid) {
            return failedReadback("SUCCESS_CONTRACT_INVALID", returnedExternal = false)
        }

        return ReadbackAttempt(
            frame =
                RuntimeDisplayFramePixels(
                    width = lease.width,
                    height = lease.height,
                    argb = argb,
                ),
            returnedExternal = true,
            status = "READBACK_OK",
            bytes = bytes,
            nonzeroBytes = nonzeroBytes,
            fnv1a64 = checksum,
        )
    }

    fun signalHostConsumed(
        handle: Handle,
        frameSequence: Long,
        signalValue: Long,
    ): Result<Unit> =
        runCatching {
            requireHandle(handle)
            require(
                signalValue == VulkanContinuousPresentTimeline.hostConsumedValue(frameSequence)
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_HOST_CONSUMED_TARGET_INVALID"
            }

            val raw =
                nativeSignalHostConsumed(
                    sessionId = handle.sessionId,
                    resourceId = handle.resource.resourceId,
                    generation = handle.resource.generation,
                    frameSequence = frameSequence,
                    signalValue = signalValue,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_SIGNAL_RESPONSE_INVALID:${safeStatus(raw)}")
            require(fields.keys == SIGNAL_SUCCESS_FIELDS) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_SIGNAL_FIELDS_INVALID"
            }
            require(
                fields["vulkan-continuous-present-host-signal"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == handle.sessionId &&
                    fields["resource_id"]?.toLongOrNull() == handle.resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == handle.resource.generation &&
                    fields["frame_sequence"]?.toLongOrNull() == frameSequence &&
                    fields["signal_value"]?.toLongOrNull() == signalValue &&
                    fields["returned_external"] == "1" &&
                    fields["visible_frame"] == "0"
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_SIGNAL_FAILED:${safeStatus(raw)}"
            }
        }

    fun close(handle: Handle): Result<Unit> =
        runCatching {
            requireHandle(handle)
            val raw = nativeClose(handle.sessionId)
            val fields = parseFields(raw)
                ?: error("VULKAN_CONTINUOUS_PRESENT_NATIVE_CLOSE_RESPONSE_INVALID:${safeStatus(raw)}")
            require(fields.keys == CLOSE_SUCCESS_FIELDS) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_CLOSE_FIELDS_INVALID"
            }
            require(
                fields["vulkan-continuous-present-host-close"] == "ok" &&
                    fields["protocol"]?.toIntOrNull() == PROTOCOL_VERSION &&
                    fields["session_id"]?.toLongOrNull() == handle.sessionId &&
                    fields["resource_id"]?.toLongOrNull() == handle.resource.resourceId &&
                    fields["generation"]?.toLongOrNull() == handle.resource.generation
            ) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_CLOSE_FAILED:${safeStatus(raw)}"
            }
        }

    private fun requireHandle(handle: Handle) {
        require(handle.structurallyValid) {
            "VULKAN_CONTINUOUS_PRESENT_NATIVE_HANDLE_INVALID"
        }
    }

    private fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1L..MAX_TIMEOUT_MILLIS) {
            "VULKAN_CONTINUOUS_PRESENT_NATIVE_TIMEOUT_INVALID"
        }
    }

    private fun failedReadback(
        status: String,
        returnedExternal: Boolean,
    ) =
        ReadbackAttempt(
            frame = null,
            returnedExternal = returnedExternal,
            status = safeStatus(status).ifBlank { "READBACK_FAILED" },
            bytes = null,
            nonzeroBytes = null,
            fnv1a64 = null,
        )

    private fun parseFields(raw: String): LinkedHashMap<String, String>? {
        if (
            raw.isBlank() ||
            raw.length > 4_096 ||
            raw.any {
                it == '\u0000' ||
                    it == '\r' ||
                    it == '\n' ||
                    it.code < 0x20 ||
                    it.code == 0x7f
            }
        ) {
            return null
        }

        val result = linkedMapOf<String, String>()
        for (token in raw.split(';')) {
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return null
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (!FIELD_NAME.matches(key) || key in result) return null
            if (!FIELD_VALUE.matches(value)) return null
            result[key] = value
        }
        return result
    }

    private fun safeStatus(raw: String): String =
        raw.take(512)
            .map { character ->
                if (character.code in 0x21..0x7e) character else '_'
            }.joinToString(separator = "")

    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val FIELD_VALUE = Regex("^[A-Za-z0-9_.:+-]{1,512}$")

    private val OPEN_SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-continuous-present-host-open",
            "protocol",
            "session_id",
            "resource_id",
            "generation",
            "offer_sequence",
            "visible_frame",
        )
    private val AWAIT_SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-continuous-present-host-await",
            "protocol",
            "session_id",
            "resource_id",
            "generation",
            "frame_sequence",
            "target",
            "observed",
            "visible_frame",
        )
    private val READBACK_SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-continuous-present-host-readback",
            "protocol",
            "session_id",
            "resource_id",
            "generation",
            "frame_sequence",
            "bytes",
            "nonzero_bytes",
            "fnv1a64",
            "returned_external",
            "visible_frame",
        )
    private val SIGNAL_SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-continuous-present-host-signal",
            "protocol",
            "session_id",
            "resource_id",
            "generation",
            "frame_sequence",
            "signal_value",
            "returned_external",
            "visible_frame",
        )
    private val CLOSE_SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-continuous-present-host-close",
            "protocol",
            "session_id",
            "resource_id",
            "generation",
        )
}

/**
 * Concrete source adapter from the v52 host coordinator to the persistent JNI
 * session. It is intentionally not selected by the active v51 runtime yet.
 */
class VulkanContinuousPresentNativePort private constructor(
    private val lease: VulkanExternalImageFdLease,
    private val resource: VulkanContinuousPresentResourceIdentity,
    private val handle: VulkanContinuousPresentNativeSession.Handle,
) : VulkanContinuousPresentHostPort, AutoCloseable {
    private val closed = AtomicBoolean(false)

    companion object {
        fun open(
            lease: VulkanExternalImageFdLease,
            resource: VulkanContinuousPresentResourceIdentity,
            timeoutMillis: Long = VulkanContinuousPresentNativeSession.DEFAULT_TIMEOUT_MILLIS,
        ): Result<VulkanContinuousPresentNativePort> =
            VulkanContinuousPresentNativeSession
                .open(
                    lease = lease,
                    resource = resource,
                    timeoutMillis = timeoutMillis,
                ).map { handle ->
                    VulkanContinuousPresentNativePort(
                        lease = lease,
                        resource = resource,
                        handle = handle,
                    )
                }
    }

    override suspend fun awaitGuestReady(
        resource: VulkanContinuousPresentResourceIdentity,
        expectedFrameSequence: Long,
        expectedGuestReadyValue: Long,
        timeoutMillis: Long,
    ): Result<Long> =
        runCatching {
            check(!closed.get()) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_PORT_CLOSED"
            }
            require(resource == this.resource) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_PORT_RESOURCE_MISMATCH"
            }
            VulkanContinuousPresentNativeSession
                .awaitGuestReady(
                    handle = handle,
                    frameSequence = expectedFrameSequence,
                    targetValue = expectedGuestReadyValue,
                    timeoutMillis = timeoutMillis,
                ).getOrThrow()
        }

    override suspend fun readbackFrame(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        timeoutMillis: Long,
    ): VulkanContinuousPresentHostReadback {
        if (closed.get()) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = false,
                status = "NATIVE_PORT_CLOSED",
            )
        }
        if (resource != this.resource) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = false,
                status = "NATIVE_PORT_RESOURCE_MISMATCH",
            )
        }
        if (
            timeoutMillis !in
                1L..VulkanContinuousPresentNativeSession.MAX_TIMEOUT_MILLIS
        ) {
            return VulkanContinuousPresentHostReadback(
                frame = null,
                returnedExternal = false,
                status = "NATIVE_PORT_TIMEOUT_INVALID",
            )
        }

        val attempt =
            VulkanContinuousPresentNativeSession.readback(
                handle = handle,
                lease = lease,
                frameSequence = frameSequence,
            )
        return VulkanContinuousPresentHostReadback(
            frame = attempt.frame,
            returnedExternal = attempt.returnedExternal,
            status = attempt.status,
        )
    }

    override suspend fun signalHostConsumed(
        resource: VulkanContinuousPresentResourceIdentity,
        frameSequence: Long,
        hostConsumedValue: Long,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_PORT_CLOSED"
            }
            require(resource == this.resource) {
                "VULKAN_CONTINUOUS_PRESENT_NATIVE_PORT_RESOURCE_MISMATCH"
            }
            VulkanContinuousPresentNativeSession
                .signalHostConsumed(
                    handle = handle,
                    frameSequence = frameSequence,
                    signalValue = hostConsumedValue,
                ).getOrThrow()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            VulkanContinuousPresentNativeSession.close(handle)
        }
    }
}
