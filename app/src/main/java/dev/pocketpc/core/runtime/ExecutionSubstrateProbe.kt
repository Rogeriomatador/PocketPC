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
)

object ExecutionSubstrateProbe {
    // This must remain false until an audited artifact lock is committed after
    // source, ELF, license and physical-device review.
    private const val ARTIFACT_CONTRACT_APPROVED = false

    private data class RequiredComponent(
        val fileName: String,
        val role: String,
        val executableRequired: Boolean,
    )

    private val fixedRequired = listOf(
        RequiredComponent("libproot.so", "proot-executable-alias", true),
        RequiredComponent("libproot_loader.so", "arm64-loader-alias", true),
        RequiredComponent("libandroid-shmem.so", "dynamic-dependency", false),
    )

    private val tallocCandidates = listOf(
        "libtalloc.so.2",
        "libtalloc.so",
    )

    fun inspect(context: Context): ExecutionSubstrateStatus {
        val directory = File(context.applicationInfo.nativeLibraryDir ?: "")
        val host = File(directory, "libpocketpc_runtime.so")

        val fixed = fixedRequired.map { required ->
            inspectFile(directory, required)
        }

        val tallocFile = tallocCandidates
            .map { File(directory, it) }
            .firstOrNull(File::isFile)

        val talloc = SubstrateComponent(
            fileName = tallocFile?.name ?: "libtalloc.so{SONAME unresolved}",
            role = "dynamic-dependency-awaiting-ELF-audit",
            exists = tallocFile?.isFile == true,
            readable = tallocFile?.canRead() == true,
            executable = tallocFile?.canExecute() == true,
            executableRequired = false,
        )

        val components = fixed + talloc
        val filesPresent = components.all { component ->
            component.exists &&
                component.readable &&
                (!component.executableRequired || component.executable)
        }
        val hostReady = host.isFile && host.canRead()
        val prootReady = filesPresent && ARTIFACT_CONTRACT_APPROVED

        return ExecutionSubstrateStatus(
            nativeLibraryDir = directory.path,
            packagedHostReady = hostReady,
            prootReady = prootReady,
            components = components,
            artifactContractApproved = ARTIFACT_CONTRACT_APPROVED,
            state = when {
                !hostReady -> "HOST_NOT_PACKAGED_OR_NOT_EXTRACTED"
                !filesPresent -> "PROOT_COMPONENTS_NOT_BUNDLED"
                !ARTIFACT_CONTRACT_APPROVED ->
                    "PROOT_COMPONENTS_PRESENT_ARTIFACT_AUDIT_REQUIRED"
                else -> "PROOT_SUBSTRATE_APPROVED"
            },
        )
    }

    private fun inspectFile(
        directory: File,
        required: RequiredComponent,
    ): SubstrateComponent {
        val file = File(directory, required.fileName)
        return SubstrateComponent(
            fileName = required.fileName,
            role = required.role,
            exists = file.isFile,
            readable = file.canRead(),
            executable = file.canExecute(),
            executableRequired = required.executableRequired,
        )
    }
}
