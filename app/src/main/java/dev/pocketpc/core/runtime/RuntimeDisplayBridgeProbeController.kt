package dev.pocketpc.core.runtime

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

data class RuntimeDisplayBridgeProbeResult(
    val process: ProotExecutionResult,
    val bridgeAuthenticated: Boolean,
    val negotiatedCapabilities: Int,
    val bridgeError: String?,
    val previewFrame:
        RuntimeDisplayFramePixels? =
        null,
)

enum class RuntimeDisplayBridgeProbeMode {
    GENERIC_PATTERN,
    WINE_DRIVER_WINDOW,
}

class RuntimeDisplayBridgeProbeController(
    private val executionController:
        ProotExecutionController,
) {
    suspend fun execute(
        basePlan: ProotInvocationPlan,
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
        userApproved: Boolean,
        timeoutMillis: Long = 15_000L,
        desktopBridge:
            RuntimeDesktopBridge? = null,
        mode:
            RuntimeDisplayBridgeProbeMode =
            RuntimeDisplayBridgeProbeMode
                .GENERIC_PATTERN,
    ): RuntimeDisplayBridgeProbeResult =
        coroutineScope<RuntimeDisplayBridgeProbeResult> {
            val structural =
                basePlan.blockers.filterNot {
                    it ==
                        ProotExecutionController
                            .EXECUTION_APPROVAL_BLOCKER
                }

            if (
                !userApproved ||
                structural.isNotEmpty() ||
                basePlan.argv.isEmpty()
            ) {
                val process =
                    executionController
                        .executeOneShot(
                            plan = basePlan,
                            userApproved =
                                userApproved,
                            timeoutMillis =
                                timeoutMillis,
                        )
                return@coroutineScope
                    RuntimeDisplayBridgeProbeResult(
                        process = process,
                        bridgeAuthenticated =
                            false,
                        negotiatedCapabilities =
                            0,
                        bridgeError =
                            "DISPLAY_BRIDGE_NOT_STARTED",
                    )
            }

            val hostTemp =
                basePlan.environment[
                    "PROOT_TMP_DIR"
                ]?.let(::File)
                    ?: return@coroutineScope
                        RuntimeDisplayBridgeProbeResult(
                            process =
                                ProotExecutionResult(
                                    state =
                                        ProotExecutionState
                                            .BLOCKED,
                                    started = false,
                                    exitCode = null,
                                    output = "",
                                    outputTruncated =
                                        false,
                                    blockers =
                                        listOf(
                                            "HOST_TEMP_BIND_MISSING",
                                        ),
                                    error =
                                        "DISPLAY_BRIDGE_TEMP_MISSING",
                                ),
                            bridgeAuthenticated =
                                false,
                            negotiatedCapabilities =
                                0,
                            bridgeError =
                                "DISPLAY_BRIDGE_TEMP_MISSING",
                        )

            val identity =
                RuntimeExecutionIdentity.of(
                    runtime,
                    tools,
                    layers,
                )
            val session =
                RuntimeDisplayBridgeSessionFactory
                    .create(identity)
            val environment =
                LinkedHashMap(
                    basePlan.environment,
                ).apply {
                    putAll(
                        session.environment(),
                    )
                }
            val environmentErrors =
                RuntimeEnvironment.validate(
                    environment,
                )
            if (
                environmentErrors.isNotEmpty()
            ) {
                val process =
                    ProotExecutionResult(
                        state =
                            ProotExecutionState
                                .BLOCKED,
                        started = false,
                        exitCode = null,
                        output = "",
                        outputTruncated =
                            false,
                        blockers =
                            environmentErrors,
                        error =
                            "DISPLAY_BRIDGE_ENVIRONMENT_INVALID",
                    )
                return@coroutineScope
                    RuntimeDisplayBridgeProbeResult(
                        process,
                        false,
                        0,
                        process.error,
                    )
            }

            val plan =
                basePlan.copy(
                    environment =
                        environment,
                )
            val host =
                RuntimeDisplayBridgeHost(
                    session,
                )
            val acceptDeferred =
                async(Dispatchers.IO) {
                    host.acceptAuthenticated()
                }
            val processDeferred =
                async {
                    executionController
                        .executeOneShot(
                            plan = plan,
                            userApproved = true,
                            timeoutMillis =
                                timeoutMillis,
                        )
                }

            var peer:
                RuntimeDisplayBridgePeer? =
                null
            var processor:
                RuntimeDisplayHostProcessor? =
                null
            var desktopBinding:
                RuntimeDesktopBinding? =
                null
            var compositor:
                RuntimeDisplayCompositorModel? =
                null
            var negotiated = 0
            var authenticated = false
            var roundTrip = false
            var frameValidated = false
            var previewFrame:
                RuntimeDisplayFramePixels? =
                null
            var bridgeError: String? = null

            try {
                val peerResult =
                    withTimeout(
                        timeoutMillis,
                    ) {
                        acceptDeferred.await()
                    }
                peer =
                    peerResult.getOrThrow()
                authenticated = true
                negotiated =
                    peer.negotiatedCapabilities

                val activeProcessor =
                    RuntimeDisplayHostProcessor(
                        endpoint = peer,
                        hostTempDirectory =
                            hostTemp,
                    )
                processor =
                    activeProcessor
                val activeCompositor =
                    RuntimeDisplayCompositorModel()
                compositor =
                    activeCompositor
                desktopBinding =
                    desktopBridge?.bind(
                        commandSender = {
                            command ->
                            runCatching {
                                activeProcessor
                                    .sendWindowCommand(
                                        command,
                                    )
                            }
                        },
                        pointerSender = {
                            event ->
                            runCatching {
                                activeProcessor
                                    .sendPointer(
                                        event,
                                    )
                            }
                        },
                        keySender = {
                            event ->
                            runCatching {
                                activeProcessor
                                    .sendKey(
                                        event,
                                    )
                            }
                        },
                    )

                fun publishStep(
                    step:
                        RuntimeDisplayHostStep,
                ) {
                    activeCompositor
                        .apply(step)
                        .getOrThrow()
                    desktopBinding
                        ?.publish(
                            activeCompositor
                                .snapshot(),
                        )
                }

                val createStep =
                    activeProcessor
                        .processNext()
                        .getOrThrow()
                publishStep(
                    createStep,
                )
                val window =
                    (
                        createStep.event as?
                            RuntimeDisplayBridgeEvent
                                .WindowCreated
                        )?.window
                        ?: error(
                            "DISPLAY_BRIDGE_EXPECTED_WINDOW_CREATE",
                        )

                val surfaceStep =
                    activeProcessor
                        .processNext()
                        .getOrThrow()
                publishStep(
                    surfaceStep,
                )
                val surfaceRequest =
                    (
                        surfaceStep.event as?
                            RuntimeDisplayBridgeEvent
                                .SurfaceRequested
                        )?.request
                        ?: error(
                            "DISPLAY_BRIDGE_EXPECTED_SURFACE_REQUEST",
                        )

                require(
                    surfaceRequest.windowId ==
                        window.windowId
                ) {
                    "DISPLAY_BRIDGE_SURFACE_REQUEST_WINDOW_MISMATCH"
                }

                val geometryStep =
                    activeProcessor
                        .processNext()
                        .getOrThrow()
                publishStep(
                    geometryStep,
                )
                require(
                    geometryStep.event is
                        RuntimeDisplayBridgeEvent
                            .WindowGeometryChanged
                ) {
                    "DISPLAY_BRIDGE_EXPECTED_WINDOW_GEOMETRY"
                }

                require(
                    activeProcessor
                        .windowsSnapshot()
                        .singleOrNull()
                        ?.geometry
                        ?.visible ==
                        true
                ) {
                    "DISPLAY_BRIDGE_WINDOW_NOT_VISIBLE"
                }

                val frameStep =
                    activeProcessor
                        .processNext()
                        .getOrThrow()
                publishStep(
                    frameStep,
                )
                val presented =
                    frameStep
                        .presentedFrame
                        ?: error(
                            "DISPLAY_BRIDGE_EXPECTED_FRAME_READY",
                        )

                require(
                    presented.ready.frameId ==
                        1L &&
                        presented.ready.windowId ==
                            window.windowId
                ) {
                    "DISPLAY_BRIDGE_FRAME_READY_IDENTITY_MISMATCH"
                }

                when (mode) {
                    RuntimeDisplayBridgeProbeMode
                        .GENERIC_PATTERN ->
                        validateSmokeFrame(
                            presented.pixels,
                        ).getOrThrow()

                    RuntimeDisplayBridgeProbeMode
                        .WINE_DRIVER_WINDOW ->
                        validateWineDriverFrame(
                            presented.pixels,
                        ).getOrThrow()
                }
                previewFrame =
                    presented.pixels
                frameValidated = true

                activeProcessor.sendPointer(
                    RuntimeBridgePointerEvent(
                        windowId =
                            window.windowId,
                        action = 1,
                        x = 100,
                        y = 80,
                        buttons = 1,
                        verticalScroll =
                            0,
                        modifiers = 0,
                    ),
                )
                activeProcessor.sendKey(
                    RuntimeBridgeKeyEvent(
                        windowId =
                            window.windowId,
                        action = 1,
                        keyCode = 65,
                        scanCode = 30,
                        modifiers = 0,
                        repeatCount = 0,
                    ),
                )
                activeProcessor
                    .acknowledgePendingFrame()
                    .getOrThrow()

                if (
                    mode ==
                    RuntimeDisplayBridgeProbeMode
                        .GENERIC_PATTERN
                ) {
                    activeProcessor
                        .sendWindowCommand(
                            RuntimeBridgeWindowCommand(
                                windowId =
                                    window.windowId,
                                command =
                                    RuntimeDisplayBridgePayloadCodec
                                        .WINDOW_COMMAND_CLOSE,
                            ),
                        )

                    val destroyStep =
                        activeProcessor
                            .processNext()
                            .getOrThrow()
                    publishStep(
                        destroyStep,
                    )
                    require(
                        destroyStep.event is
                            RuntimeDisplayBridgeEvent
                                .WindowDestroyed
                    ) {
                        "DISPLAY_BRIDGE_EXPECTED_WINDOW_DESTROY"
                    }
                } else {
                    var destroyed = false
                    for (
                        eventIndex in
                        0 until
                            MAX_WINE_POST_INPUT_EVENTS
                    ) {
                        val step =
                            activeProcessor
                                .processNext()
                                .getOrThrow()
                        publishStep(step)

                        if (
                            step.presentedFrame !=
                                null
                        ) {
                            activeProcessor
                                .acknowledgePendingFrame()
                                .getOrThrow()
                        }

                        if (
                            step.event is
                                RuntimeDisplayBridgeEvent
                                    .WindowDestroyed
                        ) {
                            destroyed = true
                            break
                        }
                    }
                    require(destroyed) {
                        "WINE_DRIVER_WINDOW_DESTROY_NOT_OBSERVED"
                    }
                }

                require(
                    activeProcessor
                        .windowsSnapshot()
                        .isEmpty() &&
                        activeProcessor
                            .surfacesSnapshot()
                            .isEmpty(),
                ) {
                    "DISPLAY_BRIDGE_WINDOW_DESTROY_FAILED"
                }
                roundTrip = true
            } catch (error: Throwable) {
                bridgeError =
                    error.message
                        ?: error.javaClass
                            .simpleName
            } finally {
                desktopBinding?.close()
                compositor?.clear()
                processor?.close()
                peer?.close()
                host.close()
            }

            val process =
                processDeferred.await()

            val success =
                authenticated &&
                    roundTrip &&
                    frameValidated

            val annotated =
                if (success) {
                    process.copy(
                        output =
                            buildString {
                                append(
                                    process.output,
                                )
                                if (
                                    process.output
                                        .isNotEmpty() &&
                                    !process.output
                                        .endsWith(
                                            "\n",
                                        )
                                ) {
                                    appendLine()
                                }
                                appendLine(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK caps=" +
                                        negotiated,
                                )
                                appendLine(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_WINDOW_OK",
                                )
                                appendLine(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_FRAMEBUFFER_OK",
                                )
                                appendLine(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_INPUT_OK",
                                )
                                if (
                                    mode ==
                                    RuntimeDisplayBridgeProbeMode
                                        .GENERIC_PATTERN
                                ) {
                                    appendLine(
                                        "POCKETPC_DISPLAY_BRIDGE_HOST_WINDOW_COMMAND_OK",
                                    )
                                }
                                if (
                                    mode ==
                                    RuntimeDisplayBridgeProbeMode
                                        .WINE_DRIVER_WINDOW
                                ) {
                                    appendLine(
                                        "POCKETPC_WINE_DRIVER_HOST_FRAME_OK",
                                    )
                                    appendLine(
                                        "POCKETPC_WINE_DRIVER_HOST_INPUT_OK",
                                    )
                                }
                                appendLine(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_ROUNDTRIP_OK",
                                )
                            },
                    )
                } else {
                    process.copy(
                        error =
                            listOfNotNull(
                                process.error,
                                bridgeError
                                    ?.let {
                                        "DISPLAY_BRIDGE_HOST_FAILED:" +
                                            it
                                    },
                            ).joinToString(
                                " | ",
                            ).ifBlank {
                                "DISPLAY_BRIDGE_HOST_FAILED"
                            },
                    )
                }

            RuntimeDisplayBridgeProbeResult(
                process = annotated,
                bridgeAuthenticated =
                    success,
                negotiatedCapabilities =
                    negotiated,
                bridgeError =
                    bridgeError,
                previewFrame =
                    if (success) {
                        previewFrame
                    } else {
                        null
                    },
            )
        }

    private fun validateSmokeFrame(
        frame:
            RuntimeDisplayFramePixels,
    ): Result<Unit> =
        runCatching {
            require(
                frame.width == 64 &&
                    frame.height == 64 &&
                    frame.argb.size ==
                        64 * 64,
            ) {
                "DISPLAY_BRIDGE_FRAMEBUFFER_SIZE_INVALID"
            }

            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val expected =
                        (
                            0xff shl 24
                        ) or
                            (
                                (x xor y) shl
                                    16
                            ) or
                            (
                                y shl 8
                            ) or
                            x
                    require(
                        frame.argb[
                            y * 64 + x
                        ] == expected,
                    ) {
                        "DISPLAY_BRIDGE_FRAMEBUFFER_PATTERN_MISMATCH:" +
                            x +
                            "," +
                            y
                    }
                }
            }
        }


    private fun validateWineDriverFrame(
        frame:
            RuntimeDisplayFramePixels,
    ): Result<Unit> =
        runCatching {
            require(
                frame.width >= 64 &&
                    frame.height >= 64 &&
                    frame.argb.size ==
                        frame.width *
                            frame.height,
            ) {
                "WINE_DRIVER_FRAMEBUFFER_SIZE_INVALID"
            }

            val blueRgb =
                (24 shl 16) or
                    (96 shl 8) or
                    210
            val orangeRgb =
                (224 shl 16) or
                    (92 shl 8) or
                    28
            var blue = 0
            var orange = 0

            frame.argb.forEach {
                pixel ->
                when (
                    pixel and
                        0x00ffffff
                ) {
                    blueRgb ->
                        blue += 1
                    orangeRgb ->
                        orange += 1
                }
            }

            val minimumEach =
                maxOf(
                    16,
                    frame.argb.size /
                        20,
                )
            require(
                blue >= minimumEach &&
                    orange >= minimumEach,
            ) {
                "WINE_DRIVER_FRAMEBUFFER_PATTERN_MISMATCH:" +
                    "blue=" +
                    blue +
                    ":orange=" +
                    orange +
                    ":minimum=" +
                    minimumEach
            }
        }
    companion object {
        private const val
            MAX_WINE_POST_INPUT_EVENTS =
            32
    }


}
