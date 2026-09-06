package dev.pocketpc.core.runtime

import java.io.File
import java.security.MessageDigest

data class ApprovedSubstrateArtifact(
    val role: String,
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val executableRequired: Boolean,
)

data class SubstrateApproval(
    val schemaVersion: Int,
    val status: String,
    val approved: Boolean,
    val sourceLockSha256: String,
    val artifactContractSha256: String,
    val artifactLockSha256: String,
    val artifacts: List<ApprovedSubstrateArtifact>,
    val sourceAudit: Boolean,
    val elfAudit: Boolean,
    val licenseAudit: Boolean,
    val deviceAudit: Boolean,
)

data class ArtifactIntegrityRecord(
    val role: String,
    val fileName: String,
    val expectedSha256: String,
    val actualSha256: String?,
    val expectedBytes: Long,
    val actualBytes: Long?,
    val valid: Boolean,
    val message: String,
)

data class SubstrateApprovalVerification(
    val approvedByManifest: Boolean,
    val policyDigestsVerified: Boolean,
    val integrityVerified: Boolean,
    val state: String,
    val errors: List<String>,
    val artifacts: List<ArtifactIntegrityRecord>,
)

object SubstrateApprovalValidator {
    private val requiredRoles = setOf(
        "proot",
        "loader64",
        "libandroid-shmem",
        "libtalloc",
    )
    private val shaRegex = Regex("^[0-9a-f]{64}$")
    private val commonFileRegex = Regex("^[A-Za-z0-9._+-]{1,128}$")
    private val tallocPackagedName = Regex("^libtalloc(?:_[A-Za-z0-9]+)*[.]so$")

    fun errors(approval: SubstrateApproval): List<String> {
        val errors = mutableListOf<String>()

        if (approval.schemaVersion != 1) errors += "approval schema não suportado."

        if (!approval.approved) {
            if (approval.status != "NOT_APPROVED") {
                errors += "approval=false exige status NOT_APPROVED."
            }
            if (approval.artifacts.isNotEmpty()) {
                errors += "approval=false exige artifacts vazio."
            }
            if (
                approval.sourceLockSha256.isNotEmpty() ||
                approval.artifactContractSha256.isNotEmpty() ||
                approval.artifactLockSha256.isNotEmpty()
            ) {
                errors += "approval=false exige digests vazios."
            }
            if (
                approval.sourceAudit ||
                approval.elfAudit ||
                approval.licenseAudit ||
                approval.deviceAudit
            ) {
                errors += "approval=false não pode marcar reviews como concluídas."
            }
            return errors
        }

        if (approval.status != "APPROVED") errors += "approval=true exige status APPROVED."
        if (!shaRegex.matches(approval.sourceLockSha256)) errors += "sourceLockSha256 inválido."
        if (!shaRegex.matches(approval.artifactContractSha256)) {
            errors += "artifactContractSha256 inválido."
        }
        if (!shaRegex.matches(approval.artifactLockSha256)) {
            errors += "artifactLockSha256 inválido."
        }

        if (!approval.sourceAudit) errors += "sourceAudit obrigatório."
        if (!approval.elfAudit) errors += "elfAudit obrigatório."
        if (!approval.licenseAudit) errors += "licenseAudit obrigatório."
        if (!approval.deviceAudit) errors += "deviceAudit obrigatório."

        val roles = approval.artifacts.map { it.role }
        if (roles.toSet() != requiredRoles || roles.size != requiredRoles.size) {
            errors += "roles de artifacts devem ser exatamente $requiredRoles."
        }

        val names = HashSet<String>()
        approval.artifacts.forEach { artifact ->
            if (artifact.role !in requiredRoles) errors += "role inválido: ${artifact.role}"
            if (!commonFileRegex.matches(artifact.fileName)) {
                errors += "fileName inválido: ${artifact.fileName}"
            }
            if (!names.add(artifact.fileName)) {
                errors += "fileName duplicado: ${artifact.fileName}"
            }
            if (!artifact.fileName.endsWith(".so")) {
                errors += "Android native packaging exige alias terminado em .so: ${artifact.role}"
            }
            if (!shaRegex.matches(artifact.sha256)) {
                errors += "SHA-256 inválido: ${artifact.role}"
            }
            if (artifact.bytes <= 0L) errors += "bytes inválidos: ${artifact.role}"

            when (artifact.role) {
                "proot" -> {
                    if (artifact.fileName != "libproot.so") {
                        errors += "proot deve usar alias libproot.so."
                    }
                    if (!artifact.executableRequired) {
                        errors += "proot precisa ser executável."
                    }
                }
                "loader64" -> {
                    if (artifact.fileName != "libproot_loader.so") {
                        errors += "loader64 deve usar alias libproot_loader.so."
                    }
                    if (!artifact.executableRequired) {
                        errors += "loader64 precisa ser executável."
                    }
                }
                "libandroid-shmem" -> {
                    if (artifact.fileName != "libandroid-shmem.so") {
                        errors += "libandroid-shmem deve manter nome libandroid-shmem.so."
                    }
                    if (artifact.executableRequired) {
                        errors += "libandroid-shmem não deve exigir bit executável."
                    }
                }
                "libtalloc" -> {
                    if (!tallocPackagedName.matches(artifact.fileName)) {
                        errors += "alias talloc deve ser Android-packagable lib*.so."
                    }
                    if (artifact.executableRequired) {
                        errors += "libtalloc não deve exigir bit executável."
                    }
                }
            }
        }

        return errors
    }
}

