package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeKeyInputRouterTest {
    private fun event(key: Int, down: Boolean = true, window: Long = 7) = RuntimeBridgeKeyEvent(
        window, if (down) RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN else RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP,
        key, key + 1, 0, 0,
    )

    @Test fun diagonalMovementAndJumpStayHeldUntilTheirOwnRelease() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        listOf(0x57, 0x44, 0x20).forEach { router.send("touch:$it", event(it)) }
        assertEquals(listOf(1, 1, 1), sent.map { it.action })
        router.send("touch:32", event(0x20, false))
        assertEquals(listOf(0x20), sent.filter { it.action == 2 }.map { it.keyCode })
        router.release()
        assertEquals(listOf(0x20, 0x44, 0x57), sent.filter { it.action == 2 }.map { it.keyCode })
    }

    @Test fun touchReleaseDoesNotReleaseHardwareOwner() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("hardware", event(0x57))
        router.send("touch:W", event(0x57))
        router.send("touch:W", event(0x57, false))
        assertEquals(1, sent.size)
        router.send("hardware", event(0x57, false))
        assertEquals(listOf(1, 2), sent.map { it.action })
    }

    @Test fun hardwareReleaseDoesNotReleaseTouchOwner() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("touch:W", event(0x57))
        router.send("hardware", event(0x57))
        router.send("hardware", event(0x57, false))
        assertEquals(1, sent.size)
        router.release()
        assertEquals(2, sent.size)
    }

    @Test fun duplicateDownAndLateUpDoNotAffectNewKeys() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("touch", event(0x57))
        router.send("touch", event(0x57))
        router.release()
        router.send("hardware", event(0x57))
        router.send("touch", event(0x57, false))
        assertEquals(listOf(1, 2, 1), sent.map { it.action })
    }

    @Test fun focusCleanupReleasesAllOwnersOnceAndIgnoresLateUps() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("touch", event(0x57))
        router.send("hardware", event(0x57))
        router.send("touch", event(0x20))
        router.release()
        router.send("touch", event(0x57, false))
        router.send("hardware", event(0x57, false))
        router.release()
        assertEquals(listOf(1, 1, 2, 2), sent.map { it.action })
    }

    @Test fun canceledGestureCannotReleaseANewerTouchOfTheSameButton() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("gesture-1", event(0x57))
        router.release()
        router.send("gesture-2", event(0x57))
        router.send("gesture-1", event(0x57, false))
        assertEquals(listOf(1, 2, 1), sent.map { it.action })
        router.send("gesture-2", event(0x57, false))
        assertEquals(listOf(1, 2, 1, 2), sent.map { it.action })
    }

    @Test fun windowCleanupCannotReleaseAnotherWindow() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("touch", event(0x57))
        router.send("touch", event(0x57, window = 9))
        router.release(7)
        assertEquals(listOf(7L), sent.filter { it.action == 2 }.map { it.windowId })
        router.release(9)
        assertEquals(listOf(7L, 9L), sent.filter { it.action == 2 }.map { it.windowId })
    }

    @Test fun heldShiftAppliesToMovementAndSpaceUntilReleased() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("shift", event(0xa0))
        router.send("W", event(0x57))
        router.send("space", event(0x20))
        router.send("W", event(0x57, false))
        router.send("shift", event(0xa0, false))
        router.send("space", event(0x20, false))
        assertEquals(listOf(1, 1, 1, 1, 0, 0), sent.map { it.modifiers })
    }

    @Test fun tapChordDoesNotReleaseAlreadyHeldModifier() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("hardware", event(0xa2))
        router.send("tap", event(0xa2))
        router.send("tap", event(0x53))
        router.send("tap", event(0x53, false))
        router.send("tap", event(0xa2, false))
        assertEquals(listOf(0xa2, 0x53, 0x53), sent.map { it.keyCode })
        assertTrue(sent.all { it.modifiers == RuntimeWindowsKeyMapper.MODIFIER_CONTROL })
        router.release()
        assertEquals(0, sent.last().modifiers)
    }

    @Test fun modifierUpClearsNativeFlagButPreservesTheOtherSide() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        router.send("left", event(0xa0))
        router.send("right", event(0xa1))
        router.send("left", event(0xa0, false).copy(modifiers = 1))
        assertEquals(1, sent.last().modifiers)
        router.send("right", event(0xa1, false).copy(modifiers = 1))
        assertEquals(0, sent.last().modifiers)
    }

    @Test fun rejectedDownDoesNotCreatePhantomHeldKey() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it); false }
        assertFalse(router.send("touch", event(0x57)))
        assertTrue(router.release())
        assertEquals(1, sent.size)
    }

    @Test fun failedReleaseRemainsAvailableForRetry() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        var rejectUp = true
        val router = RuntimeKeyInputRouter { sent.add(it); it.action != 2 || !rejectUp }
        router.send("touch", event(0x57))
        assertFalse(router.send("touch", event(0x57, false)))
        assertFalse(router.release())
        rejectUp = false
        assertTrue(router.release())
        val count = sent.size
        assertTrue(router.release())
        assertEquals(count, sent.size)
    }

    @Test fun repeatsRequireOwnershipAndKeepHeldTouchModifier() {
        val sent = mutableListOf<RuntimeBridgeKeyEvent>()
        val router = RuntimeKeyInputRouter { sent.add(it) }
        val repeat = event(0x57).copy(action = 3, repeatCount = 2)
        assertFalse(router.send("hardware", repeat))
        router.send("shift", event(0xa0))
        router.send("hardware", event(0x57))
        assertTrue(router.send("hardware", repeat))
        assertEquals(2, sent.last().repeatCount)
        assertEquals(1, sent.last().modifiers)
    }
}
