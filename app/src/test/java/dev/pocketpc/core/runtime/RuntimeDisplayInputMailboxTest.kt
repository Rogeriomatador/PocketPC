package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayInputMailboxTest {
    private fun pointer(
        action: Int,
        x: Int,
        window: Long = 1L,
        buttons: Int = 0,
    ) = RuntimeDisplayOutboundInput.Pointer(
        RuntimeBridgePointerEvent(
            windowId = window,
            action = action,
            x = x,
            y = 42,
            buttons = buttons,
            verticalScroll = 0,
            modifiers = 0,
        ),
    )

    private fun key(action: Int) = RuntimeDisplayOutboundInput.Key(
        RuntimeBridgeKeyEvent(
            windowId = 1L,
            action = action,
            keyCode = 0x41,
            scanCode = 0x1e,
            modifiers = 0,
            repeatCount = 0,
        ),
    )

    @Test
    fun adjacentMouseMovementsAreCoalescedToLatestCoordinates() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 2)
        val first = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 10)
        val latest = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 20)
        assertTrue(mailbox.offer(first))
        assertTrue(mailbox.offer(latest))
        assertEquals(1, mailbox.pendingCount())
        assertEquals(latest, mailbox.take())
        assertNull(mailbox.take())
    }

    @Test
    fun changingButtonStateOrWindowDoesNotCoalesce() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 4)
        val first = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 10, buttons = 1)
        val second = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 20, buttons = 0)
        val third = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 30, window = 2L)
        assertTrue(mailbox.offer(first))
        assertTrue(mailbox.offer(second))
        assertTrue(mailbox.offer(third))
        assertEquals(first, mailbox.take())
        assertEquals(second, mailbox.take())
        assertEquals(third, mailbox.take())
    }

    @Test
    fun buttonsAndKeyReleasesDisplaceOnlyStalePointerMovement() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 3)
        val down = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_DOWN, 1, buttons = 1)
        val move = pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 2, buttons = 1)
        val keyDown = key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)
        val keyUp = key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)

        assertTrue(mailbox.offer(down))
        assertTrue(mailbox.offer(move))
        assertTrue(mailbox.offer(keyDown))
        assertTrue(mailbox.offer(keyUp))
        assertEquals(down, mailbox.take())
        assertEquals(keyDown, mailbox.take())
        assertEquals(keyUp, mailbox.take())
        assertNull(mailbox.take())
    }

    @Test
    fun fullQueueDropsOnlyNewMouseMovement() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 1)
        val keyUp = key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)
        assertTrue(mailbox.offer(keyUp))
        assertTrue(mailbox.offer(pointer(RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE, 20)))
        assertEquals(1, mailbox.pendingCount())
        assertEquals(keyUp, mailbox.take())
    }

    @Test
    fun nonDroppableOverflowFailsClosedRatherThanLosingKeyUp() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 1)
        val keyDown = key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)
        val keyUp = key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)
        assertTrue(mailbox.offer(keyDown))
        assertFalse(mailbox.offer(keyUp))
        assertEquals(0, mailbox.pendingCount())
        assertFalse(mailbox.offer(keyDown))
        assertNull(mailbox.take())
    }

    @Test
    fun shutdownDiscardsPendingCommandsAndRefusesFurtherInput() {
        val mailbox = RuntimeDisplayInputMailbox(capacity = 2)
        assertTrue(mailbox.offer(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)))
        mailbox.close()
        assertFalse(mailbox.offer(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)))
        assertNull(mailbox.take())
    }
}
