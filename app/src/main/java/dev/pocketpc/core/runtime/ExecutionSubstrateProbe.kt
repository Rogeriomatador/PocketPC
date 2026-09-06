package dev.pocketpc.core.runtime

import android.content.Context
import java.io.File

data class SubstrateComponent(
    val fileName: String,
    val role: String,
    val exists: Boolean,
    val readable: Boolean,
    val executable: Boolean,
    val executableRequired: Boolean,
)

data class ExecutionSubstrateStatus(
    val nativeLibraryDir: String,
    val packagedHostReady: Boolean,
    val prootReady: Boolean,
    val components: List<SubstrateComponent>,
    val state: String,
    val artifactContractApproved: Boolean = false,
    val policyDigestsVerified: Boolean = false,
    val artifactIntegrityVerified: Boolean = false,
    val approvalErrors: List<String> = emptyList(),
)

object ExecutionSubstrateProbe {
    fun inspect(context: Context): ExecutionSubstrateStatus {
        val directory = File(context.applicationInfo.nativeLibraryDir ?: "")
        val host = File(directory, "libpocketpc_runtime.so")
        val hostReady = host.isFile && host.canRead()

        val approvalResult = SubstrateApprovalCodec.read(context)
        val approval = approvalResult.getOrNull()

        val verification = if (approval != null) {
            val (policyVerified, policyErrors) =
                SubstrateApprovalCodec.verifyPolicyDigests(context, approval)

            SubstrateArtifactVerifier.verify(
                nativeLibraryDir = directory,
                approval = approval,
                policyDigestsVerified = policyVerified,
                policyErrors = policyErrors,
            )
        } else {
            SubstrateApprovalVerification(
                approvedByManifest = false,
                policyDigestsVerified = false,
                integrityVerified = false,
                state = "APPROVAL_MANIFEST_LOAD_FAILED",
                errors = listOf(
                    approvalResult.exceptionOrNull()?.message
                        ?: "Não foi possível carregar approval manifest."
                ),
                artifacts = emptyList(),
            )
        }

        val components = if (approval?.approved == true) {
            approval.artifacts.map { artifact ->
                inspectFile(
                    directory = directory,
                    fileName = artifact.fileName,
                    role = artifact.role,
                    executableRequired = artifact.executableRequired,
                )
            }
        } else {
            inspectUnapprovedCandidates(directory)
        }

        val prootReady =
            hostReady &&
                verification.approvedByManifest &&
                verification.policyDigestsVerified &&
                verification.integrityVerified

        return ExecutionSubstrateStatus(
            nativeLibraryDir = directory.path,
            packagedHostReady = hostReady,
            prootReady = prootReady,
            components = components,
            artifactContractApproved = verification.approvedByManifest,
            policyDigestsVerified = verification.policyDigestsVerified,
            artifactIntegrityVerified = verification.integrityVerified,
            approvalErrors = verification.errors,
            state = when {
                !hostReady -> "HOST_NOT_PACKAGED_OR_NOT_EXTRACTED"
                else -> verification.state
            },
        )
    }

    private fun inspectUnapprovedCandidates(
        directory: File,
    ): List<SubstrateComponent> {
        val fixed = listOf(
            inspectFile(
                directory,
                "libproot.so",
                "proot-candidate",
                executableRequired = true,
            ),
            inspectFile(
                directory,
                "libproot_loader.so",
                "loader64-candidate",
                executableRequired = true,
            ),
            inspectFile(
                directory,
                "libandroid-shmem.so",
                "dependency-candidate",
                executableRequired = false,
            ),
        )

        val talloc = directory.listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedBy(File::getName)
            .firstOrNull { file ->
                file.name.startsWith("libtalloc") && file.name.contains(".so")
            }

        return fixed + SubstrateComponent(
            fileName = talloc?.name ?: "libtalloc{artifact-unresolved}.so",
            role = "talloc-candidate",
            exists = talloc != null,
            readable = talloc?.canRead() == true,
            executable = talloc?.canExecute() == true,
            executableRequired = false,
        )
    }

    private fun inspectFile(
        directory: File,
        fileName: String,
        role: String,
        executableRequired: Boolean,
    ): SubstrateComponent {
        val file = File(directory, fileName)
        return SubstrateComponent(
            fileName = fileName,
            role = role,
            exists = file.isFile,
            readable = file.canRead(),
            executable = file.canExecute(),
            executableRequired = executableRequired,
        )
    }
}
