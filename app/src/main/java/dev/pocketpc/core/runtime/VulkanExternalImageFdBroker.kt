package dev.pocketpc.core.runtime

data class VulkanExternalImageFdLease(
    val protocol: Int,
    val resourceId: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    val format: Int,
    val allocationSize: Long,
    val memoryTypeBits: Long,
    val memoryTypeIndex: Int,
    val externalOwner: Boolean,
    val boundaryLayout: Int,
    val raw: String,
) {
    val structurallyValid: Boolean
        get() =
            protocol == VulkanExternalImageFdBroker.PROTOCOL_VERSION &&
                resourceId > 0L &&
                generation > 0L &&
                width in 1..VulkanExternalImageFdBroker.MAX_DIMENSION &&
                height in 1..VulkanExternalImageFdBroker.MAX_DIMENSION &&
                format == VulkanExternalImageFdBroker.FORMAT_R8G8B8A8_UNORM &&
                allocationSize > 0L &&
                memoryTypeBits > 0L &&
                memoryTypeIndex in 0..31 &&
                (memoryTypeBits and (1L shl memoryTypeIndex)) != 0L &&
                externalOwner &&
                boundaryLayout == VulkanExternalImageFdBroker.EXTERNAL_BOUNDARY_LAYOUT_GENERAL

    fun toGuestDescriptor(
        producerPid: Int,
        processNamespace: Long,
        syncSequence: Long,
    ): GuestGraphicsResourceDescriptor =
        GuestGraphicsResourceDescriptor(
            protocol = GuestGraphicsResourceDescriptorCodec.CURRENT_PROTOCOL,
            resourceId = resourceId,
            generation = generation,
            width = width,
            height = height,
            layers = 1,
            pixelFormat = format,
            usage = VulkanExternalImageFdBroker.IMAGE_USAGE_FLAGS,
            producerPid = producerPid,
            processNamespace = processNamespace,
            syncSequence = syncSequence,
        ).also {
            require(it.structurallyValid) {
                "VULKAN_EXTERNAL_IMAGE_GUEST_DESCRIPTOR_INVALID"
            }
        }
}

object VulkanExternalImageFdBroker {
    const val PROTOCOL_VERSION = 1
    const val MAX_DIMENSION = 4_096
    const val FORMAT_R8G8B8A8_UNORM = 37
    const val IMAGE_USAGE_FLAGS = 0x17L

    /** Vulkan VkImageLayout numeric value for VK_IMAGE_LAYOUT_GENERAL. */
    const val EXTERNAL_BOUNDARY_LAYOUT_GENERAL = 1

    const val TIMELINE_PROTOCOL = "PVS1"
    const val TIMELINE_INITIAL_VALUE = 0L
    const val TIMELINE_ROLE_FRAME_OWNERSHIP = 1

    private external fun nativeCreate(
        width: Int,
        height: Int,
    ): String

    private external fun nativeSend(
        resourceId: Long,
        generation: Long,
        socketFd: Int,
        sequence: Long,
    ): String

    private external fun nativeSendTimeline(
        resourceId: Long,
        generation: Long,
        socketFd: Int,
    ): String

    private external fun nativeRelease(
        resourceId: Long,
        generation: Long,
    ): String

