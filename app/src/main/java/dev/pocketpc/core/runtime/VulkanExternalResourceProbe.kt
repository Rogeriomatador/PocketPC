package dev.pocketpc.core.runtime

data class VulkanExternalResourceSnapshot(
    val status: String,
    val protocol: Int,
    val vendorId: Long?,
    val deviceId: Long?,
    val instanceExtensionCount: Int?,
    val deviceExtensionCount: Int?,
    val khrExternalMemoryCapabilities: Boolean,
    val khrExternalMemory: Boolean,
    val khrExternalMemoryFd: Boolean,
    val extExternalMemoryDmaBuf: Boolean,
    val androidExternalMemoryAhb: Boolean,
    val khrExternalSemaphore: Boolean,
    val khrExternalSemaphoreFd: Boolean,
    val khrExternalFence: Boolean,
    val khrExternalFenceFd: Boolean,
    val queryExternalBufferProperties: Boolean,
    val opaqueFdQueried: Boolean,
    val opaqueFdImportable: Boolean,
    val opaqueFdExportable: Boolean,
    val ahbQueried: Boolean,
    val ahbImportable: Boolean,
    val ahbExportable: Boolean,
    val dmaBufQueried: Boolean,
    val dmaBufImportable: Boolean,
    val dmaBufExportable: Boolean,
    val raw: String,
) {
    val probeSucceeded: Boolean
        get() =
            status == "ok" &&
                protocol == VulkanExternalResourceProbe.PROTOCOL_VERSION &&
                vendorId != null &&
                deviceId != null &&
                instanceExtensionCount != null &&
                deviceExtensionCount != null

    val opaqueFdMemoryRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrExternalMemoryCapabilities &&
                khrExternalMemory &&
                khrExternalMemoryFd &&
                queryExternalBufferProperties &&
                opaqueFdQueried &&
                opaqueFdImportable

    val dmaBufMemoryRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrExternalMemoryCapabilities &&
                khrExternalMemory &&
                extExternalMemoryDmaBuf &&
                queryExternalBufferProperties &&
                dmaBufQueried &&
                dmaBufImportable

    val ahardwareBufferMemoryRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrExternalMemoryCapabilities &&
                khrExternalMemory &&
                androidExternalMemoryAhb &&
                queryExternalBufferProperties &&
                ahbQueried &&
                ahbImportable

    val fdSynchronizationRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrExternalSemaphore &&
                khrExternalSemaphoreFd &&
                khrExternalFence &&
                khrExternalFenceFd

    val potentialGuestFdTransportRoute: Boolean
        get() =
            (opaqueFdMemoryRouteAdvertised || dmaBufMemoryRouteAdvertised) &&
                fdSynchronizationRouteAdvertised
}

object VulkanExternalResourceProbeParser {
    private const val MAX_RECORD_LENGTH = 4_096
    private const val MAX_EXTENSION_COUNT = 4_096
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")

    fun parse(raw: String): VulkanExternalResourceSnapshot? {
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
            if (!FIELD_NAME.matches(key) || key in fields) {
                return null
            }
            fields[key] = value
        }

        if (fields.keys.any { it !in ALLOWED_FIELDS }) {
            return null
        }

        val status =
            fields["vulkan-external-resource"] ?: return null
        if (!STATUS.matches(status)) {
            return null
        }

        val protocol =
            fields["protocol"]?.toIntOrNull() ?: return null

        fun booleanField(name: String): Boolean? =
            when (fields[name]) {
                "yes" -> true
                "no" -> false
                else -> null
            }

        fun unsignedLong(name: String): Long? =
            fields[name]
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

        fun boundedCount(name: String): Int? =
            fields[name]
                ?.toIntOrNull()
                ?.takeIf { it in 0..MAX_EXTENSION_COUNT }

        val successfulRecord = status == "ok"
        val flags = CAPABILITY_FIELDS.associateWith(::booleanField)

