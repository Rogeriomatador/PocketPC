package dev.pocketpc.core.runtime

import android.view.KeyEvent

object RuntimeVirtualKeyboard {
    fun keyPress(windowId: Long, androidKeyCode: Int, shift: Boolean = false,
                 control: Boolean = false, alt: Boolean = false): List<RuntimeBridgeKeyEvent> {
        require(windowId > 0) { "VIRTUAL_KEYBOARD_WINDOW_MISSING" }
        val keys = buildList {
            if (control) add(KeyEvent.KEYCODE_CTRL_LEFT)
            if (alt) add(KeyEvent.KEYCODE_ALT_LEFT)
            if (shift) add(KeyEvent.KEYCODE_SHIFT_LEFT)
            add(androidKeyCode)
        }.map { requireNotNull(RuntimeWindowsKeyMapper.map(it)) { "VIRTUAL_KEYBOARD_KEY_UNSUPPORTED" } }
        val modifiers = (if (shift) RuntimeWindowsKeyMapper.MODIFIER_SHIFT else 0) or
            (if (control) RuntimeWindowsKeyMapper.MODIFIER_CONTROL else 0) or
            (if (alt) RuntimeWindowsKeyMapper.MODIFIER_ALT else 0)
        return keys.map { RuntimeBridgeKeyEvent(windowId, RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN,
            it.virtualKey, it.scanCode, modifiers, 0) } + keys.reversed().map {
            RuntimeBridgeKeyEvent(windowId, RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP,
                it.virtualKey, it.scanCode, 0, 0)
        }
    }
}
