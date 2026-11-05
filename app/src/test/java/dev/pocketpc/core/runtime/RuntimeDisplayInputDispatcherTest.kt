package dev.pocketpc.core.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayInputDispatcherTest {
    private fun key(action: Int): RuntimeBridgeKeyEvent =
        RuntimeBridgeKeyEvent(
            windowId = 1L,
            action = action,
            keyCode = 0x41,
            scanCode = 0x1e,
            modifiers = 0,
            repeatCount = 0,
        )

    @Test
    fun guestSocketDelayDoesNotBlockCallerAndPreservesKeyOrder() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(2)
        val seen = Collections.synchronizedList(mutableListOf<RuntimeDisplayOutboundInput>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val dispatcher = RuntimeDisplayInputDispatcher(
            transmit = { input ->
                writing.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                seen.add(input)
                delivered.countDown()
            },
            onFailure = { failures.add(it) },
            capacity = 4,
        )
        try {
            assertTrue(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)).isSuccess)
            assertTrue("Background IO worker did not start", writing.await(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            assertTrue(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)).isSuccess)
            val elapsed = System.nanoTime() - start
            assertTrue("UI admission stalled waiting for blocked socket", elapsed < TimeUnit.SECONDS.toNanos(1))
            release.countDown()
            assertTrue(delivered.await(3, TimeUnit.SECONDS))
            assertEquals(
                listOf(
                    RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN,
                    RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP,
                ),
                seen.map { (it as RuntimeDisplayOutboundInput.Key).value.action },
            )
            assertTrue(failures.isEmpty())
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun nonDroppableOverflowTriggersTransportTermination() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val dispatcher = RuntimeDisplayInputDispatcher(
            transmit = {
                writing.countDown()
                release.await(4, TimeUnit.SECONDS)
            },
            onFailure = { terminal.countDown() },
            capacity = 1,
        )
        try {
            assertTrue(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)).isSuccess)
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            assertTrue(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)).isSuccess)
            assertFalse(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN)).isSuccess)
            assertTrue("Socket termination was not requested", terminal.await(3, TimeUnit.SECONDS))
            assertFalse(dispatcher.key(key(RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP)).isSuccess)
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }
}
