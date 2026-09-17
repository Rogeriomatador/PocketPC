package dev.pocketpc.core.runtime

data class VulkanAhardwareBufferImportSnapshot(
    val status: String,
    val protocol: Int,
    val apiMajor: Int?,
    val apiMinor: Int?,
    val vendorId: Long?,
    val deviceId: Long?,
    val allocationSize: Long?,
    val memoryTypeBits: Long?,
    val ahbAllocateResult: Int?,
    val propertyQueryResult: Int?,
    val vulkan11OrNewer: Boolean,
    val ahbExtension: Boolean,
    val foreignQueueExtension: Boolean,
    val queueFamilyAvailable: Boolean,
    val deviceCreated: Boolean,
    val queryFunctionAvailable: Boolean,
    val ahbAllocated: Boolean,
    val propertiesQuerySucceeded: Boolean,
    val allocationSizeNonzero: Boolean,
    val memoryTypeBitsNonzero: Boolean,
    val nativeCanonicalImportClaim: Boolean,
    val raw: String,
) {
    val probeSucceeded: Boolean
        get() =
            status == "ok" &&
                protocol == VulkanAhardwareBufferImportProbe.PROTOCOL_VERSION &&
                apiMajor != null &&
                apiMinor != null &&
                vendorId != null &&
                deviceId != null

    val canonicalImportQuerySupported: Boolean
        get() =
            probeSucceeded &&
                vulkan11OrNewer &&
                ahbExtension &&
                foreignQueueExtension &&
                queueFamilyAvailable &&
                deviceCreated &&
                queryFunctionAvailable &&
                ahbAllocated &&
                propertiesQuerySucceeded &&
                allocationSizeNonzero &&
                memoryTypeBitsNonzero &&
                (allocationSize ?: 0L) > 0L &&
                (memoryTypeBits ?: 0L) != 0L &&
                nativeCanonicalImportClaim
}

object VulkanAhardwareBufferImportProbeParser {
    private const val MAX_RECORD_LENGTH = 4_096
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")

    fun parse(raw: String): VulkanAhardwareBufferImportSnapshot? {
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
        if (fields.keys.any { it !in ALLOWED_FIELDS }) {
            return null
        }

        val status = fields["vulkan-ahb-import"] ?: return null
        if (!STATUS.matches(status)) {
            return null
        }
        val protocol = fields["protocol"]?.toIntOrNull() ?: return null

        fun booleanField(name: String): Boolean? =
            when (fields[name]) {
                "yes" -> true
                "no" -> false
                else -> null
            }

        fun nonNegativeInt(name: String): Int? =
            fields[name]
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }

        fun nonNegativeLong(name: String): Long? =
            fields[name]
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

        val flags = FLAG_FIELDS.associateWith(::booleanField)
        if (FLAG_FIELDS.any { it in fields && flags[it] == null }) {
            return null
        }

        val successful = status == "ok"
        if (successful && FLAG_FIELDS.any { flags[it] == null }) {
            return null
        }

        val apiMajor = nonNegativeInt("api_major")
        val apiMinor = nonNegativeInt("api_minor")
        val vendorId = nonNegativeLong("vendor_id")
        val deviceId = nonNegativeLong("device_id")
        val allocationSize = nonNegativeLong("allocation_size")
        val memoryTypeBits = nonNegativeLong("memory_type_bits")
        val ahbAllocateResult = fields["ahb_allocate_result"]?.toIntOrNull()
        val propertyQueryResult = fields["property_query_result"]?.toIntOrNull()

        if (
            successful &&
            (
                apiMajor == null ||
                    apiMinor == null ||
                    vendorId == null ||
                    deviceId == null ||
                    allocationSize == null ||
                    memoryTypeBits == null ||
                    ahbAllocateResult == null ||
                    propertyQueryResult == null
                )
        ) {
            return null
        }

        return VulkanAhardwareBufferImportSnapshot(
            status = status,
            protocol = protocol,
            apiMajor = apiMajor,
            apiMinor = apiMinor,
            vendorId = vendorId,
            deviceId = deviceId,
            allocationSize = allocationSize,
            memoryTypeBits = memoryTypeBits,
            ahbAllocateResult = ahbAllocateResult,
            propertyQueryResult = propertyQueryResult,
            vulkan11OrNewer = flags["vulkan_1_1_or_newer"] ?: false,
            ahbExtension = flags["ahb_extension"] ?: false,
            foreignQueueExtension = flags["foreign_queue_extension"] ?: false,
            queueFamilyAvailable = flags["queue_family_available"] ?: false,
            deviceCreated = flags["device_created"] ?: false,
            queryFunctionAvailable = flags["query_function_available"] ?: false,
            ahbAllocated = flags["ahb_allocated"] ?: false,
            propertiesQuerySucceeded = flags["properties_query_succeeded"] ?: false,
            allocationSizeNonzero = flags["allocation_size_nonzero"] ?: false,
            memoryTypeBitsNonzero = flags["memory_type_bits_nonzero"] ?: false,
            nativeCanonicalImportClaim =
                flags["canonical_import_query_supported"] ?: false,
            raw = raw,
        )
    }

    private val FLAG_FIELDS =
        setOf(
            "vulkan_1_1_or_newer",
            "ahb_extension",
            "foreign_queue_extension",
            "queue_family_available",
            "device_created",
            "query_function_available",
            "ahb_allocated",
            "properties_query_succeeded",
            "allocation_size_nonzero",
            "memory_type_bits_nonzero",
            "canonical_import_query_supported",
        )

    private val ALLOWED_FIELDS =
        setOf(
            "vulkan-ahb-import",
            "protocol",
            "result",
            "count",
            "api_major",
            "api_minor",
            "vendor_id",
            "device_id",
            "allocation_size",
            "memory_type_bits",
            "ahb_allocate_result",
            "property_query_result",
        ) + FLAG_FIELDS
}

object VulkanAhardwareBufferImportProbe {
    const val PROTOCOL_VERSION = 1

    private val loadResult: Result<Unit> =
        runCatching {
            System.loadLibrary("pocketpc_runtime")
        }

    private external fun nativeProbe(): String

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    fun snapshot(): VulkanAhardwareBufferImportSnapshot? {
        if (!nativeHostLoaded) {
            return null
        }
        return runCatching { nativeProbe() }
            .getOrNull()
            ?.let(VulkanAhardwareBufferImportProbeParser::parse)
    }
}
