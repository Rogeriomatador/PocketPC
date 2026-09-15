package dev.pocketpc.core.runtime

import org.json.JSONObject

data class WindowsRuntimeLayerFile(
    val path: String,
    val destinationName: String,
    val sha256: String,
    val bytes: Long,
)

data class WindowsRuntimeLayerManifest(
    val schemaVersion: Int,
    val id: String,
    val version: String,
    val sourceCommit: String,
    val license: String,
    val windowsArchitecture: String,
    val targetDirectory: String,
    val files: List<WindowsRuntimeLayerFile>,
)

object WindowsRuntimeLayerManifestCodec {
    fun parse(json: String): WindowsRuntimeLayerManifest {
        val root = JSONObject(json)
        val filesJson = root.getJSONArray("files")
        val files = buildList {
            for (index in 0 until filesJson.length()) {
                val item = filesJson.getJSONObject(index)
                add(
                    WindowsRuntimeLayerFile(
                        path = item.getString("path"),
                        destinationName =
                            item.getString("destinationName"),
                        sha256 =
                            item.getString("sha256").lowercase(),
                        bytes = item.getLong("bytes"),
                    ),
                )
            }
        }
        return WindowsRuntimeLayerManifest(
            schemaVersion = root.getInt("schemaVersion"),
            id = root.getString("id"),
            version = root.getString("version"),
            sourceCommit =
                root.getString("sourceCommit").lowercase(),
            license = root.getString("license"),
            windowsArchitecture =
                root.getString("windowsArchitecture"),
            targetDirectory =
                root.getString("targetDirectory"),
            files = files,
        )
    }
}

object WindowsRuntimeLayerManifestValidator {
    private val idRegex =
        Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val versionRegex =
        Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val sha256Regex =
        Regex("^[0-9a-f]{64}$")
    private val commitRegex =
        Regex("^[0-9a-f]{40}$")
    private val dllRegex =
        Regex("^[A-Za-z0-9._-]{1,96}\\.dll$")

    fun errors(
        manifest: WindowsRuntimeLayerManifest,
    ): List<String> {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion != 1) {
            errors += "WINDOWS_LAYER_SCHEMA_UNSUPPORTED"
        }
        if (!idRegex.matches(manifest.id)) {
            errors += "WINDOWS_LAYER_ID_INVALID"
        }
        if (!versionRegex.matches(manifest.version)) {
            errors += "WINDOWS_LAYER_VERSION_INVALID"
        }
        if (!commitRegex.matches(manifest.sourceCommit)) {
            errors += "WINDOWS_LAYER_SOURCE_COMMIT_INVALID"
        }
        if (
            manifest.license.isBlank() ||
            manifest.license.length > 160
        ) {
            errors += "WINDOWS_LAYER_LICENSE_INVALID"
        }
        if (
            manifest.windowsArchitecture !=
            "x86_64-windows"
        ) {
            errors +=
                "WINDOWS_LAYER_ARCHITECTURE_UNSUPPORTED"
        }
        if (
            manifest.targetDirectory !=
            "drive_c/windows/system32"
        ) {
            errors +=
                "WINDOWS_LAYER_TARGET_DIRECTORY_INVALID"
        }
        if (
            manifest.files.isEmpty() ||
            manifest.files.size > 64
        ) {
            errors += "WINDOWS_LAYER_FILE_COUNT_INVALID"
        }

        val seenPaths = HashSet<String>()
        val seenDestinations = HashSet<String>()
        var totalBytes = 0L

        manifest.files.forEach { item ->
            val normalized =
                GuestToolManifestValidator
                    .normalizeRelative(item.path)
            if (
                normalized == null ||
                normalized != item.path
            ) {
                errors +=
                    "WINDOWS_LAYER_FILE_PATH_INVALID:" +
                        item.path
            } else if (!seenPaths.add(normalized)) {
                errors +=
                    "WINDOWS_LAYER_FILE_DUPLICATE:" +
                        item.path
            }

            if (
                !dllRegex.matches(
                    item.destinationName,
                )
            ) {
                errors +=
                    "WINDOWS_LAYER_DESTINATION_INVALID:" +
                        item.destinationName
            } else if (
                !seenDestinations.add(
                    item.destinationName.lowercase(),
                )
            ) {
                errors +=
                    "WINDOWS_LAYER_DESTINATION_DUPLICATE:" +
                        item.destinationName
            }

            if (!sha256Regex.matches(item.sha256)) {
                errors +=
                    "WINDOWS_LAYER_SHA256_INVALID:" +
                        item.path
            }
            if (item.bytes <= 0L) {
                errors +=
                    "WINDOWS_LAYER_BYTES_INVALID:" +
                        item.path
            } else if (
                Long.MAX_VALUE - totalBytes <
                item.bytes
            ) {
                errors +=
                    "WINDOWS_LAYER_TOTAL_BYTES_OVERFLOW"
            } else {
                totalBytes += item.bytes
            }
        }

        if (
            totalBytes >
            2L * 1024L * 1024L * 1024L
        ) {
            errors +=
                "WINDOWS_LAYER_TOTAL_BYTES_TOO_LARGE"
        }

        return errors.distinct()
    }
}