        val vendorId = unsignedLong("vendor_id")
        val deviceId = unsignedLong("device_id")
        val instanceExtensionCount =
            boundedCount("instance_extensions")
        val deviceExtensionCount =
            boundedCount("device_extensions")

        if (
            successfulRecord &&
            (
                vendorId == null ||
                    deviceId == null ||
                    instanceExtensionCount == null ||
                    deviceExtensionCount == null ||
                    flags.values.any { it == null }
                )
        ) {
            return null
        }

        if (
            CAPABILITY_FIELDS.any { name ->
                name in fields && flags[name] == null
            }
        ) {
            return null
        }

        return VulkanExternalResourceSnapshot(
            status = status,
            protocol = protocol,
            vendorId = vendorId,
            deviceId = deviceId,
            instanceExtensionCount = instanceExtensionCount,
            deviceExtensionCount = deviceExtensionCount,
            khrExternalMemoryCapabilities =
                flags["khr_external_memory_capabilities"] ?: false,
            khrExternalMemory =
                flags["khr_external_memory"] ?: false,
            khrExternalMemoryFd =
                flags["khr_external_memory_fd"] ?: false,
            extExternalMemoryDmaBuf =
                flags["ext_external_memory_dma_buf"] ?: false,
            androidExternalMemoryAhb =
                flags["android_external_memory_ahb"] ?: false,
            khrExternalSemaphore =
                flags["khr_external_semaphore"] ?: false,
            khrExternalSemaphoreFd =
                flags["khr_external_semaphore_fd"] ?: false,
            khrExternalFence =
                flags["khr_external_fence"] ?: false,
            khrExternalFenceFd =
                flags["khr_external_fence_fd"] ?: false,
            queryExternalBufferProperties =
                flags["query_external_buffer_properties"] ?: false,
            opaqueFdQueried =
                flags["opaque_fd_queried"] ?: false,
            opaqueFdImportable =
                flags["opaque_fd_importable"] ?: false,
            opaqueFdExportable =
                flags["opaque_fd_exportable"] ?: false,
            ahbQueried =
                flags["ahb_queried"] ?: false,
            ahbImportable =
                flags["ahb_importable"] ?: false,
            ahbExportable =
                flags["ahb_exportable"] ?: false,
            dmaBufQueried =
                flags["dma_buf_queried"] ?: false,
            dmaBufImportable =
                flags["dma_buf_importable"] ?: false,
            dmaBufExportable =
                flags["dma_buf_exportable"] ?: false,
            raw = raw,
        )
    }

    private val CAPABILITY_FIELDS =
        setOf(
            "khr_external_memory_capabilities",
            "khr_external_memory",
            "khr_external_memory_fd",
            "ext_external_memory_dma_buf",
            "android_external_memory_ahb",
            "khr_external_semaphore",
            "khr_external_semaphore_fd",
            "khr_external_fence",
            "khr_external_fence_fd",
            "query_external_buffer_properties",
            "opaque_fd_queried",
            "opaque_fd_importable",
            "opaque_fd_exportable",
            "ahb_queried",
            "ahb_importable",
            "ahb_exportable",
            "dma_buf_queried",
            "dma_buf_importable",
            "dma_buf_exportable",
        )

    private val ALLOWED_FIELDS =
        setOf(
            "vulkan-external-resource",
            "protocol",
            "result",
            "count",
            "vendor_id",
            "device_id",
            "instance_extensions",
            "device_extensions",
        ) + CAPABILITY_FIELDS
}

object VulkanExternalResourceProbe {
    const val PROTOCOL_VERSION = 1

    private val loadResult: Result<Unit> =
        runCatching {
            System.loadLibrary("pocketpc_runtime")
        }

    private external fun nativeProbe(): String

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    fun snapshot(): VulkanExternalResourceSnapshot? {
        if (!nativeHostLoaded) {
            return null
        }

        return runCatching { nativeProbe() }
            .getOrNull()
            ?.let(VulkanExternalResourceProbeParser::parse)
    }
}
