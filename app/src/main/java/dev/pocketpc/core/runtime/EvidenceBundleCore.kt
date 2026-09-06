package dev.pocketpc.core.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class EvidenceBundleEntry(
    val path: String,
    val bytes: ByteArray,
)

data class EvidenceBundleOutput(
    val zipBytes: ByteArray,
    val zipSha256: String,
    val manifestJson: String,
)

object EvidenceBundleCore {
    private val pathRegex = Regex("^[A-Za-z0-9._/-]{1,240}$")

    fun build(
        entries: List<EvidenceBundleEntry>,
        generatedAtUtc: String = Instant.now().toString(),
    ): EvidenceBundleOutput {
        require(entries.isNotEmpty())
        require(entries.size <= MAX_ENTRIES) { "Bundle excedeu o limite de entradas." }
        val totalBytes = entries.sumOf { it.bytes.size.toLong() }
        require(totalBytes <= MAX_TOTAL_BYTES) { "Bundle excedeu o limite de bytes." }
        require(entries.all { it.bytes.size <= MAX_ENTRY_BYTES }) {
            "Bundle contém entrada grande demais."
        }

        val sorted = entries.sortedBy { it.path }
        val names = HashSet<String>()

        sorted.forEach { entry ->
            require(pathRegex.matches(entry.path)) { "Bundle path inválido: ${entry.path}" }
            require(!entry.path.startsWith("/"))
            val parts = entry.path.split('/')
            require(parts.all { it.isNotEmpty() && it != "." && it != ".." })
            require(entry.path != MANIFEST_PATH) {
                "$MANIFEST_PATH é reservado."
            }
            require(names.add(entry.path)) { "Bundle path duplicado: ${entry.path}" }
        }

        val manifest = JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAtUtc", generatedAtUtc)
            .put(
                "entries",
                JSONArray().apply {
                    sorted.forEach { entry ->
                        put(
                            JSONObject()
                                .put("path", entry.path)
                                .put("bytes", entry.bytes.size)
                                .put("sha256", sha256(entry.bytes))
                        )
                    }
                }
            )
        val manifestBytes = manifest.toString(2).toByteArray(Charsets.UTF_8)

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (sorted + EvidenceBundleEntry(MANIFEST_PATH, manifestBytes))
                .forEach { entry ->
                    val zipEntry = ZipEntry(entry.path).apply {
                        time = 0L
                    }
                    zip.putNextEntry(zipEntry)
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
        }

        val zipBytes = output.toByteArray()
        return EvidenceBundleOutput(
            zipBytes = zipBytes,
            zipSha256 = sha256(zipBytes),
            manifestJson = manifest.toString(2),
        )
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    const val MANIFEST_PATH = "bundle-manifest.json"
    private const val MAX_ENTRIES = 32
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
}
