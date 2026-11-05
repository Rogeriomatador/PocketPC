package dev.pocketpc.core.runtime

data class VulkanWsiCapabilitySnapshot(
    val status: String,
    val protocol: Int,
    val vendorId: Long?,
    val deviceId: Long?,
    val instanceExtensionCount: Int?,
    val deviceExtensionCount: Int?,
    val khrSurface: Boolean,
    val khrAndroidSurface: Boolean,
    val extHeadlessSurface: Boolean,
    val khrExternalMemoryCapabilities: Boolean,
    val khrSwapchain: Boolean,
    val androidExternalMemoryAhb: Boolean,
    val khrExternalMemory: Boolean,
    val khrExternalMemoryFd: Boolean,
    val khrTimelineSemaphore: Boolean,
    val khrSynchronization2: Boolean,
    val raw: String,
) {
    val probeSucceeded: Boolean
        get() =
            status == "ok" &&
                protocol == CURRENT_PROTOCOL &&
                vendorId != null &&
                deviceId != null &&
                instanceExtensionCount != null &&
                deviceExtensionCount != null

    val androidSurfaceRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrSurface &&
                khrAndroidSurface &&
                khrSwapchain

    val headlessSurfaceRouteAdvertised: Boolean
        get() =
            probeSucceeded &&
                khrSurface &&
                extHeadlessSurface &&
                khrSwapchain

    val ahardwareBufferExternalMemoryAdvertised: Boolean
        get() =
            probeSucceeded &&
                androidExternalMemoryAhb

    companion object {
        const val CURRENT_PROTOCOL = 1
    }
}

object VulkanWsiCapabilityProbeParser {
    fun parse(raw: String): VulkanWsiCapabilitySnapshot? {
        if (
            raw.isBlank() ||
            raw.length > MAX_RECORD_LENGTH ||
            raw.any {
                it == '\u0000' ||
                    it == '\r' ||
                    it == '\n'
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

        val status =
            fields["vulkan-wsi-capabilities"]
                ?: return null
        if (!STATUS.matches(status)) {
            return null
        }
        val protocol =
            fields["protocol"]
                ?.toIntOrNull()
                ?: return null

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
        val vendorId = unsignedLong("vendor_id")
        val deviceId = unsignedLong("device_id")
        val instanceExtensionCount =
            boundedCount("instance_extensions")
        val deviceExtensionCount =
            boundedCount("device_extensions")

        val flags =
            CAPABILITY_FIELDS.associateWith(::booleanField)
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
                name in fields &&
                    flags[name] == null
            }
        ) {
            return null
        }

        return VulkanWsiCapabilitySnapshot(
            status = status,
            protocol = protocol,
            vendorId = vendorId,
            deviceId = deviceId,
            instanceExtensionCount = instanceExtensionCount,
            deviceExtensionCount = deviceExtensionCount,
            khrSurface = flags["khr_surface"] ?: false,
            khrAndroidSurface =
                flags["khr_android_surface"] ?: false,
            extHeadlessSurface =
                flags["ext_headless_surface"] ?: false,
            khrExternalMemoryCapabilities =
                flags["khr_external_memory_capabilities"] ?: false,
            khrSwapchain = flags["khr_swapchain"] ?: false,
            androidExternalMemoryAhb =
                flags["android_external_memory_ahb"] ?: false,
            khrExternalMemory =
                flags["khr_external_memory"] ?: false,
            khrExternalMemoryFd =
                flags["khr_external_memory_fd"] ?: false,
            khrTimelineSemaphore =
                flags["khr_timeline_semaphore"] ?: false,
            khrSynchronization2 =
                flags["khr_synchronization2"] ?: false,
            raw = raw,
        )
    }

    private const val MAX_RECORD_LENGTH = 4_096
    private const val MAX_EXTENSION_COUNT = 4_096
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")
    private val CAPABILITY_FIELDS =
        setOf(
            "khr_surface",
            "khr_android_surface",
            "ext_headless_surface",
            "khr_external_memory_capabilities",
            "khr_swapchain",
            "android_external_memory_ahb",
            "khr_external_memory",
            "khr_external_memory_fd",
            "khr_timeline_semaphore",
            "khr_synchronization2",
        )
    private val ALLOWED_FIELDS =
        setOf(
            "vulkan-wsi-capabilities",
            "protocol",
            "result",
            "count",
            "vendor_id",
            "device_id",
            "instance_extensions",
            "device_extensions",
        ) + CAPABILITY_FIELDS
}
