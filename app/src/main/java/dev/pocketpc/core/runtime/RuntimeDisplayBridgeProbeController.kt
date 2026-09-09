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
)

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
    ): RuntimeDisplayBridgeProbeResult =
        coroutineScope {
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
            val framebuffer =
                RuntimeDisplaySharedFramebuffer
                    .createSmoke(
                        hostTempDirectory =
                            hostTemp,
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
            var negotiated = 0
            var authenticated = false
            var roundTrip = false
            var frameValidated = false
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

                val machine =
                    RuntimeDisplayBridgeStateMachine(
                        negotiated,
                    )

                val create =
                    peer.readFrame()
                        .getOrThrow()
                val createEvent =
                    machine.apply(create)
                        .getOrThrow()
                val window =
                    (
                        createEvent as?
                            RuntimeDisplayBridgeEvent
                                .WindowCreated
                        )?.window
                        ?: error(
                            "DISPLAY_BRIDGE_EXPECTED_WINDOW_CREATE",
                        )

                val geometry =
                    peer.readFrame()
                        .getOrThrow()
                machine.apply(
                    geometry,
                ).getOrThrow()

                require(
                    machine.snapshot()
                        .singleOrNull()
                        ?.geometry
                        ?.visible ==
                        true
                ) {
                    "DISPLAY_BRIDGE_WINDOW_NOT_VISIBLE"
                }

                require(
                    window.windowId ==
                        framebuffer
                            .descriptor
                            .surface
                            .windowId
                ) {
                    "DISPLAY_BRIDGE_FRAMEBUFFER_WINDOW_MISMATCH"
                }

                peer.sendSurfaceAvailable(
                    framebuffer
                        .descriptor
                        .surface,
                )

                val frameReady =
                    peer.readFrame()
                        .getOrThrow()
                val readyEvent =
                    machine.apply(
                        frameReady,
                    ).getOrThrow()
                val ready =
                    (
                        readyEvent as?
                            RuntimeDisplayBridgeEvent
                                .FrameReady
                        )?.frame
                        ?: error(
                            "DISPLAY_BRIDGE_EXPECTED_FRAME_READY",
                        )

                val surface =
                    framebuffer
                        .descriptor
                        .surface
                require(
                    ready.windowId ==
                        surface.windowId &&
                        ready.surfaceId ==
                        surface.surfaceId &&
                        ready.generation ==
                        surface.generation &&
                        ready.frameId == 1L
                ) {
                    "DISPLAY_BRIDGE_FRAME_READY_IDENTITY_MISMATCH"
                }

                framebuffer
                    .validateSmokePattern()
                    .getOrThrow()
                frameValidated = true

                peer.sendPointer(
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
                peer.sendKey(
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
                peer.sendFramePresented(
                    RuntimeBridgeFramePresented(
                        windowId =
                            window.windowId,
                        frameId =
                            ready.frameId,
                        status = 0,
                    ),
                )

                val destroy =
                    peer.readFrame()
                        .getOrThrow()
                machine.apply(
                    destroy,
                ).getOrThrow()
                require(
                    machine.snapshot()
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
                peer?.close()
                host.close()
                framebuffer.close()
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
            )
        }
}
