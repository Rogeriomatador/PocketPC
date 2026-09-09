package dev.pocketpc.core.runtime

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeDesktopBridge {
    private data class BindingState(
        val id: Long,
        val commandSender:
            (
                RuntimeBridgeWindowCommand,
            ) -> Result<Unit>,
        val pointerSender:
            ((
                RuntimeBridgePointerEvent,
            ) -> Result<Unit>)?,
        val keySender:
            ((
                RuntimeBridgeKeyEvent,
            ) -> Result<Unit>)?,
        var windows:
            List<
                RuntimeDisplayCompositorWindow
            > = emptyList(),
    )

    private val lock = Any()
    private val mutableWindows =
        MutableStateFlow<
            List<
                RuntimeDisplayCompositorWindow
            >
        >(emptyList())

    val windows:
        StateFlow<
            List<
                RuntimeDisplayCompositorWindow
            >
        > =
        mutableWindows.asStateFlow()

    private var nextBindingId = 1L
    private val bindings =
        LinkedHashMap<
            Long,
            BindingState
        >()
    private val windowOwners =
        HashMap<Long, Long>()

    fun bind(
        sender:
            (
                RuntimeBridgeWindowCommand,
            ) -> Result<Unit>,
    ): RuntimeDesktopBinding =
        bind(
            commandSender = sender,
            pointerSender = null,
            keySender = null,
        )

    fun bind(
        commandSender:
            (
                RuntimeBridgeWindowCommand,
            ) -> Result<Unit>,
        pointerSender:
            ((
                RuntimeBridgePointerEvent,
            ) -> Result<Unit>)?,
        keySender:
            ((
                RuntimeBridgeKeyEvent,
            ) -> Result<Unit>)?,
    ): RuntimeDesktopBinding {
        val id =
            synchronized(lock) {
                val allocated =
                    nextBindingId
                require(
                    allocated > 0L,
                ) {
                    "RUNTIME_DESKTOP_BINDING_ID_EXHAUSTED"
                }
                nextBindingId =
                    try {
                        Math.addExact(
                            allocated,
                            1L,
                        )
                    } catch (
                        error:
                            ArithmeticException
                    ) {
                        0L
                    }

                bindings[allocated] =
                    BindingState(
                        id = allocated,
                        commandSender =
                            commandSender,
                        pointerSender =
                            pointerSender,
                        keySender =
                            keySender,
                    )
                allocated
            }

        return RuntimeDesktopBinding(
            owner = this,
            bindingId = id,
        )
    }

    fun command(
        windowId: Long,
        command: Int,
    ): Result<Unit> =
        runCatching {
            val sender =
                synchronized(lock) {
                    ownerForLocked(
                        windowId,
                    ).commandSender
                }

            val value =
                RuntimeBridgeWindowCommand(
                    windowId = windowId,
                    command = command,
                )

            RuntimeDisplayBridgePayloadCodec
                .encodeWindowCommand(
                    value,
                )

            sender(value).getOrThrow()
        }

    fun activate(
        windowId: Long,
    ): Result<Unit> =
        command(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_ACTIVATE,
        )

    fun minimize(
        windowId: Long,
    ): Result<Unit> =
        command(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_MINIMIZE,
        )

    fun restore(
        windowId: Long,
    ): Result<Unit> =
        command(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_RESTORE,
        )

    fun maximize(
        windowId: Long,
    ): Result<Unit> =
        command(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_MAXIMIZE,
        )

    fun closeWindow(
        windowId: Long,
    ): Result<Unit> =
        command(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_CLOSE,
        )

    fun sendPointer(
        event: RuntimeBridgePointerEvent,
    ): Result<Unit> =
        runCatching {
            val sender =
                synchronized(lock) {
                    ownerForLocked(
                        event.windowId,
                    ).pointerSender
                        ?: error(
                            "RUNTIME_DESKTOP_POINTER_CHANNEL_MISSING",
                        )
                }

            RuntimeDisplayBridgePayloadCodec
                .encodePointerEvent(event)
            sender(event).getOrThrow()
        }

    fun sendKey(
        event: RuntimeBridgeKeyEvent,
    ): Result<Unit> =
        runCatching {
            val sender =
                synchronized(lock) {
                    ownerForLocked(
                        event.windowId,
                    ).keySender
                        ?: error(
                            "RUNTIME_DESKTOP_KEY_CHANNEL_MISSING",
                        )
                }

            RuntimeDisplayBridgePayloadCodec
                .encodeKeyEvent(event)
            sender(event).getOrThrow()
        }

    internal fun publish(
        bindingId: Long,
        snapshot:
            List<
                RuntimeDisplayCompositorWindow
            >,
    ): Boolean =
        synchronized(lock) {
            val binding =
                bindings[bindingId]
                    ?: return@synchronized false

            require(
                snapshot
                    .map {
                        it.windowId
                    }
                    .distinct()
                    .size ==
                    snapshot.size,
            ) {
                "RUNTIME_DESKTOP_WINDOW_DUPLICATE"
            }

            snapshot.forEach {
                window ->
                val owner =
                    windowOwners[
                        window.windowId
                    ]
                require(
                    owner == null ||
                        owner == bindingId,
                ) {
                    "RUNTIME_DESKTOP_WINDOW_OWNER_COLLISION"
                }
            }

            windowOwners
                .entries
                .removeAll {
                    it.value ==
                        bindingId
                }

            binding.windows =
                snapshot

            snapshot.forEach {
                window ->
                windowOwners[
                    window.windowId
                ] = bindingId
            }

            publishMergedLocked()
            true
        }

    internal fun release(
        bindingId: Long,
    ): Boolean =
        synchronized(lock) {
            if (
                bindings.remove(
                    bindingId,
                ) == null
            ) {
                return@synchronized false
            }

            windowOwners
                .entries
                .removeAll {
                    it.value ==
                        bindingId
                }
            publishMergedLocked()
            true
        }

    private fun ownerForLocked(
        windowId: Long,
    ): BindingState {
        val bindingId =
            windowOwners[windowId]
                ?: error(
                    "RUNTIME_DESKTOP_WINDOW_MISSING",
                )
        return bindings[bindingId]
            ?: error(
                "RUNTIME_DESKTOP_SESSION_MISSING",
            )
    }

    private fun publishMergedLocked() {
        val merged =
            bindings
                .values
                .flatMap {
                    binding ->
                    binding.windows
                        .map {
                            window ->
                            binding.id to
                                window
                        }
                }
                .sortedWith(
                    compareBy<
                        Pair<
                            Long,
                            RuntimeDisplayCompositorWindow
                        >
                    > {
                        it.second.topmost
                    }.thenBy {
                        it.first
                    }.thenBy {
                        it.second.zIndex
                    },
                )
                .mapIndexed {
                    index,
                    pair ->
                    pair.second.copy(
                        zIndex = index,
                    )
                }

        require(
            merged
                .map {
                    it.windowId
                }
                .distinct()
                .size ==
                merged.size,
        ) {
            "RUNTIME_DESKTOP_WINDOW_DUPLICATE_GLOBAL"
        }

        mutableWindows.value =
            merged
    }
}

class RuntimeDesktopBinding internal constructor(
    private val owner:
        RuntimeDesktopBridge,
    private val bindingId: Long,
) : Closeable {
    private var closed = false

    fun publish(
        snapshot:
            List<
                RuntimeDisplayCompositorWindow
            >,
    ): Boolean {
        check(!closed) {
            "RUNTIME_DESKTOP_BINDING_CLOSED"
        }
        return owner.publish(
            bindingId,
            snapshot,
        )
    }

    override fun close() {
        if (!closed) {
            closed = true
            owner.release(
                bindingId,
            )
        }
    }
}
