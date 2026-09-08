package dev.pocketpc.core.runtime

data class RuntimeBridgeWindowState(
    val windowId: Long,
    val parentId: Long,
    val flags: Int,
    val geometry:
        RuntimeBridgeWindowGeometry?,
)

sealed interface RuntimeDisplayBridgeEvent {
    data class WindowCreated(
        val window:
            RuntimeBridgeWindowState,
    ) : RuntimeDisplayBridgeEvent

    data class WindowGeometryChanged(
        val geometry:
            RuntimeBridgeWindowGeometry,
    ) : RuntimeDisplayBridgeEvent

    data class WindowDestroyed(
        val windowId: Long,
    ) : RuntimeDisplayBridgeEvent

    data class Pointer(
        val event:
            RuntimeBridgePointerEvent,
    ) : RuntimeDisplayBridgeEvent

    data class Key(
        val event:
            RuntimeBridgeKeyEvent,
    ) : RuntimeDisplayBridgeEvent

    data class FramePresented(
        val event:
            RuntimeBridgeFramePresented,
    ) : RuntimeDisplayBridgeEvent
}

class RuntimeDisplayBridgeStateMachine(
    private val negotiatedCapabilities: Int,
) {
    private val windows =
        LinkedHashMap<
            Long,
            RuntimeBridgeWindowState
        >()

    private var expectedSequence = 1L

    fun snapshot():
        List<RuntimeBridgeWindowState> =
        windows.values.toList()

    fun apply(
        frame: RuntimeDisplayBridgeFrame,
    ): Result<RuntimeDisplayBridgeEvent> =
        runCatching {
            require(
                frame.sequence ==
                    expectedSequence,
            ) {
                "DISPLAY_BRIDGE_SEQUENCE_GAP"
            }

            val event =
                when (frame.type) {
                    RuntimeDisplayBridgeMessageType
                        .WINDOW_CREATE ->
                        createWindow(frame)
                    RuntimeDisplayBridgeMessageType
                        .WINDOW_GEOMETRY ->
                        updateGeometry(frame)
                    RuntimeDisplayBridgeMessageType
                        .WINDOW_DESTROY ->
                        destroyWindow(frame)
                    RuntimeDisplayBridgeMessageType
                        .HELLO,
                    RuntimeDisplayBridgeMessageType
                        .HELLO_ACK ->
                        error(
                            "DISPLAY_BRIDGE_HANDSHAKE_ALREADY_COMPLETE",
                        )
                    RuntimeDisplayBridgeMessageType
                        .SURFACE_AVAILABLE,
                    RuntimeDisplayBridgeMessageType
                        .POINTER_EVENT,
                    RuntimeDisplayBridgeMessageType
                        .KEY_EVENT,
                    RuntimeDisplayBridgeMessageType
                        .GAMEPAD_EVENT,
                    RuntimeDisplayBridgeMessageType
                        .FRAME_PRESENTED ->
                        error(
                            "DISPLAY_BRIDGE_MESSAGE_DIRECTION_INVALID:" +
                                frame.type.name,
                        )
                    RuntimeDisplayBridgeMessageType
                        .ERROR ->
                        error(
                            "DISPLAY_BRIDGE_GUEST_ERROR",
                        )
                }

            expectedSequence =
                Math.addExact(
                    expectedSequence,
                    1L,
                )
            event
        }

    private fun createWindow(
        frame: RuntimeDisplayBridgeFrame,
    ): RuntimeDisplayBridgeEvent {
        requireCapability(
            RuntimeDisplayBridgeCapabilities
                .WINDOW_SURFACE,
        )

        val create =
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowCreate(
                    frame.payload,
                )
                .getOrThrow()

        require(
            create.windowId !in windows,
        ) {
            "DISPLAY_BRIDGE_WINDOW_DUPLICATE"
        }
        require(
            windows.size <
                MAX_WINDOWS,
        ) {
            "DISPLAY_BRIDGE_WINDOW_LIMIT"
        }
        if (create.parentId != 0L) {
            require(
                create.parentId in windows,
            ) {
                "DISPLAY_BRIDGE_PARENT_MISSING"
            }
        }

        val state =
            RuntimeBridgeWindowState(
                windowId =
                    create.windowId,
                parentId =
                    create.parentId,
                flags = create.flags,
                geometry = null,
            )
        windows[
            create.windowId
        ] = state

        return RuntimeDisplayBridgeEvent
            .WindowCreated(state)
    }

    private fun updateGeometry(
        frame: RuntimeDisplayBridgeFrame,
    ): RuntimeDisplayBridgeEvent {
        requireCapability(
            RuntimeDisplayBridgeCapabilities
                .WINDOW_SURFACE,
        )
        val geometry =
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowGeometry(
                    frame.payload,
                )
                .getOrThrow()
        val old =
            windows[
                geometry.windowId
            ] ?: error(
                "DISPLAY_BRIDGE_WINDOW_MISSING"
            )
        windows[
            geometry.windowId
        ] =
            old.copy(
                geometry = geometry,
            )
        return RuntimeDisplayBridgeEvent
            .WindowGeometryChanged(
                geometry,
            )
    }

    private fun destroyWindow(
        frame: RuntimeDisplayBridgeFrame,
    ): RuntimeDisplayBridgeEvent {
        requireCapability(
            RuntimeDisplayBridgeCapabilities
                .WINDOW_SURFACE,
        )
        val windowId =
            RuntimeDisplayBridgePayloadCodec
                .decodeWindowDestroy(
                    frame.payload,
                )
                .getOrThrow()
        require(
            windowId in windows,
        ) {
            "DISPLAY_BRIDGE_WINDOW_MISSING"
        }
        require(
            windows.values.none {
                it.parentId ==
                    windowId
            },
        ) {
            "DISPLAY_BRIDGE_WINDOW_HAS_CHILDREN"
        }
        windows.remove(windowId)
        return RuntimeDisplayBridgeEvent
            .WindowDestroyed(
                windowId,
            )
    }




    private fun requireCapability(
        capability: Int,
    ) {
        require(
            negotiatedCapabilities and
                capability !=
                0,
        ) {
            "DISPLAY_BRIDGE_CAPABILITY_NOT_NEGOTIATED"
        }
    }

    companion object {
        const val MAX_WINDOWS = 64
    }
}
