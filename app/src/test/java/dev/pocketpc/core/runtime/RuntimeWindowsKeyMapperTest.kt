package dev.pocketpc.core.runtime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeWindowsKeyMapperTest {
    @Test
    fun mapsLetterAToWin32VkAndSet1Scan() {
        assertEquals(
            RuntimeWindowsKey(
                virtualKey = 0x41,
                scanCode = 0x1e,
            ),
            RuntimeWindowsKeyMapper.map(
                KeyEvent.KEYCODE_A,
            ),
        )
    }

    @Test
    fun mapsNavigationAsExtendedScanCode() {
        assertEquals(
            RuntimeWindowsKey(
                virtualKey = 0x27,
                scanCode = 0x14d,
            ),
            RuntimeWindowsKeyMapper.map(
                KeyEvent.KEYCODE_DPAD_RIGHT,
            ),
        )
    }

    @Test
    fun mapsRightControlAsExtendedKey() {
        assertEquals(
            RuntimeWindowsKey(
                virtualKey = 0xa3,
                scanCode = 0x11d,
            ),
            RuntimeWindowsKeyMapper.map(
                KeyEvent.KEYCODE_CTRL_RIGHT,
            ),
        )
    }

    @Test
    fun mapsFunctionKeys() {
        assertEquals(
            RuntimeWindowsKey(
                virtualKey = 0x7b,
                scanCode = 0x58,
            ),
            RuntimeWindowsKeyMapper.map(
                KeyEvent.KEYCODE_F12,
            ),
        )
    }

    @Test
    fun unknownAndroidKeyIsRejected() {
        assertNull(
            RuntimeWindowsKeyMapper.map(
                KeyEvent.KEYCODE_UNKNOWN,
            ),
        )
    }

    @Test
    fun mapsModifierMetaStateToProtocolBits() {
        val meta =
            KeyEvent.META_SHIFT_ON or
                KeyEvent.META_CTRL_ON or
                KeyEvent.META_ALT_ON or
                KeyEvent.META_META_ON

        assertEquals(
            RuntimeWindowsKeyMapper
                .MODIFIER_SHIFT or
                RuntimeWindowsKeyMapper
                    .MODIFIER_CONTROL or
                RuntimeWindowsKeyMapper
                    .MODIFIER_ALT or
                RuntimeWindowsKeyMapper
                    .MODIFIER_META,
            RuntimeWindowsKeyMapper
                .modifiers(meta),
        )
    }
}
