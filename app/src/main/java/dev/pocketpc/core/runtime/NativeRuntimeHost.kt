package dev.pocketpc.core.runtime

import android.content.Context

data class NativeHostStatus(
    val loaded: Boolean,
    val probe: String,
    val graphicsProbe: String,
    val nativeLibraryDir: String,
)

object NativeRuntimeHost {
    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("pocketpc_runtime")
    }

    private external fun nativeProbe(): String
    private external fun nativeGraphicsProbe(): String

    fun status(context: Context): NativeHostStatus {
        val appInfo = context.applicationInfo
        val loaded = loadResult.isSuccess
        val probe = if (loaded) {
            runCatching { nativeProbe() }
                .getOrElse { "native-host=probe-failed;error=${it.javaClass.simpleName}" }
        } else {
            "native-host=load-failed;error=${loadResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
        }

        val graphicsProbe = if (loaded) {
            runCatching { nativeGraphicsProbe() }
                .getOrElse { "vulkan=probe-failed;error=${it.javaClass.simpleName}" }
        } else {
            "vulkan=not-probed;native-host-not-loaded"
        }

        return NativeHostStatus(
            loaded = loaded,
            probe = probe,
            graphicsProbe = graphicsProbe,
            nativeLibraryDir = appInfo.nativeLibraryDir ?: "indisponível",
        )
    }
}
