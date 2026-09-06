package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class SubstrateApprovalModelTest {
    @Test
    fun deniedManifestIsValidOnlyWhenEmpty() {
        val approval = SubstrateApproval(
            schemaVersion = 1,
            status = "NOT_APPROVED",
            approved = false,
            sourceLockSha256 = "",
            artifactContractSha256 = "",
            artifactLockSha256 = "",
            artifacts = emptyList(),
            sourceAudit = false,
            elfAudit = false,
            licenseAudit = false,
            deviceAudit = false,
        )

        assertTrue(SubstrateApprovalValidator.errors(approval).isEmpty())
    }

    @Test
    fun rejectsVersionedTallocFilenameAsAndroidPackagingAlias() {
        val approval = approvedTemplate(
            listOf(
                artifact("proot", "libproot.so", executable = true),
                artifact("loader64", "libproot_loader.so", executable = true),
                artifact("libandroid-shmem", "libandroid-shmem.so"),
                artifact("libtalloc", "libtalloc.so.2"),
            )
        )

        val errors = SubstrateApprovalValidator.errors(approval)

        assertTrue(errors.any { it.contains("terminado em .so") || it.contains("talloc") })
    }

    @Test
    fun attestsExactApprovedFiles() {
        val root = Files.createTempDirectory("pocketpc-attest-").toFile()
        try {
            val files = createArtifacts(root)
            val approval = approvedTemplate(
                listOf(
                    approvedArtifact("proot", files.getValue("libproot.so"), true),
                    approvedArtifact("loader64", files.getValue("libproot_loader.so"), true),
                    approvedArtifact("libandroid-shmem", files.getValue("libandroid-shmem.so"), false),
                    approvedArtifact("libtalloc", files.getValue("libtalloc_android.so"), false),
                )
            )

            val result = SubstrateArtifactVerifier.verify(
                nativeLibraryDir = root,
                approval = approval,
                policyDigestsVerified = true,
            )

            assertTrue(result.errors.joinToString(), result.integrityVerified)
            assertTrue(result.policyDigestsVerified)
            assertTrue(result.state == "SUBSTRATE_ARTIFACTS_ATTESTED")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnexpectedSensitiveArtifact() {
        val root = Files.createTempDirectory("pocketpc-attest-extra-").toFile()
        try {
            val files = createArtifacts(root)
            root.resolve("libproot_old.so").writeText("unexpected")

            val approval = approvedTemplate(
                listOf(
                    approvedArtifact("proot", files.getValue("libproot.so"), true),
                    approvedArtifact("loader64", files.getValue("libproot_loader.so"), true),
                    approvedArtifact("libandroid-shmem", files.getValue("libandroid-shmem.so"), false),
                    approvedArtifact("libtalloc", files.getValue("libtalloc_android.so"), false),
                )
            )

            val result = SubstrateArtifactVerifier.verify(
                nativeLibraryDir = root,
                approval = approval,
                policyDigestsVerified = true,
            )

            assertFalse(result.integrityVerified)
            assertTrue(result.errors.any { it.contains("não aprovado") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun policyDigestFailureBlocksArtifactHashingGate() {
        val root = Files.createTempDirectory("pocketpc-attest-policy-").toFile()
        try {
            val files = createArtifacts(root)
            val approval = approvedTemplate(
                listOf(
                    approvedArtifact("proot", files.getValue("libproot.so"), true),
                    approvedArtifact("loader64", files.getValue("libproot_loader.so"), true),
                    approvedArtifact("libandroid-shmem", files.getValue("libandroid-shmem.so"), false),
                    approvedArtifact("libtalloc", files.getValue("libtalloc_android.so"), false),
                )
            )

            val result = SubstrateArtifactVerifier.verify(
                nativeLibraryDir = root,
                approval = approval,
                policyDigestsVerified = false,
                policyErrors = listOf("artifactLockSha256 divergiu."),
            )

            assertFalse(result.integrityVerified)
            assertFalse(result.policyDigestsVerified)
            assertTrue(result.state == "SUBSTRATE_POLICY_DIGEST_MISMATCH")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createArtifacts(root: File): Map<String, File> {
        val values = linkedMapOf(
            "libproot.so" to "proot-binary",
            "libproot_loader.so" to "loader-binary",
            "libandroid-shmem.so" to "shmem-binary",
            "libtalloc_android.so" to "talloc-binary",
        )
        return values.mapValues { (name, content) ->
            root.resolve(name).apply {
                writeText(content)
                if (name == "libproot.so" || name == "libproot_loader.so") {
                    setExecutable(true)
                }
            }
        }
    }

    private fun approvedArtifact(
        role: String,
        file: File,
        executable: Boolean,
    ) = ApprovedSubstrateArtifact(
        role = role,
        fileName = file.name,
        sha256 = sha256(file),
        bytes = file.length(),
        executableRequired = executable,
    )

    private fun artifact(
        role: String,
        fileName: String,
        executable: Boolean = false,
    ) = ApprovedSubstrateArtifact(
        role = role,
        fileName = fileName,
        sha256 = "a".repeat(64),
        bytes = 1,
        executableRequired = executable,
    )

    private fun approvedTemplate(
        artifacts: List<ApprovedSubstrateArtifact>,
    ) = SubstrateApproval(
        schemaVersion = 1,
        status = "APPROVED",
        approved = true,
        sourceLockSha256 = "1".repeat(64),
        artifactContractSha256 = "2".repeat(64),
        artifactLockSha256 = "3".repeat(64),
        artifacts = artifacts,
        sourceAudit = true,
        elfAudit = true,
        licenseAudit = true,
        deviceAudit = true,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
