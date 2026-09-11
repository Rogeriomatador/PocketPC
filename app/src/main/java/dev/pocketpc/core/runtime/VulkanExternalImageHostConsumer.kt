package dev.pocketpc.core.runtime

/**
 * Android-host consumer for a PVI1 image after Wine v51 reports PGA1 stage 6.
 *
 * A successful result proves that the host re-imported the exact exported image,
 * waited for PVS1, acquired the image from VK_QUEUE_FAMILY_EXTERNAL, copied its
 * RGBA8 bytes into host-visible Vulkan memory, and released the image back to
 * VK_QUEUE_FAMILY_EXTERNAL / GENERAL.
 *
 * It deliberately does NOT claim Surface/compositor presentation or a visible
 * frame. Source presence is not execution evidence.
 */
data class VulkanExternalImageHostReadback(
    val protocol: Int,
    val resourceId: Long,
    val generation: Long,
    val sequence: Long,
    val timelineValue: Long,
    val bytes: Long,
    val nonzeroBytes: Long,
    val fnv1a64: ULong,
    val acquired: Boolean,
    val returnedExternal: Boolean,
    val visibleFrame: Boolean,
    val raw: String,
) {
    val structurallyValid: Boolean
        get() =
            protocol == VulkanExternalImageFdBroker.PROTOCOL_VERSION &&
                resourceId > 0L &&
                generation > 0L &&
                sequence > 0L &&
                timelineValue >= 1L &&
                bytes > 0L &&
                nonzeroBytes in 0L..bytes &&
                acquired &&
                returnedExternal &&
                !visibleFrame

    /** Runtime evidence only when this object came from [consume] successfully. */
    val androidAcquireExecuted: Boolean
        get() = structurallyValid

    /** Host readback is still not presentation to an Android Surface. */
    val androidVisibleFrame: Boolean
        get() = false
}

object VulkanExternalImageHostConsumer {
    const val MAX_TIMEOUT_MILLIS = 30_000L
    const val DEFAULT_TIMEOUT_MILLIS = 5_000L

    private external fun nativeConsume(
        resourceId: Long,
        generation: Long,
        sequence: Long,
        width: Int,
        height: Int,
        format: Int,
        allocationSize: Long,
        memoryTypeBits: Long,
        memoryTypeIndex: Int,
        timeoutNanos: Long,
    ): String

    fun consume(
        lease: VulkanExternalImageFdLease,
        sequence: Long,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): Result<VulkanExternalImageHostReadback> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "NATIVE_RUNTIME_HOST_NOT_LOADED"
            }
            require(lease.structurallyValid) {
                "VULKAN_HOST_CONSUMER_LEASE_INVALID"
            }
            require(sequence > 0L) {
                "VULKAN_HOST_CONSUMER_SEQUENCE_INVALID"
            }
            require(timeoutMillis in 1L..MAX_TIMEOUT_MILLIS) {
                "VULKAN_HOST_CONSUMER_TIMEOUT_INVALID"
            }

            val raw =
                nativeConsume(
                    resourceId = lease.resourceId,
                    generation = lease.generation,
                    sequence = sequence,
                    width = lease.width,
                    height = lease.height,
                    format = lease.format,
                    allocationSize = lease.allocationSize,
                    memoryTypeBits = lease.memoryTypeBits,
                    memoryTypeIndex = lease.memoryTypeIndex,
                    timeoutNanos = timeoutMillis * 1_000_000L,
                )

            parseReadback(raw, lease, sequence)
                ?: error("VULKAN_HOST_CONSUMER_FAILED:${safeStatus(raw)}")
        }

    internal fun parseReadback(
        raw: String,
        lease: VulkanExternalImageFdLease,
        expectedSequence: Long,
    ): VulkanExternalImageHostReadback? {
        if (!lease.structurallyValid || expectedSequence <= 0L) return null
        val fields = parseFields(raw) ?: return null
        if (fields.keys != SUCCESS_FIELDS) return null
        if (fields["vulkan-external-image-host-consume"] != "ok") return null

        val readback =
            VulkanExternalImageHostReadback(
                protocol = fields["protocol"]?.toIntOrNull() ?: return null,
                resourceId = fields["resource_id"]?.toLongOrNull() ?: return null,
                generation = fields["generation"]?.toLongOrNull() ?: return null,
                sequence = fields["sequence"]?.toLongOrNull() ?: return null,
                timelineValue = fields["timeline_value"]?.toLongOrNull() ?: return null,
                bytes = fields["bytes"]?.toLongOrNull() ?: return null,
                nonzeroBytes = fields["nonzero_bytes"]?.toLongOrNull() ?: return null,
                fnv1a64 = fields["fnv1a64"]?.toULongOrNull() ?: return null,
                acquired = fields["acquired"] == "1",
                returnedExternal = fields["returned_external"] == "1",
                visibleFrame = fields["visible_frame"] == "1",
                raw = raw,
            )

        val expectedBytes = lease.width.toLong() * lease.height.toLong() * 4L
        return readback.takeIf {
            it.structurallyValid &&
                it.resourceId == lease.resourceId &&
                it.generation == lease.generation &&
                it.sequence == expectedSequence &&
                it.bytes == expectedBytes
        }
    }

    private fun parseFields(raw: String): LinkedHashMap<String, String>? {
        if (
            raw.isBlank() ||
            raw.length > 2_048 ||
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
        raw.take(256)
            .replace('\n', '_')
            .replace('\r', '_')

    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val FIELD_VALUE = Regex("^[A-Za-z0-9_.:+-]{1,256}$")
    private val SUCCESS_FIELDS =
        linkedSetOf(
            "vulkan-external-image-host-consume",
            "protocol",
            "resource_id",
            "generation",
            "sequence",
            "timeline_value",
            "bytes",
            "nonzero_bytes",
            "fnv1a64",
            "acquired",
            "returned_external",
            "visible_frame",
        )
}
