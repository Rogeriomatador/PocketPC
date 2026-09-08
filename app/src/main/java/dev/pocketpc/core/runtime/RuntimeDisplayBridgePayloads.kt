package dev.pocketpc.core.runtime

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RuntimeBridgeWindowCreate(
    val windowId: Long,
    val parentId: Long,
    val flags: Int,
    val width: Int,
    val height: Int,
)

data class RuntimeBridgeWindowGeometry(
    val windowId: Long,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val visible: Boolean,
    val zOrder: Int,
)

data class RuntimeBridgePointerEvent(
    val windowId: Long,
    val action: Int,
    val x: Int,
    val y: Int,
    val buttons: Int,
    val verticalScroll: Int,
    val modifiers: Int,
)

data class RuntimeBridgeKeyEvent(
    val windowId: Long,
    val action: Int,
    val keyCode: Int,
    val scanCode: Int,
    val modifiers: Int,
    val repeatCount: Int,
)

data class RuntimeBridgeFramePresented(
    val windowId: Long,
    val frameId: Long,
    val status: Int,
)

object RuntimeDisplayBridgePayloadCodec {
    const val WINDOW_CREATE_BYTES = 28
    const val WINDOW_GEOMETRY_BYTES = 32
    const val WINDOW_DESTROY_BYTES = 8
    const val POINTER_EVENT_BYTES = 32
    const val KEY_EVENT_BYTES = 28
    const val FRAME_PRESENTED_BYTES = 20

    private const val MAX_DIMENSION =
        16_384
    private const val MAX_COORDINATE =
        1_000_000

    fun decodeWindowCreate(
        payload: ByteArray,
    ): Result<RuntimeBridgeWindowCreate> =
        runCatching {
            val buffer =
                fixed(
                    payload,
                    WINDOW_CREATE_BYTES,
                )
            val value =
                RuntimeBridgeWindowCreate(
                    windowId = buffer.long,
                    parentId = buffer.long,
                    flags = buffer.int,
                    width = buffer.int,
                    height = buffer.int,
                )
            requireWindowId(
                value.windowId,
            )
            requireParentId(
                value.parentId,
                value.windowId,
            )
            requireDimensions(
                value.width,
                value.height,
            )
            value
        }

    fun decodeWindowGeometry(
        payload: ByteArray,
    ): Result<RuntimeBridgeWindowGeometry> =
        runCatching {
            val buffer =
                fixed(
                    payload,
                    WINDOW_GEOMETRY_BYTES,
                )
            val windowId = buffer.long
            val x = buffer.int
            val y = buffer.int
            val width = buffer.int
            val height = buffer.int
            val visibleRaw = buffer.int
            val zOrder = buffer.int

            requireWindowId(windowId)
            requireCoordinate(x)
            requireCoordinate(y)
            requireDimensions(
                width,
                height,
            )
            require(
                visibleRaw == 0 ||
                    visibleRaw == 1,
            ) {
                "DISPLAY_BRIDGE_VISIBLE_INVALID"
            }

            RuntimeBridgeWindowGeometry(
                windowId = windowId,
                x = x,
                y = y,
                width = width,
                height = height,
                visible =
                    visibleRaw == 1,
                zOrder = zOrder,
            )
        }

    fun decodeWindowDestroy(
        payload: ByteArray,
    ): Result<Long> =
        runCatching {
            val windowId =
                fixed(
                    payload,
                    WINDOW_DESTROY_BYTES,
                ).long
            requireWindowId(windowId)
            windowId
        }

    fun decodePointerEvent(
        payload: ByteArray,
    ): Result<RuntimeBridgePointerEvent> =
        runCatching {
            val buffer =
                fixed(
                    payload,
                    POINTER_EVENT_BYTES,
                )
            val value =
                RuntimeBridgePointerEvent(
                    windowId = buffer.long,
                    action = buffer.int,
                    x = buffer.int,
                    y = buffer.int,
                    buttons = buffer.int,
                    verticalScroll =
                        buffer.int,
                    modifiers = buffer.int,
                )
            requireWindowId(
                value.windowId,
            )
            requireCoordinate(value.x)
            requireCoordinate(value.y)
            require(
                value.action in 0..16,
            ) {
                "DISPLAY_BRIDGE_POINTER_ACTION_INVALID"
            }
            value
        }

    fun decodeKeyEvent(
        payload: ByteArray,
    ): Result<RuntimeBridgeKeyEvent> =
        runCatching {
            val buffer =
                fixed(
                    payload,
                    KEY_EVENT_BYTES,
                )
            val value =
                RuntimeBridgeKeyEvent(
                    windowId = buffer.long,
                    action = buffer.int,
                    keyCode = buffer.int,
                    scanCode = buffer.int,
                    modifiers = buffer.int,
                    repeatCount =
                        buffer.int,
                )
            requireWindowId(
                value.windowId,
            )
            require(
                value.action in 0..4,
            ) {
                "DISPLAY_BRIDGE_KEY_ACTION_INVALID"
            }
            require(
                value.keyCode in
                    0..0xffff,
            ) {
                "DISPLAY_BRIDGE_KEY_CODE_INVALID"
            }
            require(
                value.scanCode in
                    0..0xffff,
            ) {
                "DISPLAY_BRIDGE_SCAN_CODE_INVALID"
            }
            require(
                value.repeatCount in
                    0..10_000,
            ) {
                "DISPLAY_BRIDGE_REPEAT_INVALID"
            }
            value
        }

    fun decodeFramePresented(
        payload: ByteArray,
    ): Result<RuntimeBridgeFramePresented> =
        runCatching {
            val buffer =
                fixed(
                    payload,
                    FRAME_PRESENTED_BYTES,
                )
            val value =
                RuntimeBridgeFramePresented(
                    windowId = buffer.long,
                    frameId = buffer.long,
                    status = buffer.int,
                )
            requireWindowId(
                value.windowId,
            )
            require(
                value.frameId >= 0L,
            ) {
                "DISPLAY_BRIDGE_FRAME_ID_INVALID"
            }
            require(
                value.status in 0..16,
            ) {
                "DISPLAY_BRIDGE_FRAME_STATUS_INVALID"
            }
            value
        }

    private fun fixed(
        payload: ByteArray,
        expected: Int,
    ): ByteBuffer {
        require(
            payload.size == expected,
        ) {
            "DISPLAY_BRIDGE_PAYLOAD_SIZE_INVALID"
        }
        return ByteBuffer
            .wrap(payload)
            .order(
                ByteOrder.LITTLE_ENDIAN,
            )
    }

    private fun requireWindowId(
        windowId: Long,
    ) {
        require(windowId > 0L) {
            "DISPLAY_BRIDGE_WINDOW_ID_INVALID"
        }
    }

    private fun requireParentId(
        parentId: Long,
        windowId: Long,
    ) {
        require(
            parentId >= 0L &&
                parentId != windowId,
        ) {
            "DISPLAY_BRIDGE_PARENT_ID_INVALID"
        }
    }

    private fun requireDimensions(
        width: Int,
        height: Int,
    ) {
        require(
            width in 1..MAX_DIMENSION &&
                height in
                    1..MAX_DIMENSION,
        ) {
            "DISPLAY_BRIDGE_DIMENSION_INVALID"
        }
    }

    private fun requireCoordinate(
        value: Int,
    ) {
        require(
            value in
                -MAX_COORDINATE..
                    MAX_COORDINATE,
        ) {
            "DISPLAY_BRIDGE_COORDINATE_INVALID"
        }
    }
}
