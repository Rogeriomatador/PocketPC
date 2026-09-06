package dev.pocketpc.core.runtime

import android.content.Context

data class NativeHostStatus(
    val loaded: Boolean,
    val probe: String,
    val nativeLibraryDir: String,
)

object NativeRuntimeHost {
    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("pocketpc_runtime")
    }

    private external fun nativeProbe(): String

    fun status(context: Context): NativeHostStatus {
        val appInfo = context.applicationInfo
        val loaded = loadResult.isSuccess
        val probe = if (loaded) {
            runCatching { nativeProbe() }
                .getOrElse { "native-host=probe-failed;error=${it.javaClass.simpleName}" }
        } else {
            "native-host=load-failed;error=${loadResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
        }

        return NativeHostStatus(
            loaded = loaded,
            probe = probe,
            nativeLibraryDir = appInfo.nativeLibraryDir ?: "indisponível",
        )
    }
}
