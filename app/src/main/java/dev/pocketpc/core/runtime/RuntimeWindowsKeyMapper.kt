package dev.pocketpc.core.runtime

import android.view.KeyEvent

data class RuntimeWindowsKey(
    val virtualKey: Int,
    val scanCode: Int,
)

object RuntimeWindowsKeyMapper {
    const val MODIFIER_SHIFT = 1 shl 0
    const val MODIFIER_CONTROL = 1 shl 1
    const val MODIFIER_ALT = 1 shl 2
    const val MODIFIER_META = 1 shl 3

    fun map(
        androidKeyCode: Int,
    ): RuntimeWindowsKey? =
        when (androidKeyCode) {
            KeyEvent.KEYCODE_A ->
                RuntimeWindowsKey(0x41, 0x1e)
            KeyEvent.KEYCODE_B ->
                RuntimeWindowsKey(0x42, 0x30)
            KeyEvent.KEYCODE_C ->
                RuntimeWindowsKey(0x43, 0x2e)
            KeyEvent.KEYCODE_D ->
                RuntimeWindowsKey(0x44, 0x20)
            KeyEvent.KEYCODE_E ->
                RuntimeWindowsKey(0x45, 0x12)
            KeyEvent.KEYCODE_F ->
                RuntimeWindowsKey(0x46, 0x21)
            KeyEvent.KEYCODE_G ->
                RuntimeWindowsKey(0x47, 0x22)
            KeyEvent.KEYCODE_H ->
                RuntimeWindowsKey(0x48, 0x23)
            KeyEvent.KEYCODE_I ->
                RuntimeWindowsKey(0x49, 0x17)
            KeyEvent.KEYCODE_J ->
                RuntimeWindowsKey(0x4a, 0x24)
            KeyEvent.KEYCODE_K ->
                RuntimeWindowsKey(0x4b, 0x25)
            KeyEvent.KEYCODE_L ->
                RuntimeWindowsKey(0x4c, 0x26)
            KeyEvent.KEYCODE_M ->
                RuntimeWindowsKey(0x4d, 0x32)
            KeyEvent.KEYCODE_N ->
                RuntimeWindowsKey(0x4e, 0x31)
            KeyEvent.KEYCODE_O ->
                RuntimeWindowsKey(0x4f, 0x18)
            KeyEvent.KEYCODE_P ->
                RuntimeWindowsKey(0x50, 0x19)
            KeyEvent.KEYCODE_Q ->
                RuntimeWindowsKey(0x51, 0x10)
            KeyEvent.KEYCODE_R ->
                RuntimeWindowsKey(0x52, 0x13)
            KeyEvent.KEYCODE_S ->
                RuntimeWindowsKey(0x53, 0x1f)
            KeyEvent.KEYCODE_T ->
                RuntimeWindowsKey(0x54, 0x14)
            KeyEvent.KEYCODE_U ->
                RuntimeWindowsKey(0x55, 0x16)
            KeyEvent.KEYCODE_V ->
                RuntimeWindowsKey(0x56, 0x2f)
            KeyEvent.KEYCODE_W ->
                RuntimeWindowsKey(0x57, 0x11)
            KeyEvent.KEYCODE_X ->
                RuntimeWindowsKey(0x58, 0x2d)
            KeyEvent.KEYCODE_Y ->
                RuntimeWindowsKey(0x59, 0x15)
            KeyEvent.KEYCODE_Z ->
                RuntimeWindowsKey(0x5a, 0x2c)

            KeyEvent.KEYCODE_1 ->
                RuntimeWindowsKey(0x31, 0x02)
            KeyEvent.KEYCODE_2 ->
                RuntimeWindowsKey(0x32, 0x03)
            KeyEvent.KEYCODE_3 ->
                RuntimeWindowsKey(0x33, 0x04)
            KeyEvent.KEYCODE_4 ->
                RuntimeWindowsKey(0x34, 0x05)
            KeyEvent.KEYCODE_5 ->
                RuntimeWindowsKey(0x35, 0x06)
            KeyEvent.KEYCODE_6 ->
                RuntimeWindowsKey(0x36, 0x07)
            KeyEvent.KEYCODE_7 ->
                RuntimeWindowsKey(0x37, 0x08)
            KeyEvent.KEYCODE_8 ->
                RuntimeWindowsKey(0x38, 0x09)
            KeyEvent.KEYCODE_9 ->
                RuntimeWindowsKey(0x39, 0x0a)
            KeyEvent.KEYCODE_0 ->
                RuntimeWindowsKey(0x30, 0x0b)

            KeyEvent.KEYCODE_ENTER ->
                RuntimeWindowsKey(0x0d, 0x1c)
            KeyEvent.KEYCODE_NUMPAD_ENTER ->
                RuntimeWindowsKey(0x0d, 0x11c)
            KeyEvent.KEYCODE_TAB ->
                RuntimeWindowsKey(0x09, 0x0f)
            KeyEvent.KEYCODE_SPACE ->
                RuntimeWindowsKey(0x20, 0x39)
            KeyEvent.KEYCODE_DEL ->
                RuntimeWindowsKey(0x08, 0x0e)
            KeyEvent.KEYCODE_ESCAPE ->
                RuntimeWindowsKey(0x1b, 0x01)
            KeyEvent.KEYCODE_FORWARD_DEL ->
                RuntimeWindowsKey(0x2e, 0x153)
            KeyEvent.KEYCODE_INSERT ->
                RuntimeWindowsKey(0x2d, 0x152)
            KeyEvent.KEYCODE_MOVE_HOME ->
                RuntimeWindowsKey(0x24, 0x147)
            KeyEvent.KEYCODE_MOVE_END ->
                RuntimeWindowsKey(0x23, 0x14f)
            KeyEvent.KEYCODE_PAGE_UP ->
                RuntimeWindowsKey(0x21, 0x149)
            KeyEvent.KEYCODE_PAGE_DOWN ->
                RuntimeWindowsKey(0x22, 0x151)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                RuntimeWindowsKey(0x25, 0x14b)
            KeyEvent.KEYCODE_DPAD_UP ->
                RuntimeWindowsKey(0x26, 0x148)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                RuntimeWindowsKey(0x27, 0x14d)
            KeyEvent.KEYCODE_DPAD_DOWN ->
                RuntimeWindowsKey(0x28, 0x150)

            KeyEvent.KEYCODE_SHIFT_LEFT ->
                RuntimeWindowsKey(0xa0, 0x2a)
            KeyEvent.KEYCODE_SHIFT_RIGHT ->
                RuntimeWindowsKey(0xa1, 0x36)
            KeyEvent.KEYCODE_CTRL_LEFT ->
                RuntimeWindowsKey(0xa2, 0x1d)
            KeyEvent.KEYCODE_CTRL_RIGHT ->
                RuntimeWindowsKey(0xa3, 0x11d)
            KeyEvent.KEYCODE_ALT_LEFT ->
                RuntimeWindowsKey(0xa4, 0x38)
            KeyEvent.KEYCODE_ALT_RIGHT ->
                RuntimeWindowsKey(0xa5, 0x138)
            KeyEvent.KEYCODE_META_LEFT ->
                RuntimeWindowsKey(0x5b, 0x15b)
            KeyEvent.KEYCODE_META_RIGHT ->
                RuntimeWindowsKey(0x5c, 0x15c)
            KeyEvent.KEYCODE_CAPS_LOCK ->
                RuntimeWindowsKey(0x14, 0x3a)
            KeyEvent.KEYCODE_NUM_LOCK ->
                RuntimeWindowsKey(0x90, 0x45)
            KeyEvent.KEYCODE_SCROLL_LOCK ->
                RuntimeWindowsKey(0x91, 0x46)

            KeyEvent.KEYCODE_F1 ->
                RuntimeWindowsKey(0x70, 0x3b)
            KeyEvent.KEYCODE_F2 ->
                RuntimeWindowsKey(0x71, 0x3c)
            KeyEvent.KEYCODE_F3 ->
                RuntimeWindowsKey(0x72, 0x3d)
            KeyEvent.KEYCODE_F4 ->
                RuntimeWindowsKey(0x73, 0x3e)
            KeyEvent.KEYCODE_F5 ->
                RuntimeWindowsKey(0x74, 0x3f)
            KeyEvent.KEYCODE_F6 ->
                RuntimeWindowsKey(0x75, 0x40)
            KeyEvent.KEYCODE_F7 ->
                RuntimeWindowsKey(0x76, 0x41)
            KeyEvent.KEYCODE_F8 ->
                RuntimeWindowsKey(0x77, 0x42)
            KeyEvent.KEYCODE_F9 ->
                RuntimeWindowsKey(0x78, 0x43)
            KeyEvent.KEYCODE_F10 ->
                RuntimeWindowsKey(0x79, 0x44)
            KeyEvent.KEYCODE_F11 ->
                RuntimeWindowsKey(0x7a, 0x57)
            KeyEvent.KEYCODE_F12 ->
                RuntimeWindowsKey(0x7b, 0x58)

            KeyEvent.KEYCODE_NUMPAD_0 ->
                RuntimeWindowsKey(0x60, 0x52)
            KeyEvent.KEYCODE_NUMPAD_1 ->
                RuntimeWindowsKey(0x61, 0x4f)
            KeyEvent.KEYCODE_NUMPAD_2 ->
                RuntimeWindowsKey(0x62, 0x50)
            KeyEvent.KEYCODE_NUMPAD_3 ->
                RuntimeWindowsKey(0x63, 0x51)
            KeyEvent.KEYCODE_NUMPAD_4 ->
                RuntimeWindowsKey(0x64, 0x4b)
            KeyEvent.KEYCODE_NUMPAD_5 ->
                RuntimeWindowsKey(0x65, 0x4c)
            KeyEvent.KEYCODE_NUMPAD_6 ->
                RuntimeWindowsKey(0x66, 0x4d)
            KeyEvent.KEYCODE_NUMPAD_7 ->
                RuntimeWindowsKey(0x67, 0x47)
            KeyEvent.KEYCODE_NUMPAD_8 ->
                RuntimeWindowsKey(0x68, 0x48)
            KeyEvent.KEYCODE_NUMPAD_9 ->
                RuntimeWindowsKey(0x69, 0x49)
            KeyEvent.KEYCODE_NUMPAD_DIVIDE ->
                RuntimeWindowsKey(0x6f, 0x135)
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY ->
                RuntimeWindowsKey(0x6a, 0x37)
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT ->
                RuntimeWindowsKey(0x6d, 0x4a)
            KeyEvent.KEYCODE_NUMPAD_ADD ->
                RuntimeWindowsKey(0x6b, 0x4e)
            KeyEvent.KEYCODE_NUMPAD_DOT ->
                RuntimeWindowsKey(0x6e, 0x53)

            KeyEvent.KEYCODE_COMMA ->
                RuntimeWindowsKey(0xbc, 0x33)
            KeyEvent.KEYCODE_PERIOD ->
                RuntimeWindowsKey(0xbe, 0x34)
            KeyEvent.KEYCODE_GRAVE ->
                RuntimeWindowsKey(0xc0, 0x29)
            KeyEvent.KEYCODE_MINUS ->
                RuntimeWindowsKey(0xbd, 0x0c)
            KeyEvent.KEYCODE_EQUALS ->
                RuntimeWindowsKey(0xbb, 0x0d)
            KeyEvent.KEYCODE_LEFT_BRACKET ->
                RuntimeWindowsKey(0xdb, 0x1a)
            KeyEvent.KEYCODE_RIGHT_BRACKET ->
                RuntimeWindowsKey(0xdd, 0x1b)
            KeyEvent.KEYCODE_BACKSLASH ->
                RuntimeWindowsKey(0xdc, 0x2b)
            KeyEvent.KEYCODE_SEMICOLON ->
                RuntimeWindowsKey(0xba, 0x27)
            KeyEvent.KEYCODE_APOSTROPHE ->
                RuntimeWindowsKey(0xde, 0x28)
            KeyEvent.KEYCODE_SLASH ->
                RuntimeWindowsKey(0xbf, 0x35)

            else -> null
        }

    fun modifiers(
        metaState: Int,
    ): Int {
        var result = 0
        if (
            metaState and
                KeyEvent.META_SHIFT_ON != 0
        ) {
            result =
                result or
                    MODIFIER_SHIFT
        }
        if (
            metaState and
                KeyEvent.META_CTRL_ON != 0
        ) {
            result =
                result or
                    MODIFIER_CONTROL
        }
        if (
            metaState and
                KeyEvent.META_ALT_ON != 0
        ) {
            result =
                result or
                    MODIFIER_ALT
        }
        if (
            metaState and
                KeyEvent.META_META_ON != 0
        ) {
            result =
                result or
                    MODIFIER_META
        }
        return result
    }
}
