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
    val archiveFormat: String = "opaque",
    val extractedBytesLimit: Long = 0L,
    val entryLimit: Int = 0,
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
    private val archiveFormats = setOf("tar", "tar.gz")

    fun validate(
        manifest: RuntimeManifest,
        supportedAbis: List<String>,
    ): RuntimeValidation {
        val errors = mutableListOf<String>()

        if (manifest.schemaVersion !in 1..2) errors += "schemaVersion suportado: 1 ou 2."
        if (!idPattern.matches(manifest.id)) errors += "id inválido."
        if (manifest.name.isBlank() || manifest.name.length > 80) errors += "name inválido."
        if (!versionPattern.matches(manifest.version) || manifest.version == "." || manifest.version == "..") {
            errors += "version inválida."
        }
        if (manifest.architecture != "aarch64") errors += "PocketPC aceita somente architecture=aarch64 nesta fase."
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

        if (manifest.schemaVersion == 1) {
            if (manifest.archiveFormat != "opaque") errors += "schema v1 usa archiveFormat=opaque."
        } else {
            if (manifest.archiveFormat !in archiveFormats) {
                errors += "archiveFormat v2 precisa ser tar ou tar.gz."
            }
            if (
                manifest.extractedBytesLimit <= 0L ||
                manifest.extractedBytesLimit > MAX_EXTRACTED_BYTES
            ) {
                errors += "extractedBytesLimit fora do intervalo permitido."
            }
            if (manifest.entryLimit !in 1..MAX_ENTRY_LIMIT) {
                errors += "entryLimit fora do intervalo permitido."
            }
        }

        return RuntimeValidation(errors.isEmpty(), errors)
    }

    fun canExtract(manifest: RuntimeManifest): Boolean =
        manifest.schemaVersion == 2 &&
            manifest.archiveFormat in archiveFormats &&
            manifest.extractedBytesLimit > 0L &&
            manifest.entryLimit > 0

    const val MAX_ROOTFS_BYTES: Long = 16L * 1024L * 1024L * 1024L
    const val MAX_EXTRACTED_BYTES: Long = 64L * 1024L * 1024L * 1024L
    const val MAX_ENTRY_LIMIT: Int = 2_000_000
}
