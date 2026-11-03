package dev.pocketpc.core.runtime

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Commands originating from the Android UI. They are validated on admission,
 * queued in wire order, and written to the guest on Dispatchers.IO.
 *
 * The ACK/surface protocol intentionally remains synchronous on the session IO
 * worker; these are only the UI-originated commands.
 */
internal sealed interface RuntimeDisplayOutboundInput {
    data class Pointer(val value: RuntimeBridgePointerEvent) : RuntimeDisplayOutboundInput
    data class Key(val value: RuntimeBridgeKeyEvent) : RuntimeDisplayOutboundInput
    data class Window(val value: RuntimeBridgeWindowCommand) : RuntimeDisplayOutboundInput
}

/**
 * A bounded, ordered mailbox. Never discards DOWN/UP, keyboard or window
 * events. Only pointer MOVE can be replaced or discarded under pressure.
 * A full queue containing no droppable motion fails closed; silently losing a
 * KEY_UP could leave a key held in the Windows guest.
 */
internal class RuntimeDisplayInputMailbox(
    private val capacity: Int = 128,
) {
    init {
        require(capacity in 1..4096)
    }

    private val lock = Any()
    private val pending = ArrayDeque<RuntimeDisplayOutboundInput>()
    private var closed = false

    fun offer(input: RuntimeDisplayOutboundInput): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false

            if (input.isMotion()) {
                val previous = pending.lastOrNull()
                if (previous?.isCompatibleMotion(input) == true) {
                    pending.removeLast()
                    pending.addLast(input)
                    return@synchronized true
                }
                // No room: omit only this move. A following move will resync
                // the pointer, without jeopardizing key/button transitions.
                if (pending.size >= capacity) return@synchronized true
            } else if (pending.size >= capacity) {
                val staleMotion = pending.indexOfFirst { it.isMotion() }
                if (staleMotion < 0) {
                    closed = true
                    pending.clear()
                    return@synchronized false
                }
                pending.removeAt(staleMotion)
            }

            pending.addLast(input)
            true
        }

    fun take(): RuntimeDisplayOutboundInput? =
        synchronized(lock) {
            if (closed || pending.isEmpty()) null else pending.removeFirst()
        }

    fun close() = synchronized(lock) {
        closed = true
        pending.clear()
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun RuntimeDisplayOutboundInput.isMotion(): Boolean =
        this is RuntimeDisplayOutboundInput.Pointer &&
            value.action == RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE

    private fun RuntimeDisplayOutboundInput.isCompatibleMotion(
        next: RuntimeDisplayOutboundInput,
    ): Boolean {
        val previous = (this as? RuntimeDisplayOutboundInput.Pointer)?.value ?: return false
        val current = (next as? RuntimeDisplayOutboundInput.Pointer)?.value ?: return false
        return previous.action == RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE &&
            current.action == RuntimeDisplayBridgePayloadCodec.POINTER_ACTION_MOVE &&
            previous.windowId == current.windowId &&
            previous.buttons == current.buttons &&
            previous.modifiers == current.modifiers
    }
}

/**
 * Non-blocking entrypoint for Compose event handlers. An unresponsive socket
 * can occupy this IO worker, but cannot block the Android main thread.
 *
 * Transport failures or unsendable state transitions terminate the guest
 * channel (via onFailure) so input ownership cannot remain silently desynced.
 */
internal class RuntimeDisplayInputDispatcher(
    private val transmit: (RuntimeDisplayOutboundInput) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    capacity: Int = 128,
) : Closeable {
    private val mailbox = RuntimeDisplayInputMailbox(capacity)
    private val terminated = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signal) {
                while (!terminated.get()) {
                    val next = mailbox.take() ?: break
                    try {
                        transmit(next)
                    } catch (failure: Throwable) {
                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                        fail(failure)
                        break
                    }
                }
            }
        }
    }

    fun pointer(value: RuntimeBridgePointerEvent): Result<Unit> =
        submit(RuntimeDisplayOutboundInput.Pointer(value))

    fun key(value: RuntimeBridgeKeyEvent): Result<Unit> =
        submit(RuntimeDisplayOutboundInput.Key(value))

    fun window(value: RuntimeBridgeWindowCommand): Result<Unit> =
        submit(RuntimeDisplayOutboundInput.Window(value))

    private fun submit(input: RuntimeDisplayOutboundInput): Result<Unit> =
        runCatching {
            check(!terminated.get()) { "DISPLAY_INPUT_DISPATCHER_CLOSED" }
            // Fail synchronously for invalid events rather than acknowledge a
            // queued event which could never be encoded by the guest protocol.
            when (input) {
                is RuntimeDisplayOutboundInput.Pointer ->
                    RuntimeDisplayBridgePayloadCodec.encodePointerEvent(input.value)
                is RuntimeDisplayOutboundInput.Key ->
                    RuntimeDisplayBridgePayloadCodec.encodeKeyEvent(input.value)
                is RuntimeDisplayOutboundInput.Window ->
                    RuntimeDisplayBridgePayloadCodec.encodeWindowCommand(input.value)
            }
            if (!mailbox.offer(input)) {
                val failure = IllegalStateException("DISPLAY_INPUT_QUEUE_SATURATED")
                fail(failure)
                throw failure
            }
            check(signal.trySend(Unit).isSuccess) { "DISPLAY_INPUT_DISPATCHER_CLOSED" }
        }

    private fun fail(failure: Throwable) {
        if (terminated.compareAndSet(false, true)) {
            mailbox.close()
            signal.close()
            // A failed socket must be disconnected on IO, never from Compose.
            scope.launch { runCatching { onFailure(failure) } }
        }
    }

    override fun close() {
        if (terminated.compareAndSet(false, true)) {
            mailbox.close()
            signal.close()
        }
        scope.cancel()
    }
}
