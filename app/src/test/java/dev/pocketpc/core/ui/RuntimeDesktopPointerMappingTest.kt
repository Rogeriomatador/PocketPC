package dev.pocketpc.core.ui

import android.view.MotionEvent
import dev.pocketpc.core.runtime.RuntimeDisplayBridgePayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeDesktopPointerMappingTest {
    @Test
    fun mapsPrimarySecondaryAndMiddleButtons() {
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .POINTER_BUTTON_PRIMARY,
            runtimePointerButtonMask(
                MotionEvent.BUTTON_PRIMARY,
            ),
        )
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .POINTER_BUTTON_SECONDARY,
            runtimePointerButtonMask(
                MotionEvent.BUTTON_SECONDARY,
            ),
        )
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .POINTER_BUTTON_MIDDLE,
            runtimePointerButtonMask(
                MotionEvent.BUTTON_TERTIARY,
            ),
        )
    }

    @Test
    fun mapsMultiplePressedButtons() {
        assertEquals(
            RuntimeDisplayBridgePayloadCodec
                .POINTER_BUTTON_PRIMARY or
                RuntimeDisplayBridgePayloadCodec
                    .POINTER_BUTTON_SECONDARY or
                RuntimeDisplayBridgePayloadCodec
                    .POINTER_BUTTON_MIDDLE,
            runtimePointerButtonMask(
                MotionEvent.BUTTON_PRIMARY or
                    MotionEvent.BUTTON_SECONDARY or
                    MotionEvent.BUTTON_TERTIARY,
            ),
        )
    }

    @Test
    fun ignoresUnsupportedMouseButtons() {
        assertEquals(
            0,
            runtimePointerButtonMask(
                MotionEvent.BUTTON_BACK or
                    MotionEvent.BUTTON_FORWARD,
            ),
        )
    }
}
