package dev.pocketpc.core.runtime

data class HostGraphicsResourceLease(
    val resourceId: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    val layers: Int,
    val pixelFormat: Int,
    val usage: Long,
    val producerPid: Int,
) {
    val structurallyValid: Boolean
        get() =
            resourceId > 0L &&
                generation > 0L &&
                width in 1..GuestGraphicsResourceDescriptor.MAX_DIMENSION &&
                height in 1..GuestGraphicsResourceDescriptor.MAX_DIMENSION &&
                layers in 1..GuestGraphicsResourceDescriptor.MAX_LAYERS &&
                pixelFormat > 0 &&
                usage >= 0L &&
                producerPid > 0

    fun toGuestDescriptor(
        processNamespace: Long,
        syncSequence: Long = 0L,
    ): GuestGraphicsResourceDescriptor {
        require(structurallyValid) {
            "HOST_GRAPHICS_RESOURCE_LEASE_INVALID"
        }
        require(processNamespace > 0L) {
            "HOST_GRAPHICS_PROCESS_NAMESPACE_INVALID"
        }
        require(syncSequence >= 0L) {
            "HOST_GRAPHICS_SYNC_SEQUENCE_INVALID"
        }

        return GuestGraphicsResourceDescriptor(
            protocol = GuestGraphicsResourceDescriptorCodec.CURRENT_PROTOCOL,
            resourceId = resourceId,
            generation = generation,
            width = width,
            height = height,
            layers = layers,
            pixelFormat = pixelFormat,
            usage = usage,
            producerPid = producerPid,
            processNamespace = processNamespace,
            syncSequence = syncSequence,
        )
    }
}

data class HostGraphicsBrokerRecord(
    val operation: String,
    val status: String,
    val protocol: Int,
    val resourceId: Long?,
    val generation: Long?,
    val width: Int?,
    val height: Int?,
    val layers: Int?,
    val pixelFormat: Int?,
    val usage: Long?,
    val producerPid: Int?,
    val nativeResult: Int?,
    val raw: String,
) {
    val succeeded: Boolean
        get() =
            protocol == HostGraphicsResourceBroker.PROTOCOL_VERSION &&
                status == "ok"

    fun asLeaseOrNull(): HostGraphicsResourceLease? {
        if (!succeeded || operation != "create") {
            return null
        }

        val lease =
            HostGraphicsResourceLease(
                resourceId = resourceId ?: return null,
                generation = generation ?: return null,
                width = width ?: return null,
                height = height ?: return null,
                layers = layers ?: return null,
                pixelFormat = pixelFormat ?: return null,
                usage = usage ?: return null,
                producerPid = producerPid ?: return null,
            )
        return lease.takeIf {
            it.structurallyValid
        }
    }
}

object HostGraphicsBrokerRecordParser {
    private const val MAX_RECORD_LENGTH = 2_048
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val TOKEN_VALUE = Regex("^[A-Za-z0-9._+-]{1,128}$")

    fun parse(raw: String): HostGraphicsBrokerRecord? {
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
            if (
                separator <= 0 ||
                separator == token.lastIndex
            ) {
                return null
            }
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (
                !FIELD_NAME.matches(key) ||
                !TOKEN_VALUE.matches(value) ||
                key in fields
            ) {
                return null
            }
            fields[key] = value
        }

        if (fields.keys.any { it !in ALLOWED_FIELDS }) {
            return null
        }

        val operation =
            fields["pocketpc-ahb-broker"] ?: return null
        if (operation !in OPERATIONS) {
            return null
        }

        val status = fields["status"] ?: return null
        val protocol = fields["protocol"]?.toIntOrNull() ?: return null

        fun longField(name: String): Long? =
            fields[name]
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

        fun intField(name: String): Int? =
            fields[name]
                ?.toIntOrNull()

        return HostGraphicsBrokerRecord(
            operation = operation,
            status = status,
            protocol = protocol,
            resourceId = longField("resource_id"),
            generation = longField("generation"),
            width = intField("width"),
            height = intField("height"),
            layers = intField("layers"),
            pixelFormat = intField("pixel_format"),
            usage = longField("usage"),
            producerPid = intField("producer_pid"),
            nativeResult =
                intField("send") ?: intField("allocate"),
            raw = raw,
        )
    }

    private val OPERATIONS =
        setOf("create", "send", "release")

    private val ALLOWED_FIELDS =
        setOf(
            "pocketpc-ahb-broker",
            "status",
            "protocol",
            "resource_id",
            "generation",
            "width",
            "height",
            "layers",
            "pixel_format",
            "usage",
            "producer_pid",
            "send",
            "allocate",
        )
}

object HostGraphicsResourceBroker {
    const val PROTOCOL_VERSION = 1

    private val loadResult: Result<Unit> =
        runCatching {
            System.loadLibrary("pocketpc_runtime")
        }

    private external fun nativeCreateBuffer(
        width: Int,
        height: Int,
    ): String

    private external fun nativeSendBuffer(
        resourceId: Long,
        generation: Long,
        socketFd: Int,
    ): String

    private external fun nativeReleaseBuffer(
        resourceId: Long,
        generation: Long,
    ): String

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    fun createBuffer(
        width: Int,
        height: Int,
    ): HostGraphicsBrokerRecord? {
        if (!nativeHostLoaded) {
            return null
        }
        if (
            width !in 1..GuestGraphicsResourceDescriptor.MAX_DIMENSION ||
            height !in 1..GuestGraphicsResourceDescriptor.MAX_DIMENSION
        ) {
            return null
        }

        return runCatching {
            nativeCreateBuffer(width, height)
        }.getOrNull()
            ?.let(HostGraphicsBrokerRecordParser::parse)
    }

    fun sendBuffer(
        lease: HostGraphicsResourceLease,
        socketFd: Int,
    ): HostGraphicsBrokerRecord? {
        if (
            !nativeHostLoaded ||
            !lease.structurallyValid ||
            socketFd < 0
        ) {
            return null
        }

        return runCatching {
            nativeSendBuffer(
                lease.resourceId,
                lease.generation,
                socketFd,
            )
        }.getOrNull()
            ?.let(HostGraphicsBrokerRecordParser::parse)
    }

    fun releaseBuffer(
        lease: HostGraphicsResourceLease,
    ): HostGraphicsBrokerRecord? {
        if (!nativeHostLoaded || !lease.structurallyValid) {
            return null
        }

        return runCatching {
            nativeReleaseBuffer(
                lease.resourceId,
                lease.generation,
            )
        }.getOrNull()
            ?.let(HostGraphicsBrokerRecordParser::parse)
    }
}
