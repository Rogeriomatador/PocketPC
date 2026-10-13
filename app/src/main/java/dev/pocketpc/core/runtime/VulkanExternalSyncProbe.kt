package dev.pocketpc.core.runtime

data class VulkanExternalSyncSnapshot(
    val status: String,
    val protocol: Int,
    val apiMajor: Int?,
    val apiMinor: Int?,
    val vendorId: Long?,
    val deviceId: Long?,
    val externalSemaphoreFdExtension: Boolean,
    val externalSemaphoreQueryFunction: Boolean,
    val opaqueFdSemaphoreQueried: Boolean,
    val opaqueFdSemaphoreImportable: Boolean,
    val opaqueFdSemaphoreExportable: Boolean,
    val opaqueFdSemaphoreCompatible: Boolean,
    val timelineExtension: Boolean,
    val timelineFeatureQueried: Boolean,
    val timelineSupported: Boolean,
    val nativePvs1Candidate: Boolean,
    val raw: String,
) {
    val probeSucceeded: Boolean
        get() =
            status == "ok" &&
                protocol == VulkanExternalSyncProbe.PROTOCOL_VERSION &&
                apiMajor != null &&
                apiMinor != null &&
                vendorId != null &&
                deviceId != null

    val pvs1Candidate: Boolean
        get() =
            probeSucceeded &&
                externalSemaphoreFdExtension &&
                externalSemaphoreQueryFunction &&
                opaqueFdSemaphoreQueried &&
                opaqueFdSemaphoreImportable &&
                opaqueFdSemaphoreExportable &&
                opaqueFdSemaphoreCompatible &&
                timelineFeatureQueried &&
                timelineSupported &&
                nativePvs1Candidate
}

object VulkanExternalSyncProbeParser {
    private const val MAX_RECORD_LENGTH = 4_096
    private val FIELD_NAME = Regex("^[a-z0-9_-]{1,64}$")
    private val STATUS = Regex("^[a-z0-9-]{1,64}$")

    fun parse(raw: String): VulkanExternalSyncSnapshot? {
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

        val fields = linkedMapOf<String, String>()
        for (token in raw.split(';')) {
            val separator = token.indexOf('=')
            if (separator <= 0 || separator == token.lastIndex) return null
            val key = token.substring(0, separator)
            val value = token.substring(separator + 1)
            if (!FIELD_NAME.matches(key) || key in fields) return null
            fields[key] = value
        }
        if (fields.keys.any { it !in ALLOWED_FIELDS }) return null

        val status = fields["vulkan-external-sync"] ?: return null
        if (!STATUS.matches(status)) return null
        val protocol = fields["protocol"]?.toIntOrNull() ?: return null

        fun bool(name: String): Boolean? =
            when (fields[name]) {
                "yes" -> true
                "no" -> false
                else -> null
            }

        fun uint(name: String): Long? =
            fields[name]?.toLongOrNull()?.takeIf { it >= 0L }

        val flags = FLAG_FIELDS.associateWith(::bool)
        if (FLAG_FIELDS.any { it in fields && flags[it] == null }) return null

        val successful = status == "ok"
        if (successful) {
            if (FLAG_FIELDS.any { flags[it] == null }) return null
            if (
                uint("api_major") == null ||
                uint("api_minor") == null ||
                uint("vendor_id") == null ||
                uint("device_id") == null
            ) {
                return null
            }
        }

        val snapshot =
            VulkanExternalSyncSnapshot(
                status = status,
                protocol = protocol,
                apiMajor = uint("api_major")?.toInt(),
                apiMinor = uint("api_minor")?.toInt(),
                vendorId = uint("vendor_id"),
                deviceId = uint("device_id"),
                externalSemaphoreFdExtension =
                    flags["external_semaphore_fd_extension"] ?: false,
                externalSemaphoreQueryFunction =
                    flags["external_semaphore_query_function"] ?: false,
                opaqueFdSemaphoreQueried =
                    flags["opaque_fd_semaphore_queried"] ?: false,
                opaqueFdSemaphoreImportable =
                    flags["opaque_fd_semaphore_importable"] ?: false,
                opaqueFdSemaphoreExportable =
                    flags["opaque_fd_semaphore_exportable"] ?: false,
                opaqueFdSemaphoreCompatible =
                    flags["opaque_fd_semaphore_compatible"] ?: false,
                timelineExtension =
                    flags["timeline_extension"] ?: false,
                timelineFeatureQueried =
                    flags["timeline_feature_queried"] ?: false,
                timelineSupported =
                    flags["timeline_supported"] ?: false,
                nativePvs1Candidate =
                    flags["pvs1_candidate"] ?: false,
                raw = raw,
            )

        if (
            successful &&
            snapshot.nativePvs1Candidate !=
                (
                    snapshot.externalSemaphoreFdExtension &&
                        snapshot.externalSemaphoreQueryFunction &&
                        snapshot.opaqueFdSemaphoreQueried &&
                        snapshot.opaqueFdSemaphoreImportable &&
                        snapshot.opaqueFdSemaphoreExportable &&
                        snapshot.opaqueFdSemaphoreCompatible &&
                        snapshot.timelineFeatureQueried &&
                        snapshot.timelineSupported
                    )
        ) {
            return null
        }

        return snapshot
    }

    private val FLAG_FIELDS =
        setOf(
            "external_semaphore_fd_extension",
            "external_semaphore_query_function",
            "opaque_fd_semaphore_queried",
            "opaque_fd_semaphore_importable",
            "opaque_fd_semaphore_exportable",
            "opaque_fd_semaphore_compatible",
            "timeline_extension",
            "timeline_feature_queried",
            "timeline_supported",
            "pvs1_candidate",
        )

    private val ALLOWED_FIELDS =
        setOf(
            "vulkan-external-sync",
            "protocol",
            "result",
            "count",
            "api_major",
            "api_minor",
            "vendor_id",
            "device_id",
        ) + FLAG_FIELDS
}

object VulkanExternalSyncProbe {
    const val PROTOCOL_VERSION = 1

    private val loadResult: Result<Unit> =
        runCatching {
            System.loadLibrary("pocketpc_runtime")
        }

    private external fun nativeProbe(): String

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    fun snapshot(): VulkanExternalSyncSnapshot? {
        if (!nativeHostLoaded) return null
        return runCatching { nativeProbe() }
            .getOrNull()
            ?.let(VulkanExternalSyncProbeParser::parse)
    }
}
