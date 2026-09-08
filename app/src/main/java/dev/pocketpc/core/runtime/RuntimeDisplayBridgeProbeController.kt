package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
            val structuralBlockers =
                basePlan.blockers.filterNot {
                    it ==
                        ProotExecutionController
                            .EXECUTION_APPROVAL_BLOCKER
                }

            if (
                !userApproved ||
                structuralBlockers.isNotEmpty() ||
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

            val runtimeIdentity =
                RuntimeExecutionIdentity.of(
                    runtime = runtime,
                    tools = tools,
                    layers = layers,
                )
            val session =
                RuntimeDisplayBridgeSessionFactory
                    .create(
                        runtimeIdentitySha256 =
                            runtimeIdentity,
                    )

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
                val blocked =
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
                        process = blocked,
                        bridgeAuthenticated =
                            false,
                        negotiatedCapabilities =
                            0,
                        bridgeError =
                            blocked.error,
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
            val accept =
                async(Dispatchers.IO) {
                    host.acceptAuthenticated()
                }

            val process =
                try {
                    executionController
                        .executeOneShot(
                            plan = plan,
                            userApproved = true,
                            timeoutMillis =
                                timeoutMillis,
                        )
                } finally {
                    if (
                        !accept.isCompleted
                    ) {
                        host.close()
                    }
                }

            val peerResult =
                runCatching {
                    accept.await()
                }.getOrElse { error ->
                    Result.failure(error)
                }

            host.close()

            val peer =
                peerResult.getOrNull()
            val negotiated =
                peer?.negotiatedCapabilities
                    ?: 0
            peer?.close()

            val authenticated =
                peerResult.isSuccess
            val bridgeError =
                peerResult.exceptionOrNull()
                    ?.message

            val annotatedProcess =
                if (authenticated) {
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
                                        .endsWith("\n")
                                ) {
                                    appendLine()
                                }
                                append(
                                    "POCKETPC_DISPLAY_BRIDGE_HOST_AUTH_OK caps=",
                                )
                                appendLine(
                                    negotiated,
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
                                separator = " | ",
                            ).ifBlank {
                                "DISPLAY_BRIDGE_HOST_FAILED"
                            },
                    )
                }

            RuntimeDisplayBridgeProbeResult(
                process =
                    annotatedProcess,
                bridgeAuthenticated =
                    authenticated,
                negotiatedCapabilities =
                    negotiated,
                bridgeError =
                    bridgeError,
            )
        }
}
