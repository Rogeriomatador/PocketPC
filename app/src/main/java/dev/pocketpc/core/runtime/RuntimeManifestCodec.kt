package dev.pocketpc.core.runtime

import org.json.JSONObject

object RuntimeManifestCodec {
    fun parse(json: String): RuntimeManifest {
        val root = JSONObject(json)
        val schema = root.getInt("schemaVersion")
        return RuntimeManifest(
            schemaVersion = schema,
            id = root.getString("id"),
            name = root.getString("name"),
            version = root.getString("version"),
            architecture = root.getString("architecture"),
            rootfsSha256 = root.getString("rootfsSha256").lowercase(),
            rootfsBytes = root.getLong("rootfsBytes"),
            entrypoint = root.getString("entrypoint"),
            license = root.getString("license"),
            archiveFormat = if (schema >= 2) root.getString("archiveFormat") else "opaque",
            extractedBytesLimit = if (schema >= 2) root.getLong("extractedBytesLimit") else 0L,
            entryLimit = if (schema >= 2) root.getInt("entryLimit") else 0,
        )
    }

    fun encode(manifest: RuntimeManifest): String {
        val root = JSONObject()
            .put("schemaVersion", manifest.schemaVersion)
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("version", manifest.version)
            .put("architecture", manifest.architecture)
            .put("rootfsSha256", manifest.rootfsSha256.lowercase())
            .put("rootfsBytes", manifest.rootfsBytes)
            .put("entrypoint", manifest.entrypoint)
            .put("license", manifest.license)

        if (manifest.schemaVersion >= 2) {
            root
                .put("archiveFormat", manifest.archiveFormat)
                .put("extractedBytesLimit", manifest.extractedBytesLimit)
                .put("entryLimit", manifest.entryLimit)
        }

        return root.toString(2)
    }
}