    fun create(
        width: Int,
        height: Int,
    ): Result<VulkanExternalImageFdLease> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "NATIVE_RUNTIME_HOST_NOT_LOADED"
            }
            require(width in 1..MAX_DIMENSION) {
                "VULKAN_EXTERNAL_IMAGE_WIDTH_INVALID"
            }
            require(height in 1..MAX_DIMENSION) {
                "VULKAN_EXTERNAL_IMAGE_HEIGHT_INVALID"
            }

            val raw = nativeCreate(width, height)
            parseLease(raw)
                ?: error(
                    "VULKAN_EXTERNAL_IMAGE_CREATE_FAILED:" +
                        safeStatus(raw),
                )
        }

    fun send(
        lease: VulkanExternalImageFdLease,
        socketFd: Int,
        sequence: Long,
    ): Result<Unit> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "NATIVE_RUNTIME_HOST_NOT_LOADED"
            }
            require(lease.structurallyValid) {
                "VULKAN_EXTERNAL_IMAGE_LEASE_INVALID"
            }
            require(socketFd >= 0) {
                "VULKAN_EXTERNAL_IMAGE_SOCKET_INVALID"
            }
            require(sequence > 0L) {
                "VULKAN_EXTERNAL_IMAGE_SEQUENCE_INVALID"
            }

            val raw =
                nativeSend(
                    lease.resourceId,
                    lease.generation,
                    socketFd,
                    sequence,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_EXTERNAL_IMAGE_SEND_RESPONSE_INVALID")
            require(
                fields["vulkan-external-image-fd-send"] == "ok" &&
                    fields["protocol"] == PROTOCOL_VERSION.toString() &&
                    fields["resource_id"]?.toLongOrNull() == lease.resourceId &&
                    fields["generation"]?.toLongOrNull() == lease.generation &&
                    fields["sequence"]?.toLongOrNull() == sequence &&
                    fields["external_owner"] == "1" &&
                    fields["boundary_layout"]?.toIntOrNull() == EXTERNAL_BOUNDARY_LAYOUT_GENERAL &&
                    fields["send_result"] == "0"
            ) {
                "VULKAN_EXTERNAL_IMAGE_SEND_FAILED:" + safeStatus(raw)
            }
        }

    fun sendTimeline(
        lease: VulkanExternalImageFdLease,
        socketFd: Int,
    ): Result<Unit> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "NATIVE_RUNTIME_HOST_NOT_LOADED"
            }
            require(lease.structurallyValid) {
                "VULKAN_EXTERNAL_IMAGE_LEASE_INVALID"
            }
            require(socketFd >= 0) {
                "VULKAN_EXTERNAL_TIMELINE_SOCKET_INVALID"
            }

            val raw =
                nativeSendTimeline(
                    lease.resourceId,
                    lease.generation,
                    socketFd,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_EXTERNAL_TIMELINE_SEND_RESPONSE_INVALID")
            require(
                fields["vulkan-external-timeline-fd-send"] == "ok" &&
                    fields["protocol"] == PROTOCOL_VERSION.toString() &&
                    fields["resource_id"]?.toLongOrNull() == lease.resourceId &&
                    fields["generation"]?.toLongOrNull() == lease.generation &&
                    fields["initial_value"]?.toLongOrNull() == TIMELINE_INITIAL_VALUE &&
                    fields["role"]?.toIntOrNull() == TIMELINE_ROLE_FRAME_OWNERSHIP &&
                    fields["send_result"] == "0"
            ) {
                "VULKAN_EXTERNAL_TIMELINE_SEND_FAILED:" + safeStatus(raw)
            }
        }

    fun release(
        lease: VulkanExternalImageFdLease,
    ): Result<Unit> =
        runCatching {
            require(NativeRuntimeHost.loaded) {
                "NATIVE_RUNTIME_HOST_NOT_LOADED"
            }
            require(lease.structurallyValid) {
                "VULKAN_EXTERNAL_IMAGE_LEASE_INVALID"
            }

            val raw =
                nativeRelease(
                    lease.resourceId,
                    lease.generation,
                )
            val fields = parseFields(raw)
                ?: error("VULKAN_EXTERNAL_IMAGE_RELEASE_RESPONSE_INVALID")
            require(
                fields["vulkan-external-image-fd-release"] == "ok" &&
                    fields["protocol"] == PROTOCOL_VERSION.toString() &&
                    fields["resource_id"]?.toLongOrNull() == lease.resourceId &&
                    fields["generation"]?.toLongOrNull() == lease.generation
            ) {
                "VULKAN_EXTERNAL_IMAGE_RELEASE_FAILED:" + safeStatus(raw)
            }
        }

    internal fun parseLease(
        raw: String,
    ): VulkanExternalImageFdLease? {
        val fields = parseFields(raw) ?: return null
        if (fields.keys != LEASE_FIELDS) return null
        if (fields["vulkan-external-image-fd"] != "ok") return null

        val externalOwner = when (fields["external_owner"]) {
            "1" -> true
            "0" -> false
            else -> return null
        }

        val lease =
            VulkanExternalImageFdLease(
                protocol = fields["protocol"]?.toIntOrNull() ?: return null,
                resourceId = fields["resource_id"]?.toLongOrNull() ?: return null,
                generation = fields["generation"]?.toLongOrNull() ?: return null,
                width = fields["width"]?.toIntOrNull() ?: return null,
                height = fields["height"]?.toIntOrNull() ?: return null,
                format = fields["format"]?.toIntOrNull() ?: return null,
                allocationSize = fields["allocation_size"]?.toLongOrNull() ?: return null,
                memoryTypeBits = fields["memory_type_bits"]?.toLongOrNull() ?: return null,
                memoryTypeIndex = fields["memory_type_index"]?.toIntOrNull() ?: return null,
                externalOwner = externalOwner,
                boundaryLayout = fields["boundary_layout"]?.toIntOrNull() ?: return null,
                raw = raw,
            )
        return lease.takeIf { it.structurallyValid }
    }

    private fun parseFields(
        raw: String,
    ): LinkedHashMap<String, String>? {
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
    private val LEASE_FIELDS =
        linkedSetOf(
            "vulkan-external-image-fd",
            "protocol",
            "resource_id",
            "generation",
            "width",
            "height",
            "format",
            "allocation_size",
            "memory_type_bits",
            "memory_type_index",
            "external_owner",
            "boundary_layout",
        )
}
