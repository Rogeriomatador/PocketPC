package dev.pocketpc.core.update

import dev.pocketpc.core.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val EXPERIMENTAL_RUNTIME_FEED_URL =
    "https://raw.githubusercontent.com/Rogeriomatador/PocketPC-Updates/main/latest.json"

private const val V52_CAPABILITY =
    "pocketpc.vulkan.continuous-present.v52"

data class PocketPcExperimentalRuntimeOffer(
    val kind: String,
    val experimental: Boolean,
    val guestToolVersion: String,
    val pocketPcSourceRevision: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val wineVulkanAbi: Int,
    val capabilities: Set<String>,
)

class PocketPcExperimentalRuntimeCatalog {
    suspend fun fetchPairedV52Offer():
        Result<PocketPcExperimentalRuntimeOffer?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = fetch(EXPERIMENTAL_RUNTIME_FEED_URL)
                parsePocketPcExperimentalRuntimeOffer(
                    raw = raw,
                    expectedPackageName = BuildConfig.APPLICATION_ID,
                    installedPocketPcSourceRevision =
                        BuildConfig.POCKETPC_SOURCE_REVISION,
                    installedPocketPcSourceRevisionPinned =
                        BuildConfig.POCKETPC_SOURCE_REVISION_PINNED,
                )
            }
        }

    private fun fetch(address: String): String {
        val connection =
            URL(address).openConnection()
                as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "PocketPC/" + BuildConfig.VERSION_NAME,
        )

        try {
            val status = connection.responseCode
            require(status in 200..299) {
                "Servidor de atualização respondeu $status."
            }
            require(
                connection.url.protocol.equals(
                    "https",
                    ignoreCase = true,
                )
            ) {
                "EXPERIMENTAL_RUNTIME_FEED_REDIRECT_DOWNGRADE"
            }
            val bytes =
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_FEED_BYTES) {
                            "Manifesto de atualização é grande demais."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_FEED_BYTES = 64 * 1024
    }
}

internal fun parsePocketPcExperimentalRuntimeOffer(
    raw: String,
    expectedPackageName: String,
    installedPocketPcSourceRevision: String,
    installedPocketPcSourceRevisionPinned: Boolean,
): PocketPcExperimentalRuntimeOffer? {
    val root = JSONObject(raw)
    require(root.optInt("schemaVersion", 0) == 1) {
        "EXPERIMENTAL_RUNTIME_FEED_SCHEMA_UNSUPPORTED"
    }
    require(root.getString("packageName") == expectedPackageName) {
        "EXPERIMENTAL_RUNTIME_FEED_PACKAGE_MISMATCH"
    }
    if (!root.optBoolean("published", false)) {
        return null
    }

    val offer = root.optJSONObject("experimentalRuntime")
        ?: return null

    val feedRevision =
        root.optString("sourceRevision", "")
            .trim()
            .lowercase()
    val installedRevision =
        installedPocketPcSourceRevision
            .trim()
            .lowercase()
    val offerRevision =
        offer.optString("pocketPcSourceRevision", "")
            .trim()
            .lowercase()

    val revisionRegex = Regex("^[0-9a-f]{40}$")
    require(installedPocketPcSourceRevisionPinned) {
        "EXPERIMENTAL_RUNTIME_APK_REVISION_NOT_PINNED"
    }
    require(revisionRegex.matches(feedRevision)) {
        "EXPERIMENTAL_RUNTIME_FEED_REVISION_INVALID"
    }
    require(revisionRegex.matches(installedRevision)) {
        "EXPERIMENTAL_RUNTIME_APK_REVISION_INVALID"
    }
    require(revisionRegex.matches(offerRevision)) {
        "EXPERIMENTAL_RUNTIME_OFFER_REVISION_INVALID"
    }
    require(
        feedRevision == installedRevision &&
            offerRevision == installedRevision
    ) {
        "EXPERIMENTAL_RUNTIME_REVISION_MISMATCH"
    }

    val guestToolVersion =
        offer.optString("guestToolVersion", "")
            .trim()
    val url = offer.optString("url", "")
    val sha256 =
        offer.optString("sha256", "")
            .lowercase()
    val bytes = offer.optLong("bytes", -1L)
    require(offer.optString("kind", "") == "wine") {
        "EXPERIMENTAL_RUNTIME_KIND_INVALID"
    }
    require(offer.optBoolean("experimental", false)) {
        "EXPERIMENTAL_RUNTIME_FLAG_MISSING"
    }
    require(
        Regex("^[A-Za-z0-9._+-]{1,128}$")
            .matches(guestToolVersion)
    ) {
        "EXPERIMENTAL_RUNTIME_GUEST_TOOL_VERSION_INVALID"
    }
    require(url.startsWith("https://")) {
        "EXPERIMENTAL_RUNTIME_URL_INVALID"
    }
    require(Regex("^[0-9a-f]{64}$").matches(sha256)) {
        "EXPERIMENTAL_RUNTIME_SHA256_INVALID"
    }
    require(bytes in 1L..MAX_RUNTIME_ARCHIVE_BYTES) {
        "EXPERIMENTAL_RUNTIME_SIZE_INVALID"
    }
    require(offer.optInt("wineVulkanAbi", 0) == 52) {
        "EXPERIMENTAL_RUNTIME_ABI_INVALID"
    }

    val rawCapabilities = offer.optJSONArray("capabilities")
        ?: error("EXPERIMENTAL_RUNTIME_CAPABILITIES_MISSING")
    val capabilities = mutableSetOf<String>()
    for (index in 0 until rawCapabilities.length()) {
        val capability = rawCapabilities.optString(index, "")
        require(
            capability.isNotBlank() &&
                capability.length <= 128 &&
                capabilities.add(capability)
        ) {
            "EXPERIMENTAL_RUNTIME_CAPABILITY_INVALID"
        }
    }
    require(capabilities == setOf(V52_CAPABILITY)) {
        "EXPERIMENTAL_RUNTIME_CAPABILITY_SET_INVALID"
    }

    return PocketPcExperimentalRuntimeOffer(
        kind = "wine",
        experimental = true,
        guestToolVersion = guestToolVersion,
        pocketPcSourceRevision = offerRevision,
        url = url,
        sha256 = sha256,
        bytes = bytes,
        wineVulkanAbi = 52,
        capabilities = capabilities,
    )
}

private const val MAX_RUNTIME_ARCHIVE_BYTES =
    8L * 1024L * 1024L * 1024L
