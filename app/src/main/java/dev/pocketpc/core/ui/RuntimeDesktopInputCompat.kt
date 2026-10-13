package dev.pocketpc.core.ui

import android.view.MotionEvent
import dev.pocketpc.core.runtime.RuntimeDisplayBridgePayloadCodec

internal const val WINDOWS_WHEEL_DELTA = 120f

internal fun runtimePointerButtonMask(buttonState: Int): Int {
    var result = 0
    if (buttonState and MotionEvent.BUTTON_PRIMARY != 0) {
        result = result or RuntimeDisplayBridgePayloadCodec.POINTER_BUTTON_PRIMARY
    }
    if (buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
        result = result or RuntimeDisplayBridgePayloadCodec.POINTER_BUTTON_SECONDARY
    }
    if (buttonState and MotionEvent.BUTTON_TERTIARY != 0) {
        result = result or RuntimeDisplayBridgePayloadCodec.POINTER_BUTTON_MIDDLE
    }
    return result
}
