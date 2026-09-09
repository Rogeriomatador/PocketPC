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
    val zOrderFlags: Int,
    val insertAfterWindowId: Long,
)

data class RuntimeBridgeSurfaceRequest(
    val windowId: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: Int,
    val flags: Int,
)

data class RuntimeBridgeSurfaceAvailable(
    val windowId: Long,
    val surfaceId: Long,
    val generation: Long,
    val width: Int,
    val height: Int,
    val strideBytes: Int,
    val pixelFormat: Int,
    val tokenHex: String,
) {
    val guestPath: String
        get() =
            "/tmp/.pocketpc-surface-" +
                tokenHex +
                ".bgra"
}

data class RuntimeBridgeFrameReady(
    val windowId: Long,
    val surfaceId: Long,
    val generation: Long,
    val frameId: Long,
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

data class RuntimeBridgeWindowCommand(
    val windowId: Long,
    val command: Int,
    val flags: Int = 0,
)

object RuntimeDisplayBridgePayloadCodec {
    const val WINDOW_CREATE_BYTES = 28
    const val WINDOW_GEOMETRY_BYTES = 40
    const val WINDOW_DESTROY_BYTES = 8
    const val WINDOW_COMMAND_BYTES = 16
    const val SURFACE_REQUEST_BYTES = 32
    const val SURFACE_AVAILABLE_BYTES = 56
    const val FRAME_READY_BYTES = 32
    const val POINTER_EVENT_BYTES = 32
    const val KEY_EVENT_BYTES = 28
    const val FRAME_PRESENTED_BYTES = 20

    const val PIXEL_FORMAT_BGRA8888 = 1

    const val POINTER_ACTION_MOVE = 0
    const val POINTER_ACTION_DOWN = 1
    const val POINTER_ACTION_UP = 2
    const val POINTER_ACTION_SCROLL = 3

    const val POINTER_BUTTON_PRIMARY = 1 shl 0
    const val POINTER_BUTTON_SECONDARY = 1 shl 1
    const val POINTER_BUTTON_MIDDLE = 1 shl 2
    private const val POINTER_BUTTON_ALLOWED_MASK =
        POINTER_BUTTON_PRIMARY or
            POINTER_BUTTON_SECONDARY or
            POINTER_BUTTON_MIDDLE

    const val KEY_ACTION_DOWN = 1
    const val KEY_ACTION_UP = 2
    const val KEY_ACTION_REPEAT = 3

    const val MODIFIER_SHIFT = 1 shl 0
    const val MODIFIER_CONTROL = 1 shl 1
    const val MODIFIER_ALT = 1 shl 2
    const val MODIFIER_META = 1 shl 3
    private const val MODIFIER_ALLOWED_MASK =
        MODIFIER_SHIFT or
            MODIFIER_CONTROL or
            MODIFIER_ALT or
            MODIFIER_META

    const val WINDOW_COMMAND_ACTIVATE = 1
    const val WINDOW_COMMAND_MINIMIZE = 2
    const val WINDOW_COMMAND_RESTORE = 3
    const val WINDOW_COMMAND_MAXIMIZE = 4
    const val WINDOW_COMMAND_CLOSE = 5

    const val Z_ORDER_NO_CHANGE = 1 shl 0
    const val Z_ORDER_TOP = 1 shl 1
    const val Z_ORDER_BOTTOM = 1 shl 2
    const val Z_ORDER_TOPMOST = 1 shl 3
    const val Z_ORDER_NOTOPMOST = 1 shl 4
    const val Z_ORDER_AFTER_WINDOW = 1 shl 5

    private const val Z_ORDER_ALLOWED_MASK =
        Z_ORDER_NO_CHANGE or
            Z_ORDER_TOP or
            Z_ORDER_BOTTOM or
            Z_ORDER_TOPMOST or
            Z_ORDER_NOTOPMOST or
            Z_ORDER_AFTER_WINDOW

    private const val MAX_DIMENSION = 16_384
    private const val MAX_COORDINATE = 1_000_000
    private const val SURFACE_TOKEN_BYTES = 16
    private val tokenRegex =
        Regex("^[0-9a-f]{32}$")

    fun encodeSurfaceRequest(
        value: RuntimeBridgeSurfaceRequest,
    ): ByteArray {
        validateSurfaceRequest(value)
        return buffer(
            SURFACE_REQUEST_BYTES,
        ).apply {
            putLong(value.windowId)
            putLong(value.generation)
            putInt(value.width)
            putInt(value.height)
            putInt(value.pixelFormat)
            putInt(value.flags)
        }.array()
    }

    fun decodeSurfaceRequest(
        payload: ByteArray,
    ): Result<RuntimeBridgeSurfaceRequest> =
        runCatching {
            val b =
                fixed(
                    payload,
                    SURFACE_REQUEST_BYTES,
                )
            RuntimeBridgeSurfaceRequest(
                windowId = b.long,
                generation = b.long,
                width = b.int,
                height = b.int,
                pixelFormat = b.int,
                flags = b.int,
            ).also(
                ::validateSurfaceRequest,
            )
        }

    fun encodeSurfaceAvailable(
        value: RuntimeBridgeSurfaceAvailable,
    ): ByteArray {
        validateSurface(value)
        val token =
            decodeHexToken(
                value.tokenHex,
            )
        return buffer(
            SURFACE_AVAILABLE_BYTES,
        ).apply {
            putLong(value.windowId)
            putLong(value.surfaceId)
            putLong(value.generation)
            putInt(value.width)
            putInt(value.height)
            putInt(value.strideBytes)
            putInt(value.pixelFormat)
            put(token)
        }.array()
    }

    fun decodeSurfaceAvailable(
        payload: ByteArray,
    ): Result<RuntimeBridgeSurfaceAvailable> =
        runCatching {
            val b =
                fixed(
                    payload,
                    SURFACE_AVAILABLE_BYTES,
                )
            val token =
                ByteArray(
                    SURFACE_TOKEN_BYTES,
                )
            val value =
                RuntimeBridgeSurfaceAvailable(
                    windowId = b.long,
                    surfaceId = b.long,
                    generation = b.long,
                    width = b.int,
                    height = b.int,
                    strideBytes = b.int,
                    pixelFormat = b.int,
                    tokenHex =
                        run {
                            b.get(token)
                            token.toHex()
                        },
                )
            validateSurface(value)
            value
        }

    fun encodeFrameReady(
        value: RuntimeBridgeFrameReady,
    ): ByteArray {
        validateFrameReady(value)
        return buffer(
            FRAME_READY_BYTES,
        ).apply {
            putLong(value.windowId)
            putLong(value.surfaceId)
            putLong(value.generation)
            putLong(value.frameId)
        }.array()
    }

    fun decodeFrameReady(
        payload: ByteArray,
    ): Result<RuntimeBridgeFrameReady> =
        runCatching {
            val b =
                fixed(
                    payload,
                    FRAME_READY_BYTES,
                )
            RuntimeBridgeFrameReady(
                windowId = b.long,
                surfaceId = b.long,
                generation = b.long,
                frameId = b.long,
            ).also(
                ::validateFrameReady,
            )
        }

    fun encodePointerEvent(
        value: RuntimeBridgePointerEvent,
    ): ByteArray {
        requireWindowId(value.windowId)
        requireCoordinate(value.x)
        requireCoordinate(value.y)
        require(
            value.action in
                POINTER_ACTION_MOVE..
                    POINTER_ACTION_SCROLL,
        ) {
            "DISPLAY_BRIDGE_POINTER_ACTION_INVALID"
        }
        require(
            value.buttons and
                POINTER_BUTTON_ALLOWED_MASK ==
                value.buttons,
        ) {
            "DISPLAY_BRIDGE_POINTER_BUTTON_MASK_INVALID"
        }
        require(
            value.modifiers and
                MODIFIER_ALLOWED_MASK ==
                value.modifiers,
        ) {
            "DISPLAY_BRIDGE_POINTER_MODIFIER_MASK_INVALID"
        }
        return buffer(
            POINTER_EVENT_BYTES,
        ).apply {
            putLong(value.windowId)
            putInt(value.action)
            putInt(value.x)
            putInt(value.y)
            putInt(value.buttons)
            putInt(value.verticalScroll)
            putInt(value.modifiers)
        }.array()
    }

    fun encodeKeyEvent(
        value: RuntimeBridgeKeyEvent,
    ): ByteArray {
        requireWindowId(value.windowId)
        require(
            value.action in
                KEY_ACTION_DOWN..
                    KEY_ACTION_REPEAT,
        ) {
            "DISPLAY_BRIDGE_KEY_ACTION_INVALID"
        }
        require(
            value.keyCode in 0..0xffff,
        ) {
            "DISPLAY_BRIDGE_KEY_CODE_INVALID"
        }
        require(
            value.scanCode in 0..0xffff,
        ) {
            "DISPLAY_BRIDGE_SCAN_CODE_INVALID"
        }
        require(
            value.modifiers and
                MODIFIER_ALLOWED_MASK ==
                value.modifiers,
        ) {
            "DISPLAY_BRIDGE_MODIFIER_MASK_INVALID"
        }
        require(
            value.repeatCount in
                0..10_000,
        ) {
            "DISPLAY_BRIDGE_REPEAT_INVALID"
        }
        return buffer(
            KEY_EVENT_BYTES,
        ).apply {
            putLong(value.windowId)
            putInt(value.action)
            putInt(value.keyCode)
            putInt(value.scanCode)
            putInt(value.modifiers)
            putInt(value.repeatCount)
        }.array()
    }

    fun encodeWindowCommand(
        value: RuntimeBridgeWindowCommand,
    ): ByteArray {
        validateWindowCommand(value)
        return buffer(
            WINDOW_COMMAND_BYTES,
        ).apply {
            putLong(value.windowId)
            putInt(value.command)
            putInt(value.flags)
        }.array()
    }

    fun decodeWindowCommand(
        payload: ByteArray,
    ): Result<RuntimeBridgeWindowCommand> =
        runCatching {
            val b =
                fixed(
                    payload,
                    WINDOW_COMMAND_BYTES,
                )
            RuntimeBridgeWindowCommand(
                windowId = b.long,
                command = b.int,
                flags = b.int,
            ).also(
                ::validateWindowCommand,
            )
        }

    fun encodeFramePresented(
        value: RuntimeBridgeFramePresented,
    ): ByteArray {
        requireWindowId(value.windowId)
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
        return buffer(
            FRAME_PRESENTED_BYTES,
        ).apply {
            putLong(value.windowId)
            putLong(value.frameId)
            putInt(value.status)
        }.array()
    }

    fun decodeWindowCreate(
        payload: ByteArray,
    ): Result<RuntimeBridgeWindowCreate> =
        runCatching {
            val b =
                fixed(
                    payload,
                    WINDOW_CREATE_BYTES,
                )
            RuntimeBridgeWindowCreate(
                b.long,
                b.long,
                b.int,
                b.int,
                b.int,
            ).also {
                requireWindowId(it.windowId)
                requireParentId(
                    it.parentId,
                    it.windowId,
                )
                requireDimensions(
                    it.width,
                    it.height,
                )
            }
        }

    fun decodeWindowGeometry(
        payload: ByteArray,
    ): Result<RuntimeBridgeWindowGeometry> =
        runCatching {
            val b =
                fixed(
                    payload,
                    WINDOW_GEOMETRY_BYTES,
                )
            val id = b.long
            val x = b.int
            val y = b.int
            val w = b.int
            val h = b.int
            val visible = b.int
            val zOrderFlags = b.int
            val insertAfterWindowId = b.long
            requireWindowId(id)
            requireCoordinate(x)
            requireCoordinate(y)
            requireDimensions(w, h)
            require(
                visible == 0 ||
                    visible == 1,
            ) {
                "DISPLAY_BRIDGE_VISIBLE_INVALID"
            }
            validateZOrder(
                windowId = id,
                flags = zOrderFlags,
                insertAfterWindowId =
                    insertAfterWindowId,
            )
            RuntimeBridgeWindowGeometry(
                windowId = id,
                x = x,
                y = y,
                width = w,
                height = h,
                visible = visible == 1,
                zOrderFlags = zOrderFlags,
                insertAfterWindowId =
                    insertAfterWindowId,
            )
        }

    fun decodeWindowDestroy(
        payload: ByteArray,
    ): Result<Long> =
        runCatching {
            fixed(
                payload,
                WINDOW_DESTROY_BYTES,
            ).long.also(
                ::requireWindowId,
            )
        }

    fun decodePointerEvent(
        payload: ByteArray,
    ): Result<RuntimeBridgePointerEvent> =
        runCatching {
            val b =
                fixed(
                    payload,
                    POINTER_EVENT_BYTES,
                )
            RuntimeBridgePointerEvent(
                b.long,
                b.int,
                b.int,
                b.int,
                b.int,
                b.int,
                b.int,
            ).also {
                requireWindowId(
                    it.windowId,
                )
                requireCoordinate(it.x)
                requireCoordinate(it.y)
                require(
                    it.action in
                        POINTER_ACTION_MOVE..
                            POINTER_ACTION_SCROLL,
                ) {
                    "DISPLAY_BRIDGE_POINTER_ACTION_INVALID"
                }
                require(
                    it.buttons and
                        POINTER_BUTTON_ALLOWED_MASK ==
                        it.buttons,
                ) {
                    "DISPLAY_BRIDGE_POINTER_BUTTON_MASK_INVALID"
                }
                require(
                    it.modifiers and
                        MODIFIER_ALLOWED_MASK ==
                        it.modifiers,
                ) {
                    "DISPLAY_BRIDGE_POINTER_MODIFIER_DECODE_INVALID"
                }
            }
        }

    fun decodeKeyEvent(
        payload: ByteArray,
    ): Result<RuntimeBridgeKeyEvent> =
        runCatching {
            val b =
                fixed(
                    payload,
                    KEY_EVENT_BYTES,
                )
            RuntimeBridgeKeyEvent(
                b.long,
                b.int,
                b.int,
                b.int,
                b.int,
                b.int,
            ).also {
                requireWindowId(
                    it.windowId,
                )
                require(
                    it.action in
                        KEY_ACTION_DOWN..
                            KEY_ACTION_REPEAT,
                ) {
                    "DISPLAY_BRIDGE_KEY_ACTION_INVALID"
                }
                require(
                    it.keyCode in
                        0..0xffff,
                ) {
                    "DISPLAY_BRIDGE_KEY_CODE_INVALID"
                }
                require(
                    it.scanCode in
                        0..0xffff,
                ) {
                    "DISPLAY_BRIDGE_SCAN_CODE_INVALID"
                }
                require(
                    it.modifiers and
                        MODIFIER_ALLOWED_MASK ==
                        it.modifiers,
                ) {
                    "DISPLAY_BRIDGE_KEY_MODIFIER_MASK_INVALID"
                }
                require(
                    it.repeatCount in
                        0..10_000,
                ) {
                    "DISPLAY_BRIDGE_REPEAT_INVALID"
                }
            }
        }

    fun decodeFramePresented(
        payload: ByteArray,
    ): Result<RuntimeBridgeFramePresented> =
        runCatching {
            val b =
                fixed(
                    payload,
                    FRAME_PRESENTED_BYTES,
                )
            RuntimeBridgeFramePresented(
                b.long,
                b.long,
                b.int,
            ).also {
                requireWindowId(
                    it.windowId,
                )
                require(
                    it.frameId >= 0L,
                ) {
                    "DISPLAY_BRIDGE_FRAME_ID_INVALID"
                }
                require(
                    it.status in 0..16,
                ) {
                    "DISPLAY_BRIDGE_FRAME_STATUS_INVALID"
                }
            }
        }

    private fun validateWindowCommand(
        value: RuntimeBridgeWindowCommand,
    ) {
        requireWindowId(value.windowId)
        require(
            value.command in
                WINDOW_COMMAND_ACTIVATE..
                    WINDOW_COMMAND_CLOSE,
        ) {
            "DISPLAY_BRIDGE_WINDOW_COMMAND_INVALID"
        }
        require(value.flags == 0) {
            "DISPLAY_BRIDGE_WINDOW_COMMAND_FLAGS_INVALID"
        }
    }

    private fun validateSurfaceRequest(
        value: RuntimeBridgeSurfaceRequest,
    ) {
        requireWindowId(value.windowId)
        require(value.generation > 0L) {
            "DISPLAY_BRIDGE_SURFACE_GENERATION_INVALID"
        }
        requireDimensions(
            value.width,
            value.height,
        )
        require(
            value.pixelFormat ==
                PIXEL_FORMAT_BGRA8888,
        ) {
            "DISPLAY_BRIDGE_PIXEL_FORMAT_INVALID"
        }
        require(value.flags == 0) {
            "DISPLAY_BRIDGE_SURFACE_FLAGS_INVALID"
        }
    }

    private fun validateSurface(
        value: RuntimeBridgeSurfaceAvailable,
    ) {
        requireWindowId(value.windowId)
        require(value.surfaceId > 0L) {
            "DISPLAY_BRIDGE_SURFACE_ID_INVALID"
        }
        require(value.generation > 0L) {
            "DISPLAY_BRIDGE_SURFACE_GENERATION_INVALID"
        }
        requireDimensions(
            value.width,
            value.height,
        )
        require(
            value.strideBytes >=
                value.width * 4 &&
                value.strideBytes <=
                MAX_DIMENSION * 4,
        ) {
            "DISPLAY_BRIDGE_SURFACE_STRIDE_INVALID"
        }
        require(
            value.pixelFormat ==
                PIXEL_FORMAT_BGRA8888,
        ) {
            "DISPLAY_BRIDGE_PIXEL_FORMAT_INVALID"
        }
        require(
            tokenRegex.matches(
                value.tokenHex,
            ),
        ) {
            "DISPLAY_BRIDGE_SURFACE_TOKEN_INVALID"
        }
    }

    private fun validateFrameReady(
        value: RuntimeBridgeFrameReady,
    ) {
        requireWindowId(value.windowId)
        require(value.surfaceId > 0L) {
            "DISPLAY_BRIDGE_SURFACE_ID_INVALID"
        }
        require(value.generation > 0L) {
            "DISPLAY_BRIDGE_SURFACE_GENERATION_INVALID"
        }
        require(value.frameId > 0L) {
            "DISPLAY_BRIDGE_FRAME_ID_INVALID"
        }
    }

    private fun decodeHexToken(
        tokenHex: String,
    ): ByteArray {
        require(
            tokenRegex.matches(
                tokenHex,
            ),
        )
        return ByteArray(
            SURFACE_TOKEN_BYTES,
        ) { index ->
            tokenHex.substring(
                index * 2,
                index * 2 + 2,
            ).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") {
            "%02x".format(
                it.toInt() and 0xff,
            )
        }

    private fun buffer(
        size: Int,
    ): ByteBuffer =
        ByteBuffer.allocate(size)
            .order(
                ByteOrder.LITTLE_ENDIAN,
            )

    private fun fixed(
        payload: ByteArray,
        expected: Int,
    ): ByteBuffer {
        require(
            payload.size == expected,
        ) {
            "DISPLAY_BRIDGE_PAYLOAD_SIZE_INVALID"
        }
        return ByteBuffer.wrap(payload)
            .order(
                ByteOrder.LITTLE_ENDIAN,
            )
    }

    private fun validateZOrder(
        windowId: Long,
        flags: Int,
        insertAfterWindowId: Long,
    ) {
        require(
            flags != 0 &&
                flags and
                    Z_ORDER_ALLOWED_MASK ==
                flags &&
                Integer.bitCount(flags) == 1,
        ) {
            "DISPLAY_BRIDGE_Z_ORDER_FLAGS_INVALID"
        }

        if (
            flags ==
            Z_ORDER_AFTER_WINDOW
        ) {
            requireWindowId(
                insertAfterWindowId,
            )
            require(
                insertAfterWindowId !=
                    windowId,
            ) {
                "DISPLAY_BRIDGE_Z_ORDER_SELF_REFERENCE"
            }
        } else {
            require(
                insertAfterWindowId == 0L,
            ) {
                "DISPLAY_BRIDGE_Z_ORDER_AFTER_UNEXPECTED"
            }
        }
    }

    private fun requireWindowId(
        id: Long,
    ) {
        require(id > 0L) {
            "DISPLAY_BRIDGE_WINDOW_ID_INVALID"
        }
    }

    private fun requireParentId(
        parent: Long,
        id: Long,
    ) {
        require(
            parent >= 0L &&
                parent != id,
        ) {
            "DISPLAY_BRIDGE_PARENT_ID_INVALID"
        }
    }

    private fun requireDimensions(
        w: Int,
        h: Int,
    ) {
        require(
            w in 1..MAX_DIMENSION &&
                h in 1..MAX_DIMENSION,
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
