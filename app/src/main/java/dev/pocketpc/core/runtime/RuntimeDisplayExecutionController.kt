package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

data class RuntimeDisplayExecutionResult(
    val process: ProotExecutionResult,
    val bridgeAuthenticated: Boolean,
    val bridgeError: String?,
)

private sealed interface RuntimeDisplayCompletion {
    data class Process(
        val result:
            ProotExecutionResult,
    ) : RuntimeDisplayCompletion

    data class Bridge(
        val result:
            Result<Unit>,
    ) : RuntimeDisplayCompletion
}

class RuntimeDisplayExecutionController(
    private val executionController:
        ProotExecutionController,
) : Closeable {
    private val closed =
        AtomicBoolean(false)

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

    suspend fun execute(
        basePlan: ProotInvocationPlan,
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer>,
        userApproved: Boolean,
        handshakeTimeoutMillis:
            Long = 15_000L,
    ): RuntimeDisplayExecutionResult =
        coroutineScope {
            check(!closed.get()) {
                "DISPLAY_EXECUTION_CONTROLLER_CLOSED"
            }

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
                        .executeSession(
                            plan = basePlan,
                            userApproved =
                                userApproved,
                        )
                return@coroutineScope
                    RuntimeDisplayExecutionResult(
                        process = process,
                        bridgeAuthenticated =
                            false,
                        bridgeError =
                            "DISPLAY_SESSION_NOT_STARTED",
                    )
            }

            val hostTemp =
                basePlan.environment[
                    "PROOT_TMP_DIR"
                ]?.let(::File)
                    ?: return@coroutineScope
                        RuntimeDisplayExecutionResult(
                            process =
                                blockedResult(
                                    "HOST_TEMP_BIND_MISSING",
                                    "DISPLAY_BRIDGE_TEMP_MISSING",
                                ),
                            bridgeAuthenticated =
                                false,
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
                environmentErrors
                    .isNotEmpty()
            ) {
                return@coroutineScope
                    RuntimeDisplayExecutionResult(
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
                                    environmentErrors,
                                error =
                                    "DISPLAY_BRIDGE_ENVIRONMENT_INVALID",
                            ),
                        bridgeAuthenticated =
                            false,
                        bridgeError =
                            "DISPLAY_BRIDGE_ENVIRONMENT_INVALID",
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
            var displaySession:
                RuntimeDisplaySessionController? =
                null

            val acceptDeferred =
                async(Dispatchers.IO) {
                    host.acceptAuthenticated(
                        readTimeoutMillis = 0,
                    )
                }
            val processDeferred =
                async {
                    executionController
                        .executeSession(
                            plan = plan,
                            userApproved = true,
                        )
                }

            try {
                val peer =
                    try {
                        withTimeout(
                            handshakeTimeoutMillis
                                .coerceIn(
                                    1_000L,
                                    60_000L,
                                ),
                        ) {
                            acceptDeferred.await()
                                .getOrThrow()
                        }
                    } catch (
                        cancellation:
                            CancellationException
                    ) {
                        throw cancellation
                    } catch (
                        failure: Throwable
                    ) {
                        executionController
                            .stopActive()
                        val process =
                            processDeferred.await()
                        return@coroutineScope
                            RuntimeDisplayExecutionResult(
                                process = process,
                                bridgeAuthenticated =
                                    false,
                                bridgeError =
                                    "DISPLAY_BRIDGE_HANDSHAKE_FAILED:" +
                                        (
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                        ),
                            )
                    }

                val activeSession =
                    RuntimeDisplaySessionController(
                        endpoint = peer,
                        hostTempDirectory =
                            hostTemp,
                    )
                displaySession =
                    activeSession

                val collector =
                    launch {
                        activeSession.windows
                            .collect {
                                windows ->
                                mutableWindows.value =
                                    windows
                            }
                    }
                val bridgeDeferred =
                    async {
                        activeSession
                            .runSession()
                    }

                val first =
                    select<RuntimeDisplayCompletion> {
                        processDeferred
                            .onAwait {
                                RuntimeDisplayCompletion
                                    .Process(it)
                            }
                        bridgeDeferred
                            .onAwait {
                                RuntimeDisplayCompletion
                                    .Bridge(it)
                            }
                    }

                val result =
                    when (first) {
                        is RuntimeDisplayCompletion.Process -> {
                            activeSession.close()
                            runCatching {
                                bridgeDeferred.await()
                            }
                            RuntimeDisplayExecutionResult(
                                process =
                                    first.result,
                                bridgeAuthenticated =
                                    true,
                                bridgeError =
                                    null,
                            )
                        }

                        is RuntimeDisplayCompletion.Bridge -> {
                            val bridgeFailure =
                                first.result
                                    .exceptionOrNull()
                            if (
                                processDeferred
                                    .isActive
                            ) {
                                executionController
                                    .stopActive()
                            }
                            val process =
                                processDeferred.await()

                            RuntimeDisplayExecutionResult(
                                process = process,
                                bridgeAuthenticated =
                                    true,
                                bridgeError =
                                    bridgeFailure
                                        ?.let {
                                            "DISPLAY_BRIDGE_SESSION_FAILED:" +
                                                (
                                                    it.message
                                                        ?: it
                                                            .javaClass
                                                            .simpleName
                                                )
                                        }
                                        ?: "DISPLAY_BRIDGE_SESSION_ENDED_BEFORE_PROCESS",
                            )
                        }
                    }

                collector.cancelAndJoin()
                activeSession.close()
                mutableWindows.value =
                    emptyList()
                result
            } finally {
                displaySession?.close()
                host.close()
                if (
                    !acceptDeferred
                        .isCompleted
                ) {
                    acceptDeferred.cancel()
                }
                mutableWindows.value =
                    emptyList()
            }
        }

    fun stopActive(): Boolean =
        executionController.stopActive()

    override fun close() {
        if (
            closed.compareAndSet(
                false,
                true,
            )
        ) {
            executionController
                .stopActive()
            mutableWindows.value =
                emptyList()
        }
    }

    private fun blockedResult(
        blocker: String,
        error: String,
    ) =
        ProotExecutionResult(
            state =
                ProotExecutionState.BLOCKED,
            started = false,
            exitCode = null,
            output = "",
            outputTruncated = false,
            blockers =
                listOf(blocker),
            error = error,
        )
}
