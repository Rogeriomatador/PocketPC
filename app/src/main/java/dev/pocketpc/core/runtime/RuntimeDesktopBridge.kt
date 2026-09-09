package dev.pocketpc.core.runtime

import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RuntimeDesktopBridge {
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
    private var activeBindingId = 0L
    private var commandSender:
        ((
            RuntimeBridgeWindowCommand,
        ) -> Result<Unit>)? =
        null

    fun bind(
        sender:
            (
                RuntimeBridgeWindowCommand,
            ) -> Result<Unit>,
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
                activeBindingId =
                    allocated
                commandSender =
                    sender
                mutableWindows.value =
                    emptyList()
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
                    require(
                        activeBindingId >
                            0L &&
                            commandSender !=
                                null
                    ) {
                        "RUNTIME_DESKTOP_SESSION_MISSING"
                    }
                    require(
                        mutableWindows.value
                            .any {
                                it.windowId ==
                                    windowId
                            },
                    ) {
                        "RUNTIME_DESKTOP_WINDOW_MISSING"
                    }
                    commandSender
                } ?: error(
                    "RUNTIME_DESKTOP_SESSION_MISSING",
                )

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

    internal fun publish(
        bindingId: Long,
        snapshot:
            List<
                RuntimeDisplayCompositorWindow
            >,
    ): Boolean =
        synchronized(lock) {
            if (
                bindingId !=
                    activeBindingId
            ) {
                return@synchronized false
            }

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

            mutableWindows.value =
                snapshot
                    .sortedBy {
                        it.zIndex
                    }
            true
        }

    internal fun release(
        bindingId: Long,
    ): Boolean =
        synchronized(lock) {
            if (
                bindingId !=
                    activeBindingId
            ) {
                return@synchronized false
            }

            activeBindingId = 0L
            commandSender = null
            mutableWindows.value =
                emptyList()
            true
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
