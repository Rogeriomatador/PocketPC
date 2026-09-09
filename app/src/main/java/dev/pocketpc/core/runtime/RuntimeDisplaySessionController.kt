package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class RuntimeDisplaySessionController(
    private val endpoint:
        RuntimeDisplayBridgeEndpoint,
    hostTempDirectory: File,
) : Closeable {
    private val closed =
        AtomicBoolean(false)
    private val processor =
        RuntimeDisplayHostProcessor(
            endpoint,
            hostTempDirectory,
        )
    private val compositor =
        RuntimeDisplayCompositorModel()

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

    suspend fun runSession():
        Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                while (
                    !closed.get() &&
                    currentCoroutineContext()
                        .isActive
                ) {
                    val step =
                        processor.processNext()
                            .getOrThrow()

                    compositor.apply(step)
                        .getOrThrow()
                    mutableWindows.value =
                        compositor.snapshot()

                    if (
                        step.presentedFrame !=
                            null
                    ) {
                        processor
                            .acknowledgePendingFrame()
                            .getOrThrow()
                    }
                }
            }.recoverCatching {
                if (closed.get()) {
                    Unit
                } else {
                    throw it
                }
            }
        }

    fun sendPointer(
        event: RuntimeBridgePointerEvent,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            processor.sendPointer(event)
        }

    fun sendKey(
        event: RuntimeBridgeKeyEvent,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            processor.sendKey(event)
        }

    override fun close() {
        if (
            closed.compareAndSet(
                false,
                true,
            )
        ) {
            processor.close()
            compositor.clear()
            mutableWindows.value =
                emptyList()

            (
                endpoint as?
                    Closeable
            )?.let {
                runCatching {
                    it.close()
                }
            }
        }
    }
}
