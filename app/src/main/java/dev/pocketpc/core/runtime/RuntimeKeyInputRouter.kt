package dev.pocketpc.core.runtime

/** Serial, UI-thread input ownership: releasing a touch must not release a hardware key. */
class RuntimeKeyInputRouter(private val transmit: (RuntimeBridgeKeyEvent) -> Boolean) {
    private data class Key(val windowId: Long, val keyCode: Int)
    private data class Held(val event: RuntimeBridgeKeyEvent, val owners: MutableSet<String>)
    private val held = linkedMapOf<Key, Held>()

    fun send(owner: String, event: RuntimeBridgeKeyEvent): Boolean {
        require(owner.isNotBlank() && event.windowId > 0)
        val key = Key(event.windowId, event.keyCode)
        val current = held[key]
        return when (event.action) {
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_DOWN -> {
                if (current != null) {
                    current.owners.add(owner)
                    true
                } else {
                    val outgoing = event.copy(modifiers = event.modifiers or modifiers(event.windowId, key.keyCode))
                    if (!transmit(outgoing)) false else {
                        held[key] = Held(outgoing, mutableSetOf(owner))
                        true
                    }
                }
            }
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_REPEAT -> {
                if (current?.owners?.contains(owner) != true) false
                else transmit(event.copy(modifiers = event.modifiers or modifiers(event.windowId)))
            }
            RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP -> {
                if (current?.owners?.contains(owner) != true) true
                else if (current.owners.size > 1) {
                    current.owners.remove(owner)
                    true
                } else {
                    val outgoing = current.event.copy(
                        action = RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP,
                        modifiers = (event.modifiers and modifierForKey(key.keyCode).inv()) or
                            modifiers(event.windowId, excluding = key.keyCode),
                        repeatCount = 0,
                    )
                    if (!transmit(outgoing)) false else {
                        held.remove(key)
                        true
                    }
                }
            }
            else -> false
        }
    }

    /** Failed releases remain tracked so a later cleanup can retry them. */
    fun release(windowId: Long? = null): Boolean {
        var success = true
        held.keys.toList().asReversed().filter { windowId == null || it.windowId == windowId }.forEach { key ->
            val event = held.getValue(key).event.copy(
                action = RuntimeDisplayBridgePayloadCodec.KEY_ACTION_UP,
                modifiers = modifiers(key.windowId, excluding = key.keyCode), repeatCount = 0,
            )
            if (transmit(event)) held.remove(key) else success = false
        }
        return success
    }

    private fun modifiers(windowId: Long, including: Int? = null, excluding: Int? = null): Int {
        val codes = held.keys.filter { it.windowId == windowId && it.keyCode != excluding }.map { it.keyCode } + listOfNotNull(including)
        return codes.fold(0) { flags, code -> flags or modifierForKey(code) }
    }

    private fun modifierForKey(code: Int): Int = when (code) {
        0xa0, 0xa1 -> RuntimeWindowsKeyMapper.MODIFIER_SHIFT
        0xa2, 0xa3 -> RuntimeWindowsKeyMapper.MODIFIER_CONTROL
        0xa4, 0xa5 -> RuntimeWindowsKeyMapper.MODIFIER_ALT
        0x5b, 0x5c -> RuntimeWindowsKeyMapper.MODIFIER_META
        else -> 0
    }
}
