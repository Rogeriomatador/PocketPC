package dev.pocketpc.core.runtime

import org.json.JSONObject

object RuntimeManifestCodec {
    fun parse(json: String): RuntimeManifest {
        val root = JSONObject(json)
        return RuntimeManifest(
            schemaVersion = root.getInt("schemaVersion"),
            id = root.getString("id"),
            name = root.getString("name"),
            version = root.getString("version"),
            architecture = root.getString("architecture"),
            rootfsSha256 = root.getString("rootfsSha256").lowercase(),
            rootfsBytes = root.getLong("rootfsBytes"),
            entrypoint = root.getString("entrypoint"),
            license = root.getString("license"),
        )
    }

    fun encode(manifest: RuntimeManifest): String =
        JSONObject()
            .put("schemaVersion", manifest.schemaVersion)
            .put("id", manifest.id)
            .put("name", manifest.name)
            .put("version", manifest.version)
            .put("architecture", manifest.architecture)
            .put("rootfsSha256", manifest.rootfsSha256.lowercase())
            .put("rootfsBytes", manifest.rootfsBytes)
            .put("entrypoint", manifest.entrypoint)
            .put("license", manifest.license)
            .toString(2)
}
