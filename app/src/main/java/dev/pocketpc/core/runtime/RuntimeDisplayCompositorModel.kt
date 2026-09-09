package dev.pocketpc.core.runtime

data class RuntimeDisplayCompositorWindow(
    val windowId: Long,
    val parentId: Long,
    val flags: Int,
    val geometry:
        RuntimeBridgeWindowGeometry?,
    val surfaceId: Long?,
    val surfaceGeneration: Long?,
    val frameId: Long,
    val frame:
        RuntimeDisplayFramePixels?,
)

class RuntimeDisplayCompositorModel {
    private val windows =
        LinkedHashMap<
            Long,
            RuntimeDisplayCompositorWindow
        >()

    @Synchronized
    fun snapshot():
        List<RuntimeDisplayCompositorWindow> =
        windows.values.toList()

    @Synchronized
    fun apply(
        step: RuntimeDisplayHostStep,
    ): Result<Unit> =
        runCatching {
            when (
                val event =
                    step.event
            ) {
                is RuntimeDisplayBridgeEvent
                    .WindowCreated -> {
                    require(
                        event.window.windowId !in
                            windows,
                    ) {
                        "DISPLAY_COMPOSITOR_WINDOW_DUPLICATE"
                    }
                    windows[
                        event.window.windowId
                    ] =
                        RuntimeDisplayCompositorWindow(
                            windowId =
                                event.window.windowId,
                            parentId =
                                event.window.parentId,
                            flags =
                                event.window.flags,
                            geometry =
                                event.window.geometry,
                            surfaceId = null,
                            surfaceGeneration =
                                null,
                            frameId = 0L,
                            frame = null,
                        )
                }

                is RuntimeDisplayBridgeEvent
                    .WindowGeometryChanged -> {
                    val previous =
                        windows[
                            event.geometry
                                .windowId
                        ] ?: error(
                            "DISPLAY_COMPOSITOR_WINDOW_MISSING",
                        )
                    windows[
                        event.geometry
                            .windowId
                    ] =
                        previous.copy(
                            geometry =
                                event.geometry,
                        )
                }

                is RuntimeDisplayBridgeEvent
                    .FrameReady -> {
                    val presented =
                        step.presentedFrame
                            ?: error(
                                "DISPLAY_COMPOSITOR_FRAME_MISSING",
                            )
                    require(
                        presented.ready ==
                            event.frame,
                    ) {
                        "DISPLAY_COMPOSITOR_FRAME_IDENTITY_MISMATCH"
                    }

                    val previous =
                        windows[
                            event.frame
                                .windowId
                        ] ?: error(
                            "DISPLAY_COMPOSITOR_WINDOW_MISSING",
                        )
                    require(
                        event.frame.frameId >
                            previous.frameId,
                    ) {
                        "DISPLAY_COMPOSITOR_FRAME_STALE"
                    }

                    windows[
                        event.frame.windowId
                    ] =
                        previous.copy(
                            surfaceId =
                                event.frame
                                    .surfaceId,
                            surfaceGeneration =
                                event.frame
                                    .generation,
                            frameId =
                                event.frame
                                    .frameId,
                            frame =
                                presented.pixels,
                        )
                }

                is RuntimeDisplayBridgeEvent
                    .WindowDestroyed -> {
                    require(
                        windows.remove(
                            event.windowId,
                        ) != null,
                    ) {
                        "DISPLAY_COMPOSITOR_WINDOW_MISSING"
                    }
                }

                is RuntimeDisplayBridgeEvent
                    .SurfaceRequested -> Unit

                is RuntimeDisplayBridgeEvent
                    .Pointer,
                is RuntimeDisplayBridgeEvent
                    .Key,
                is RuntimeDisplayBridgeEvent
                    .FramePresented -> error(
                        "DISPLAY_COMPOSITOR_HOST_EVENT_DIRECTION_INVALID",
                    )
            }
        }

    @Synchronized
    fun clear() {
        windows.clear()
    }
}
