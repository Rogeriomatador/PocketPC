package dev.pocketpc.core.runtime

/** Tracks only events accepted by the bridge, so focus changes cannot leave keys held. */
class RuntimePressedKeyState {
    private val pressed = linkedMapOf<Pair<Long, Int>, RuntimeBridgeKeyEvent>()

    fun accepted(event: RuntimeBridgeKeyEvent) {
        val key = event.windowId to event.keyCode
        when (event.action) {
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN,
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_REPEAT -> pressed[key] = event
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP -> pressed.remove(key)
        }
    }

    fun release(windowId: Long? = null): List<RuntimeBridgeKeyEvent> {
        val keys = pressed.filterKeys { windowId == null || it.first == windowId }
        keys.keys.forEach(pressed::remove)
        return keys.values.reversed().map {
            it.copy(action = RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP, modifiers = 0, repeatCount = 0)
        }
    }
}
