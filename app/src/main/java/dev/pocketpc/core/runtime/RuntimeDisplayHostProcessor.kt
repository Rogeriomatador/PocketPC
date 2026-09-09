package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File

data class RuntimeDisplayPresentedFrame(
    val ready:
        RuntimeBridgeFrameReady,
    val pixels:
        RuntimeDisplayFramePixels,
)

data class RuntimeDisplayHostStep(
    val event:
        RuntimeDisplayBridgeEvent,
    val presentedFrame:
        RuntimeDisplayPresentedFrame? =
            null,
)

class RuntimeDisplayHostProcessor(
    private val endpoint:
        RuntimeDisplayBridgeEndpoint,
    hostTempDirectory: File,
) : Closeable {
    private val machine =
        RuntimeDisplayBridgeStateMachine(
            endpoint
                .negotiatedCapabilities,
        )
    private val surfaces =
        RuntimeDisplaySurfaceRegistry(
            hostTempDirectory,
        )

    private var pendingFrame:
        RuntimeBridgeFrameReady? =
        null

    @Synchronized
    fun windowsSnapshot():
        List<RuntimeBridgeWindowState> =
        machine.snapshot()

    @Synchronized
    fun surfacesSnapshot():
        List<RuntimeDisplaySurfaceState> =
        surfaces.snapshot()

    @Synchronized
    fun pendingFrame():
        RuntimeBridgeFrameReady? =
        pendingFrame

    @Synchronized
    fun processNext():
        Result<RuntimeDisplayHostStep> =
        runCatching {
            require(
                pendingFrame == null,
            ) {
                "DISPLAY_BRIDGE_FRAME_ACK_PENDING"
            }

            val frame =
                endpoint.readFrame()
                    .getOrThrow()
            val event =
                machine.apply(frame)
                    .getOrThrow()

            var presented:
                RuntimeDisplayPresentedFrame? =
                null

            when (event) {
                is RuntimeDisplayBridgeEvent
                    .SurfaceRequested -> {
                    val surface =
                        surfaces.allocate(
                            event.request,
                        ).getOrThrow()
                    endpoint
                        .sendSurfaceAvailable(
                            surface,
                        )
                }

                is RuntimeDisplayBridgeEvent
                    .FrameReady -> {
                    val pixels =
                        surfaces.readFrame(
                            event.frame,
                        ).getOrElse {
                            runCatching {
                                endpoint
                                    .sendFramePresented(
                                        RuntimeBridgeFramePresented(
                                            windowId =
                                                event.frame
                                                    .windowId,
                                            frameId =
                                                event.frame
                                                    .frameId,
                                            status =
                                                FRAME_STATUS_REJECTED,
                                        ),
                                    )
                            }
                            throw IllegalStateException(
                                "DISPLAY_BRIDGE_FRAME_READ_FAILED:" +
                                    (
                                        it.message
                                            ?: it.javaClass
                                                .simpleName
                                    ),
                                it,
                            )
                        }

                    pendingFrame =
                        event.frame
                    presented =
                        RuntimeDisplayPresentedFrame(
                            ready =
                                event.frame,
                            pixels = pixels,
                        )
                }

                is RuntimeDisplayBridgeEvent
                    .WindowDestroyed -> {
                    surfaces.removeWindow(
                        event.windowId,
                    )
                }

                is RuntimeDisplayBridgeEvent
                    .WindowCreated,
                is RuntimeDisplayBridgeEvent
                    .WindowGeometryChanged,
                is RuntimeDisplayBridgeEvent
                    .Pointer,
                is RuntimeDisplayBridgeEvent
                    .Key,
                is RuntimeDisplayBridgeEvent
                    .FramePresented -> Unit
            }

            RuntimeDisplayHostStep(
                event = event,
                presentedFrame =
                    presented,
            )
        }

    @Synchronized
    fun acknowledgePendingFrame(
        status: Int =
            FRAME_STATUS_PRESENTED,
    ): Result<RuntimeBridgeFrameReady> =
        runCatching {
            require(
                status in
                    FRAME_STATUS_PRESENTED..
                        FRAME_STATUS_MAX,
            ) {
                "DISPLAY_BRIDGE_FRAME_STATUS_INVALID"
            }

            val ready =
                pendingFrame
                    ?: error(
                        "DISPLAY_BRIDGE_FRAME_ACK_MISSING",
                    )

            endpoint.sendFramePresented(
                RuntimeBridgeFramePresented(
                    windowId =
                        ready.windowId,
                    frameId =
                        ready.frameId,
                    status = status,
                ),
            )

            pendingFrame = null
            ready
        }

    fun sendPointer(
        event: RuntimeBridgePointerEvent,
    ) {
        endpoint.sendPointer(event)
    }

    fun sendKey(
        event: RuntimeBridgeKeyEvent,
    ) {
        endpoint.sendKey(event)
    }

    @Synchronized
    override fun close() {
        surfaces.close()
        pendingFrame = null
    }

    companion object {
        const val FRAME_STATUS_PRESENTED =
            0
        const val FRAME_STATUS_REJECTED =
            1
        const val FRAME_STATUS_MAX =
            16
    }
}
