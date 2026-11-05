package dev.pocketpc.core.runtime

/**
 * Host-side receiver for PGA1 stage 6 emitted by the Wine v51 Present bridge.
 *
 * Stage 6 is accepted only after the caller has already consumed stage 5 from
 * the same authenticated GraphicsSeqpacketSessionHost connection. It proves
 * the exact-image copy queue reached completion and that PVI1 was released
 * back to VK_QUEUE_FAMILY_EXTERNAL/GENERAL. It does not prove Android displayed
 * the image and it does not prove Roblox gameplay.
 */
object GraphicsPresentCopyAckHost {
    const val STAGE = 6

    private val loadResult: Result<Unit> =
        runCatching { System.loadLibrary("pocketpc_runtime") }

    private external fun nativeAwaitPresentCopyCompleted(
        fd: Int,
        resourceId: Long,
        generation: Long,
        sequence: Long,
        timeoutMillis: Int,
    ): Int

    val nativeHostLoaded: Boolean
        get() = loadResult.isSuccess

    data class Acknowledgement internal constructor(
        val queueFamilyIndex: Int,
    ) {
        val exactSwapchainPixelsCopied: Boolean
            get() = queueFamilyIndex >= 0
        val returnedToExternalGeneral: Boolean
            get() = exactSwapchainPixelsCopied
        val androidVisibleFrame: Boolean
            get() = false
        val robloxGameplayValidated: Boolean
            get() = false
    }

    fun await(
        connection: GraphicsSeqpacketSessionHost.AcceptedConnection,
        resourceId: Long,
        generation: Long,
        sequence: Long,
        timeoutMillis: Long = GraphicsSeqpacketSessionHost.DEFAULT_AUTH_TIMEOUT_MILLIS,
    ): Acknowledgement? {
        if (!nativeHostLoaded || !connection.valid) return null
        if (resourceId <= 0L || generation <= 0L || sequence <= 0L) return null
        if (
            timeoutMillis !in
                GraphicsSeqpacketSessionHost.MIN_AUTH_TIMEOUT_MILLIS..
                    GraphicsSeqpacketSessionHost.MAX_AUTH_TIMEOUT_MILLIS
        ) {
            return null
        }

        val queueFamily =
            runCatching {
                nativeAwaitPresentCopyCompleted(
                    fd = connection.fd,
                    resourceId = resourceId,
                    generation = generation,
                    sequence = sequence,
                    timeoutMillis = timeoutMillis.toInt(),
                )
            }.getOrNull() ?: return null

        return queueFamily.takeIf { it >= 0 }
            ?.let(::Acknowledgement)
            ?.takeIf {
                it.exactSwapchainPixelsCopied &&
                    it.returnedToExternalGeneral &&
                    !it.androidVisibleFrame &&
                    !it.robloxGameplayValidated
            }
    }
}
