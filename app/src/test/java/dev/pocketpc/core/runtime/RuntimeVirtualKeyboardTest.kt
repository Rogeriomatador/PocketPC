package dev.pocketpc.core.runtime

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class RuntimeVirtualKeyboardTest {
    @Test fun controlSaveUsesRealWin32KeysAndReleasesModifiersLast() {
        val events = RuntimeVirtualKeyboard.keyPress(7, KeyEvent.KEYCODE_S, control = true)
        assertEquals(listOf(0xa2, 0x53, 0x53, 0xa2), events.map { it.keyCode })
        assertEquals(listOf(1, 1, 2, 2), events.map { it.action })
        assertTrue(events.all { it.windowId == 7L })
        val state = RuntimePressedKeyState()
        events.forEach(state::accepted)
        assertTrue(state.release().isEmpty())
    }
    @Test fun uppercaseTapIncludesShiftAndExtendedEnterRemainsMapped() {
        val events = RuntimeVirtualKeyboard.keyPress(9, KeyEvent.KEYCODE_A, shift = true)
        assertEquals(listOf(0xa0, 0x41, 0x41, 0xa0), events.map { it.keyCode })
        assertEquals(0x1c, RuntimeVirtualKeyboard.keyPress(9, KeyEvent.KEYCODE_ENTER).first().scanCode)
    }
}
