package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import dev.pocketpc.core.performance.RuntimePerformanceController
import kotlinx.coroutines.delay
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
    desktopBridge:
        RuntimeDesktopBridge? = null,
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
    // Both the guest socket coroutine and the external Vulkan broker publish
    // into the same mutable compositor. Apply+publish must be atomic.
    private val compositorLock = Any()
    private val softwareFramePacer = RuntimeDisplayFramePacer()
    private val inputDispatcher =
        RuntimeDisplayInputDispatcher(
            transmit = { command ->
                when (command) {
                    is RuntimeDisplayOutboundInput.Pointer ->
                        processor.sendPointer(command.value)
                    is RuntimeDisplayOutboundInput.Key ->
                        processor.sendKey(command.value)
                    is RuntimeDisplayOutboundInput.Window ->
                        processor.sendWindowCommand(command.value)
                }
            },
            onFailure = {
                // Closing the socket interrupts a stalled guest read/write;
                // the session loop then exits and releases its windows.
                (endpoint as? Closeable)?.close()
            },
        )
    private val desktopBinding =
        desktopBridge?.bind(
            commandSender = {
                command ->
                runCatching {
                    check(!closed.get()) {
                        "DISPLAY_SESSION_CLOSED"
                    }
                    inputDispatcher.window(command).getOrThrow()
                }
            },
            pointerSender = {
                event ->
                runCatching {
                    check(!closed.get()) {
                        "DISPLAY_SESSION_CLOSED"
                    }
                    inputDispatcher.pointer(event).getOrThrow()
                }
            },
            keySender = {
                event ->
                runCatching {
                    check(!closed.get()) {
                        "DISPLAY_SESSION_CLOSED"
                    }
                    inputDispatcher.key(event).getOrThrow()
                }
            },
            externalVulkanFrameSender = {
                value ->
                applyExternalVulkanFrame(value)
            },
        )

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

                    synchronized(compositorLock) {
                        if (!closed.get()) {
                            compositor.apply(step).getOrThrow()
                            publishSnapshot()
                        }
                    }

                    if (
                        step.presentedFrame !=
                            null
                    ) {
                        // Acknowledge after an adjustable software-frame
                        // budget. This applies backpressure to the guest and
                        // keeps heavy frame copies off the UI thread.
                        val requestedRate =
                            RuntimePerformanceController.current()
                                .effectiveDxvkFrameRate()
                        val waitMillis =
                            softwareFramePacer.recommendedDelayMillis(
                                nowNanos = System.nanoTime(),
                                requestedFramesPerSecond = requestedRate,
                                frameWidth = step.presentedFrame.pixels.width,
                                frameHeight = step.presentedFrame.pixels.height,
                            )
                        if (waitMillis > 0L) delay(waitMillis)
                        processor
                            .acknowledgePendingFrame()
                            .getOrThrow()
                        softwareFramePacer.recordAcknowledgement(System.nanoTime())
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

    /**
     * Accepts one exact-window Vulkan readback routed by [RuntimeDesktopBridge].
     * The compositor keeps PVI1 identity separate from the legacy bridge frame
     * namespace. Publishing this snapshot updates the desktop model only; it is
     * not physical evidence that Android drew the pixels to the screen.
     */
    internal fun applyExternalVulkanFrame(
        value: RuntimeDesktopExternalVulkanFrame,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            require(value.structurallyValid) {
                "DISPLAY_SESSION_EXTERNAL_VULKAN_FRAME_INVALID"
            }
            synchronized(compositorLock) {
                check(!closed.get()) {
                    "DISPLAY_SESSION_CLOSED"
                }
                require(
                    windows.value.any {
                        it.windowId == value.windowId
                    },
                ) {
                    "DISPLAY_SESSION_WINDOW_MISSING"
                }
                compositor.applyExternalVulkanFrame(
                    windowId = value.windowId,
                    identity = value.identity,
                    frame = value.frame,
                ).getOrThrow()
                publishSnapshot()
            }

            RuntimeGraphicsEvidenceLog.desktopModelFrameDelivered(
                windowId = value.windowId,
                resourceId = value.identity.resourceId,
                generation = value.identity.generation,
                sequence = value.identity.sequence,
                width = value.frame.width,
                height = value.frame.height,
            )
        }

    fun sendPointer(
        event: RuntimeBridgePointerEvent,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            inputDispatcher.pointer(event).getOrThrow()
        }

    fun sendKey(
        event: RuntimeBridgeKeyEvent,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            inputDispatcher.key(event).getOrThrow()
        }

    fun commandWindow(
        windowId: Long,
        command: Int,
    ): Result<Unit> =
        runCatching {
            check(!closed.get()) {
                "DISPLAY_SESSION_CLOSED"
            }
            require(
                windows.value.any {
                    it.windowId ==
                        windowId
                },
            ) {
                "DISPLAY_SESSION_WINDOW_MISSING"
            }
            inputDispatcher.window(
                RuntimeBridgeWindowCommand(
                    windowId = windowId,
                    command = command,
                ),
            ).getOrThrow()
        }

    fun activateWindow(
        windowId: Long,
    ): Result<Unit> =
        commandWindow(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_ACTIVATE,
        )

    fun minimizeWindow(
        windowId: Long,
    ): Result<Unit> =
        commandWindow(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_MINIMIZE,
        )

    fun restoreWindow(
        windowId: Long,
    ): Result<Unit> =
        commandWindow(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_RESTORE,
        )

    fun maximizeWindow(
        windowId: Long,
    ): Result<Unit> =
        commandWindow(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_MAXIMIZE,
        )

    fun closeWindow(
        windowId: Long,
    ): Result<Unit> =
        commandWindow(
            windowId,
            RuntimeDisplayBridgePayloadCodec
                .WINDOW_COMMAND_CLOSE,
        )

    override fun close() {
        if (
            closed.compareAndSet(
                false,
                true,
            )
        ) {
            inputDispatcher.close()
            // Close the socket BEFORE taking the processor's synchronized
            // lock: processNext may be blocked inside a socket read.
            // Reversing the order risks a lifecycle/main-thread deadlock.
            (endpoint as? Closeable)?.let {
                runCatching { it.close() }
            }
            processor.close()
            synchronized(compositorLock) {
                compositor.clear()
                mutableWindows.value = emptyList()
                desktopBinding?.close()
            }
        }
    }

    private fun publishSnapshot() {
        val snapshot =
            compositor.snapshot()
        mutableWindows.value =
            snapshot
        desktopBinding
            ?.publish(
                snapshot,
            )
    }
}
