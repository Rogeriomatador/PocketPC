package dev.pocketpc.core.runtime

import android.content.Context

data class NativeHostStatus(
    val loaded: Boolean,
    val probe: String,
    val graphicsProbe: String,
    val nativeLibraryDir: String,
    val hardwareBufferProbe: String =
        "ahardwarebuffer=not-probed",
    val vulkanWsiCapabilityProbe: String =
        "vulkan-wsi-capabilities=not-probed",
    val vulkanExternalResourceProbe: String =
        "vulkan-external-resource=not-probed",
)

object NativeRuntimeHost {
    private val loadResult: Result<Unit> = runCatching {
        System.loadLibrary("pocketpc_runtime")
    }

    private external fun nativeProbe(): String
    private external fun nativeGraphicsProbe(): String
    private external fun nativeHardwareBufferProbe(): String
    private external fun nativeVulkanWsiCapabilityProbe(): String
    private external fun nativeSendHardwareBufferCrossProcessProbe(
        socketFd: Int,
    ): String
    private external fun nativeReceiveHardwareBufferCrossProcessProbe(
        socketFd: Int,
    ): String

    val loaded: Boolean
        get() = loadResult.isSuccess

    fun sendHardwareBufferCrossProcessProbe(
        socketFd: Int,
    ): String =
        if (loaded) {
            runCatching {
                nativeSendHardwareBufferCrossProcessProbe(
                    socketFd,
                )
            }.getOrElse {
                "ahb-xproc-send=jni-failed;error=${it.javaClass.simpleName}"
            }
        } else {
            "ahb-xproc-send=native-host-not-loaded"
        }

    fun receiveHardwareBufferCrossProcessProbe(
        socketFd: Int,
    ): String =
        if (loaded) {
            runCatching {
                nativeReceiveHardwareBufferCrossProcessProbe(
                    socketFd,
                )
            }.getOrElse {
                "ahb-xproc-recv=jni-failed;error=${it.javaClass.simpleName}"
            }
        } else {
            "ahb-xproc-recv=native-host-not-loaded"
        }

    fun status(context: Context): NativeHostStatus {
        val appInfo = context.applicationInfo
        val loaded = loadResult.isSuccess
        val probe = if (loaded) {
            runCatching { nativeProbe() }
                .getOrElse { "native-host=probe-failed;error=${it.javaClass.simpleName}" }
        } else {
            "native-host=load-failed;error=${loadResult.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
        }

        val baseGraphicsProbe = if (loaded) {
            runCatching { nativeGraphicsProbe() }
                .getOrElse { "vulkan=probe-failed;error=${it.javaClass.simpleName}" }
        } else {
            "vulkan=not-probed;native-host-not-loaded"
        }

        val hardwareBufferProbe = if (loaded) {
            runCatching {
                nativeHardwareBufferProbe()
            }.getOrElse {
                "ahardwarebuffer=probe-failed;error=${it.javaClass.simpleName}"
            }
        } else {
            "ahardwarebuffer=not-probed;native-host-not-loaded"
        }

        val vulkanWsiCapabilityProbe = if (loaded) {
            runCatching {
                nativeVulkanWsiCapabilityProbe()
            }.getOrElse {
                "vulkan-wsi-capabilities=probe-failed;error=${it.javaClass.simpleName}"
            }
        } else {
            "vulkan-wsi-capabilities=not-probed;native-host-not-loaded"
        }

        val vulkanExternalResourceProbe = if (loaded) {
            VulkanExternalResourceProbe
                .snapshot()
                ?.raw
                ?: "vulkan-external-resource=probe-failed-or-invalid"
        } else {
            "vulkan-external-resource=not-probed;native-host-not-loaded"
        }

        return NativeHostStatus(
            loaded = loaded,
            probe = probe,
            graphicsProbe =
                baseGraphicsProbe +
                    "\n" +
                    vulkanExternalResourceProbe,
            nativeLibraryDir = appInfo.nativeLibraryDir ?: "indisponível",
            hardwareBufferProbe = hardwareBufferProbe,
            vulkanWsiCapabilityProbe = vulkanWsiCapabilityProbe,
            vulkanExternalResourceProbe = vulkanExternalResourceProbe,
        )
    }
}
