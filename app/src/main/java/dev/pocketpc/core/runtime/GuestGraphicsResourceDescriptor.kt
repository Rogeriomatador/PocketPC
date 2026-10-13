package dev.pocketpc.core.runtime

/**
 * Metadata contract for a graphics resource that may eventually accompany a
 * transferable OS handle from the Android host into the guest/Wine side.
 *
 * This codec deliberately transports metadata only. It never serializes raw
 * pointers, file-descriptor integers or process-local Vulkan/ANativeWindow
 * handles as if they were portable identities.
 */
data class GuestGraphicsResourceDescriptor(
    val protocol: Int,
    val resourceId: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    val layers: Int,
    val pixelFormat: Int,
    val usage: Long,
    val producerPid: Int,
    val processNamespace: Long,
    val syncSequence: Long,
) {
    val structurallyValid: Boolean
        get() =
            protocol == GuestGraphicsResourceDescriptorCodec.CURRENT_PROTOCOL &&
                resourceId > 0L &&
                generation > 0L &&
                width in 1..MAX_DIMENSION &&
                height in 1..MAX_DIMENSION &&
                layers in 1..MAX_LAYERS &&
                pixelFormat > 0 &&
                usage >= 0L &&
                producerPid > 0 &&
                processNamespace > 0L &&
                syncSequence >= 0L &&
                estimatedPixelCount() != null

    fun estimatedPixelCount(): Long? =
        runCatching {
            Math.multiplyExact(
                Math.multiplyExact(width.toLong(), height.toLong()),
                layers.toLong(),
            )
        }.getOrNull()

    companion object {
        const val MAX_DIMENSION = 16_384
        const val MAX_LAYERS = 256
    }
}

object GuestGraphicsResourceDescriptorCodec {
    const val CURRENT_PROTOCOL = 1
    private const val MAX_RECORD_LENGTH = 2_048

    fun encode(
        descriptor: GuestGraphicsResourceDescriptor,
    ): String {
        require(descriptor.structurallyValid) {
            "GUEST_GRAPHICS_DESCRIPTOR_INVALID"
        }
        return buildString {
            append("pocketpc-graphics-resource=descriptor")
            append(";protocol=").append(descriptor.protocol)
            append(";resource_id=").append(descriptor.resourceId)
            append(";generation=").append(descriptor.generation)
            append(";width=").append(descriptor.width)
            append(";height=").append(descriptor.height)
            append(";layers=").append(descriptor.layers)
            append(";pixel_format=").append(descriptor.pixelFormat)
            append(";usage=").append(descriptor.usage)
            append(";producer_pid=").append(descriptor.producerPid)
            append(";process_namespace=").append(descriptor.processNamespace)
            append(";sync_sequence=").append(descriptor.syncSequence)
        }
    }

    fun decode(raw: String): GuestGraphicsResourceDescriptor? {
        if (
            raw.isBlank() ||
            raw.length > MAX_RECORD_LENGTH ||
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

        val fields = LinkedHashMap<String, String>()
        for (token in raw.split(';')) {
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) {
                return null
            }
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (!FIELD_NAME.matches(key) || key in fields) {
                return null
            }
            fields[key] = value
        }

        if (fields.keys != REQUIRED_FIELDS) {
            return null
        }
        if (fields["pocketpc-graphics-resource"] != "descriptor") {
            return null
        }

        val descriptor =
            GuestGraphicsResourceDescriptor(
                protocol = fields["protocol"]?.toIntOrNull() ?: return null,
                resourceId = fields["resource_id"]?.toLongOrNull() ?: return null,
                generation = fields["generation"]?.toLongOrNull() ?: return null,
                width = fields["width"]?.toIntOrNull() ?: return null,
                height = fields["height"]?.toIntOrNull() ?: return null,
                layers = fields["layers"]?.toIntOrNull() ?: return null,
                pixelFormat = fields["pixel_format"]?.toIntOrNull() ?: return null,
                usage = fields["usage"]?.toLongOrNull() ?: return null,
                producerPid = fields["producer_pid"]?.toIntOrNull() ?: return null,
                processNamespace = fields["process_namespace"]?.toLongOrNull() ?: return null,
                syncSequence = fields["sync_sequence"]?.toLongOrNull() ?: return null,
            )

        return descriptor.takeIf {
            it.structurallyValid
        }
    }

    private val FIELD_NAME =
        Regex("^[a-z0-9_-]{1,64}$")

    private val REQUIRED_FIELDS =
        linkedSetOf(
            "pocketpc-graphics-resource",
            "protocol",
            "resource_id",
            "generation",
            "width",
            "height",
            "layers",
            "pixel_format",
            "usage",
            "producer_pid",
            "process_namespace",
            "sync_sequence",
        )
}
