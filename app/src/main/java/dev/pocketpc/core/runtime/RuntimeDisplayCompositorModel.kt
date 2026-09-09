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
    val topmost: Boolean = false,
    val zIndex: Int = 0,
)

class RuntimeDisplayCompositorModel {
    private val windows =
        LinkedHashMap<
            Long,
            RuntimeDisplayCompositorWindow
        >()
    private val zOrder =
        mutableListOf<Long>()

    @Synchronized
    fun snapshot():
        List<RuntimeDisplayCompositorWindow> =
        zOrder.mapNotNull(
            windows::get,
        )

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
                            topmost = false,
                            zIndex = 0,
                        )
                    insertAtTopOfGroup(
                        event.window.windowId,
                        topmost = false,
                    )
                    refreshZIndices()
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
                    applyZOrder(
                        event.geometry,
                    )
                    refreshZIndices()
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
                    zOrder.remove(
                        event.windowId,
                    )
                    refreshZIndices()
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
        zOrder.clear()
    }

    private fun applyZOrder(
        geometry:
            RuntimeBridgeWindowGeometry,
    ) {
        val id =
            geometry.windowId
        val current =
            windows[id]
                ?: error(
                    "DISPLAY_COMPOSITOR_WINDOW_MISSING",
                )

        when (
            geometry.zOrderFlags
        ) {
            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_NO_CHANGE ->
                return

            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_BOTTOM -> {
                windows[id] =
                    current.copy(
                        topmost = false,
                    )
                zOrder.remove(id)
                zOrder.add(
                    0,
                    id,
                )
            }

            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_TOPMOST -> {
                windows[id] =
                    current.copy(
                        topmost = true,
                    )
                zOrder.remove(id)
                zOrder.add(id)
            }

            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_NOTOPMOST -> {
                windows[id] =
                    current.copy(
                        topmost = false,
                    )
                insertAtTopOfGroup(
                    id,
                    topmost = false,
                )
            }

            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_TOP -> {
                insertAtTopOfGroup(
                    id,
                    topmost =
                        current.topmost,
                )
            }

            RuntimeDisplayBridgePayloadCodec
                .Z_ORDER_AFTER_WINDOW -> {
                val targetId =
                    geometry
                        .insertAfterWindowId
                val target =
                    windows[targetId]
                        ?: error(
                            "DISPLAY_COMPOSITOR_Z_TARGET_MISSING",
                        )

                val desiredTopmost =
                    if (
                        current.topmost &&
                        !target.topmost
                    ) {
                        false
                    } else {
                        current.topmost
                    }
                windows[id] =
                    current.copy(
                        topmost =
                            desiredTopmost,
                    )

                zOrder.remove(id)
                if (
                    desiredTopmost ==
                    target.topmost
                ) {
                    val targetIndex =
                        zOrder.indexOf(
                            targetId,
                        )
                    require(
                        targetIndex >= 0,
                    ) {
                        "DISPLAY_COMPOSITOR_Z_TARGET_MISSING"
                    }
                    zOrder.add(
                        targetIndex,
                        id,
                    )
                } else {
                    insertAtTopOfGroup(
                        id,
                        topmost =
                            desiredTopmost,
                    )
                }
            }

            else ->
                error(
                    "DISPLAY_COMPOSITOR_Z_ORDER_INVALID",
                )
        }
    }

    private fun insertAtTopOfGroup(
        id: Long,
        topmost: Boolean,
    ) {
        zOrder.remove(id)

        if (topmost) {
            zOrder.add(id)
            return
        }

        val firstTopmost =
            zOrder.indexOfFirst {
                windows[it]
                    ?.topmost ==
                    true
            }
        if (firstTopmost < 0) {
            zOrder.add(id)
        } else {
            zOrder.add(
                firstTopmost,
                id,
            )
        }
    }

    private fun refreshZIndices() {
        zOrder.forEachIndexed {
            index,
            id ->
            windows[id]?.let {
                window ->
                windows[id] =
                    window.copy(
                        zIndex = index,
                    )
            }
        }
    }
}