object SubstrateArtifactVerifier {
    fun verify(
        nativeLibraryDir: File,
        approval: SubstrateApproval,
        policyDigestsVerified: Boolean,
        policyErrors: List<String> = emptyList(),
    ): SubstrateApprovalVerification {
        val validationErrors = SubstrateApprovalValidator.errors(approval)
        if (validationErrors.isNotEmpty()) {
            return SubstrateApprovalVerification(
                approvedByManifest = approval.approved,
                policyDigestsVerified = false,
                integrityVerified = false,
                state = "APPROVAL_MANIFEST_INVALID",
                errors = validationErrors,
                artifacts = emptyList(),
            )
        }

        if (!approval.approved) {
            return SubstrateApprovalVerification(
                approvedByManifest = false,
                policyDigestsVerified = false,
                integrityVerified = false,
                state = "SUBSTRATE_NOT_APPROVED",
                errors = emptyList(),
                artifacts = emptyList(),
            )
        }

        if (!policyDigestsVerified) {
            return SubstrateApprovalVerification(
                approvedByManifest = true,
                policyDigestsVerified = false,
                integrityVerified = false,
                state = "SUBSTRATE_POLICY_DIGEST_MISMATCH",
                errors = policyErrors.ifEmpty {
                    listOf("Digests de policy não foram verificados.")
                },
                artifacts = emptyList(),
            )
        }

        val root = runCatching { nativeLibraryDir.canonicalFile }.getOrElse {
            return SubstrateApprovalVerification(
                approvedByManifest = true,
                policyDigestsVerified = true,
                integrityVerified = false,
                state = "NATIVE_LIBRARY_DIR_INVALID",
                errors = listOf(it.message ?: it.javaClass.simpleName),
                artifacts = emptyList(),
            )
        }

        val records = approval.artifacts.map { artifact ->
            verifyArtifact(root, artifact)
        }.toMutableList()

        val expectedSensitiveNames = approval.artifacts.map { it.fileName }.toSet()
        val extras = root.listFiles().orEmpty()
            .filter(File::isFile)
            .map(File::getName)
            .filter(::isSensitiveSubstrateName)
            .filterNot(expectedSensitiveNames::contains)

        extras.forEach { extra ->
            records += ArtifactIntegrityRecord(
                role = "unexpected",
                fileName = extra,
                expectedSha256 = "",
                actualSha256 = null,
                expectedBytes = 0L,
                actualBytes = File(root, extra).length(),
                valid = false,
                message = "Artifact substrate não aprovado presente: $extra",
            )
        }

        val errors = records.filterNot { it.valid }.map { it.message }

        return SubstrateApprovalVerification(
            approvedByManifest = true,
            policyDigestsVerified = true,
            integrityVerified = errors.isEmpty(),
            state = if (errors.isEmpty()) {
                "SUBSTRATE_ARTIFACTS_ATTESTED"
            } else {
                "SUBSTRATE_ARTIFACT_ATTESTATION_FAILED"
            },
            errors = errors,
            artifacts = records,
        )
    }

    private fun verifyArtifact(
        root: File,
        artifact: ApprovedSubstrateArtifact,
    ): ArtifactIntegrityRecord {
        val file = File(root, artifact.fileName)
        val canonical = runCatching { file.canonicalFile }.getOrNull()

        if (
            canonical == null ||
            canonical.parentFile != root ||
            !canonical.isFile ||
            !canonical.canRead()
        ) {
            return record(artifact, null, null, false, "Artifact ausente/inválido: ${artifact.role}")
        }

        if (artifact.executableRequired && !canonical.canExecute()) {
            return record(
                artifact,
                null,
                canonical.length(),
                false,
                "Artifact sem permissão executável: ${artifact.role}",
            )
        }

        val actualBytes = canonical.length()
        if (actualBytes != artifact.bytes) {
            return record(
                artifact,
                null,
                actualBytes,
                false,
                "Tamanho divergiu: ${artifact.role}",
            )
        }

        val actualSha = sha256(canonical)
        val valid = actualSha == artifact.sha256
        return record(
            artifact,
            actualSha,
            actualBytes,
            valid,
            if (valid) "ATTEST_OK" else "SHA-256 divergiu: ${artifact.role}",
        )
    }

    private fun isSensitiveSubstrateName(name: String): Boolean =
        name == "libandroid-shmem.so" ||
            name.startsWith("libproot") ||
            name.startsWith("libtalloc")

    private fun record(
        artifact: ApprovedSubstrateArtifact,
        actualSha256: String?,
        actualBytes: Long?,
        valid: Boolean,
        message: String,
    ) = ArtifactIntegrityRecord(
        role = artifact.role,
        fileName = artifact.fileName,
        expectedSha256 = artifact.sha256,
        actualSha256 = actualSha256,
        expectedBytes = artifact.bytes,
        actualBytes = actualBytes,
        valid = valid,
        message = message,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
