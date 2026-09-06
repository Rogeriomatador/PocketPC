package dev.pocketpc.core.runtime

import android.content.Context
import java.io.File

data class SubstrateComponent(
    val fileName: String,
    val exists: Boolean,
    val readable: Boolean,
    val executable: Boolean,
)

data class ExecutionSubstrateStatus(
    val nativeLibraryDir: String,
    val packagedHostReady: Boolean,
    val prootReady: Boolean,
    val components: List<SubstrateComponent>,
    val state: String,
)

object ExecutionSubstrateProbe {
    private val prootRequired = listOf(
        "libproot.so",
        "libproot_loader.so",
        "libtalloc.so",
        "libandroid-shmem.so",
    )

    fun inspect(context: Context): ExecutionSubstrateStatus {
        val directory = File(context.applicationInfo.nativeLibraryDir ?: "")
        val host = File(directory, "libpocketpc_runtime.so")
        val components = prootRequired.map { name ->
            val file = File(directory, name)
            SubstrateComponent(
                fileName = name,
                exists = file.isFile,
                readable = file.canRead(),
                executable = file.canExecute(),
            )
        }
        val prootReady = components.all { it.exists && it.readable && it.executable }
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
