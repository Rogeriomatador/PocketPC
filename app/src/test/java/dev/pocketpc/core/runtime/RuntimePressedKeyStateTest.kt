package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimePressedKeyStateTest {
    private fun down(window: Long, key: Int) = RuntimeBridgeKeyEvent(window,
        RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN, key, 30, 1, 0)

    @Test fun switchingFocusReleasesEveryKeyInReverseOrderOnlyOnce() {
        val state = RuntimePressedKeyState()
        state.accepted(down(1, 16)); state.accepted(down(1, 65))
        state.accepted(down(1, 65).copy(action = RuntimeDisplayBridgePayloadCodec.KEY_ACTION_REPEAT, repeatCount = 2))
        val releases = state.release()
        assertEquals(listOf(65, 16), releases.map { it.keyCode })
        assertTrue(releases.all { it.action == RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP && it.modifiers == 0 && it.repeatCount == 0 })
        assertTrue(state.release().isEmpty())
    }
    @Test fun keyUpAndWindowRemovalDoNotReleaseOtherWindows() {
        val state = RuntimePressedKeyState()
        state.accepted(down(1, 65)); state.accepted(down(2, 66))
        state.accepted(down(1, 65).copy(action = RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP))
        assertTrue(state.release(1).isEmpty())
        assertEquals(2L, state.release().single().windowId)
    }
}
