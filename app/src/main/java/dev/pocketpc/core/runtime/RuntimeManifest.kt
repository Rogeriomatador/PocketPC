package dev.pocketpc.core.runtime

data class RuntimeManifest(
    val schemaVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val architecture: String,
    val rootfsSha256: String,
    val rootfsBytes: Long,
    val entrypoint: String,
    val license: String,
)

data class RuntimeValidation(
    val valid: Boolean,
    val errors: List<String>,
)

object RuntimeManifestValidator {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val sha256Pattern = Regex("^[a-fA-F0-9]{64}$")
    private val absoluteLinuxPath = Regex("^/[A-Za-z0-9._/+@-]{1,255}$")

    fun validate(
        manifest: RuntimeManifest,
        supportedAbis: List<String>,
    ): RuntimeValidation {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion != 1) errors += "schemaVersion precisa ser 1."
        if (!idPattern.matches(manifest.id)) errors += "id inválido."
        if (manifest.name.isBlank() || manifest.name.length > 80) errors += "name inválido."
        if (!versionPattern.matches(manifest.version) || manifest.version == "." || manifest.version == "..") {
            errors += "version inválida."
        }
        if (manifest.architecture != "aarch64") errors += "Alpha 4 aceita somente architecture=aarch64."
        if ("arm64-v8a" !in supportedAbis) errors += "O dispositivo não expõe ABI arm64-v8a."
        if (!sha256Pattern.matches(manifest.rootfsSha256)) errors += "rootfsSha256 inválido."
        if (manifest.rootfsBytes <= 0L || manifest.rootfsBytes > MAX_ROOTFS_BYTES) {
            errors += "rootfsBytes fora do intervalo permitido."
        }
        if (
            !absoluteLinuxPath.matches(manifest.entrypoint) ||
            manifest.entrypoint.split('/').any { it == ".." }
        ) {
            errors += "entrypoint precisa ser caminho Linux absoluto e normalizado."
        }
        if (manifest.license.isBlank() || manifest.license.length > 160) errors += "license inválida."

        return RuntimeValidation(errors.isEmpty(), errors)
    }

    const val MAX_ROOTFS_BYTES: Long = 16L * 1024L * 1024L * 1024L
}
