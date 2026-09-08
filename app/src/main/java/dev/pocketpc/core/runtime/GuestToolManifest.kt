package dev.pocketpc.core.runtime

import org.json.JSONObject

data class GuestToolFile(
    val path: String,
    val sha256: String,
    val bytes: Long,
    val executable: Boolean,
)

data class GuestToolManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val architecture: String,
    val guestRoot: String,
    val entrypoint: String,
    val sourceCommit: String,
    val license: String,
    val files: List<GuestToolFile>,
    val executionMode: String = "native-aarch64",
)

object GuestToolManifestCodec {
    fun parse(json: String): GuestToolManifest {
        val root = JSONObject(json)
        val filesJson = root.getJSONArray("files")
        val files = buildList {
            for (index in 0 until filesJson.length()) {
                val item = filesJson.getJSONObject(index)
                add(
                    GuestToolFile(
                        path = item.getString("path"),
                        sha256 = item.getString("sha256").lowercase(),
                        bytes = item.getLong("bytes"),
                        executable = item.getBoolean("executable"),
                    ),
                )
            }
        }
        return GuestToolManifest(
            schemaVersion = root.getInt("schemaVersion"),
            id = root.getString("id"),
            version = root.getString("version"),
            architecture = root.getString("architecture"),
            guestRoot = root.getString("guestRoot"),
            entrypoint = root.getString("entrypoint"),
            sourceCommit = root.getString("sourceCommit").lowercase(),
            license = root.getString("license"),
            files = files,
            executionMode =
                root.optString(
                    "executionMode",
                    "native-aarch64",
                ),
        )
    }
}

object GuestToolManifestValidator {
    private val idRegex = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val versionRegex = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val sha256Regex = Regex("^[0-9a-f]{64}$")
    private val commitRegex = Regex("^[0-9a-f]{40}$")

    fun errors(manifest: GuestToolManifest): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion != 1) {
            errors += "GUEST_TOOL_SCHEMA_UNSUPPORTED"
        }
        if (!idRegex.matches(manifest.id)) {
            errors += "GUEST_TOOL_ID_INVALID"
        }
        if (!versionRegex.matches(manifest.version)) {
            errors += "GUEST_TOOL_VERSION_INVALID"
        }
        when (manifest.executionMode) {
            "native-aarch64" -> {
                if (manifest.architecture != "aarch64") {
                    errors +=
                        "GUEST_TOOL_ARCHITECTURE_MODE_MISMATCH"
                }
            }
            "box64-x86_64" -> {
                if (manifest.architecture != "x86_64") {
                    errors +=
                        "GUEST_TOOL_ARCHITECTURE_MODE_MISMATCH"
                }
            }
            else ->
                errors +=
                    "GUEST_TOOL_EXECUTION_MODE_UNSUPPORTED"
        }

        val normalizedGuestRoot =
            RuntimeBindPolicy.normalizeGuestPath(manifest.guestRoot)
        if (
            normalizedGuestRoot == null ||
            normalizedGuestRoot != manifest.guestRoot ||
            normalizedGuestRoot != "/opt/pocketpc/" + manifest.id
        ) {
            errors += "GUEST_TOOL_ROOT_INVALID"
        }

        val normalizedEntrypoint = normalizeRelative(manifest.entrypoint)
        if (
            normalizedEntrypoint == null ||
            normalizedEntrypoint != manifest.entrypoint
        ) {
            errors += "GUEST_TOOL_ENTRYPOINT_INVALID"
        }

        if (!commitRegex.matches(manifest.sourceCommit)) {
            errors += "GUEST_TOOL_SOURCE_COMMIT_INVALID"
        }
        if (manifest.license.isBlank() || manifest.license.length > 160) {
            errors += "GUEST_TOOL_LICENSE_INVALID"
        }
        if (manifest.files.isEmpty() || manifest.files.size > 100_000) {
            errors += "GUEST_TOOL_FILE_COUNT_INVALID"
        }

        val seen = HashSet<String>()
        var totalBytes = 0L
        manifest.files.forEach { item ->
            val normalized = normalizeRelative(item.path)
            if (normalized == null || normalized != item.path) {
                errors += "GUEST_TOOL_FILE_PATH_INVALID:" + item.path
            } else if (!seen.add(normalized)) {
                errors += "GUEST_TOOL_FILE_DUPLICATE:" + item.path
            }

            if (!sha256Regex.matches(item.sha256)) {
                errors += "GUEST_TOOL_FILE_SHA256_INVALID:" + item.path
            }
            if (item.bytes <= 0L) {
                errors += "GUEST_TOOL_FILE_BYTES_INVALID:" + item.path
            } else {
                if (Long.MAX_VALUE - totalBytes < item.bytes) {
                    errors += "GUEST_TOOL_TOTAL_BYTES_OVERFLOW"
                } else {
                    totalBytes += item.bytes
                }
            }
        }

        if (totalBytes > 8L * 1024 * 1024 * 1024) {
            errors += "GUEST_TOOL_TOTAL_BYTES_TOO_LARGE"
        }

        val entrypointFile =
            manifest.files.singleOrNull {
                it.path == manifest.entrypoint
            }
        if (entrypointFile == null) {
            errors += "GUEST_TOOL_ENTRYPOINT_NOT_LISTED"
        } else if (!entrypointFile.executable) {
            errors += "GUEST_TOOL_ENTRYPOINT_NOT_EXECUTABLE"
        }

        return errors.distinct()
    }

    internal fun normalizeRelative(raw: String): String? {
        if (
            raw.isBlank() ||
            raw.startsWith("/") ||
            '\u0000' in raw ||
            '\n' in raw ||
            '\\' in raw
        ) {
            return null
        }

        val parts = ArrayList<String>()
        raw.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> parts += part
            }
        }
        return parts.joinToString("/").ifBlank { null }
    }
}
