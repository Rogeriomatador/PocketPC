package dev.pocketpc.core.runtime

data class VulkanWsiCapabilitySnapshot(
    val status: String,
    val protocol: Int,
    val vendorId: Long?,
    val deviceId: Long?,
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
                protocol == CURRENT_PROTOCOL

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

        fun bool(name: String): Boolean =
            when (fields[name]) {
                "yes" -> true
                "no", null -> false
                else -> return false
            }

        fun unsignedLong(name: String): Long? =
            fields[name]
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

        return VulkanWsiCapabilitySnapshot(
            status = status,
            protocol = protocol,
            vendorId = unsignedLong("vendor_id"),
            deviceId = unsignedLong("device_id"),
            khrSurface = bool("khr_surface"),
            khrAndroidSurface = bool("khr_android_surface"),
            extHeadlessSurface = bool("ext_headless_surface"),
            khrExternalMemoryCapabilities =
                bool("khr_external_memory_capabilities"),
            khrSwapchain = bool("khr_swapchain"),
            androidExternalMemoryAhb =
                bool("android_external_memory_ahb"),
            khrExternalMemory =
                bool("khr_external_memory"),
            khrExternalMemoryFd =
                bool("khr_external_memory_fd"),
            khrTimelineSemaphore =
                bool("khr_timeline_semaphore"),
            khrSynchronization2 =
                bool("khr_synchronization2"),
            raw = raw,
        )
    }

    private const val MAX_RECORD_LENGTH = 4_096
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")
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
}
