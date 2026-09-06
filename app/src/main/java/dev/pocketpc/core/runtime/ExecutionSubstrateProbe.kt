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
)

object ExecutionSubstrateProbe {
    private data class RequiredComponent(
        val fileName: String,
        val role: String,
        val executableRequired: Boolean,
    )

    private val prootRequired = listOf(
        RequiredComponent("libproot.so", "proot-executable-alias", true),
        RequiredComponent("libproot_loader.so", "arm64-loader-alias", true),
        RequiredComponent("libtalloc.so", "dynamic-dependency", false),
        RequiredComponent("libandroid-shmem.so", "dynamic-dependency", false),
    )

    fun inspect(context: Context): ExecutionSubstrateStatus {
        val directory = File(context.applicationInfo.nativeLibraryDir ?: "")
        val host = File(directory, "libpocketpc_runtime.so")
        val components = prootRequired.map { required ->
            val file = File(directory, required.fileName)
            SubstrateComponent(
                fileName = required.fileName,
                role = required.role,
                exists = file.isFile,
                readable = file.canRead(),
                executable = file.canExecute(),
                executableRequired = required.executableRequired,
            )
        }

        val prootReady = components.all { component ->
            component.exists &&
                component.readable &&
                (!component.executableRequired || component.executable)
        }
        val hostReady = host.isFile && host.canRead()

        return ExecutionSubstrateStatus(
            nativeLibraryDir = directory.path,
            packagedHostReady = hostReady,
            prootReady = prootReady,
            components = components,
            state = when {
                !hostReady -> "HOST_NOT_PACKAGED_OR_NOT_EXTRACTED"
                prootReady -> "PROOT_COMPONENTS_PRESENT_UNVALIDATED"
                else -> "PROOT_COMPONENTS_NOT_BUNDLED"
            },
        )
    }
}
