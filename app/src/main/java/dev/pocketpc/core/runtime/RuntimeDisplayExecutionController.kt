package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val authenticatedPeerCount: Int = 0,
)

private sealed interface RuntimeDisplayStartup {
    data class Peer(
        val result:
            Result<RuntimeDisplayBridgePeer>,
    ) : RuntimeDisplayStartup

    data class Process(
        val result:
            ProotExecutionResult,
    ) : RuntimeDisplayStartup
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
        desktopBridge:
            RuntimeDesktopBridge? = null,
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
            val desktopMultiplexer =
                desktopBridge
                    ?: RuntimeDesktopBridge()
            val stateLock = Any()
            val activeSessions =
                LinkedHashMap<
                    Int,
                    RuntimeDisplaySessionController
                >()
            val activeJobs =
                LinkedHashMap<
                    Int,
                    Job
                >()
            val peerFailures =
                mutableListOf<String>()
            var nextPeerId = 1
            var authenticatedPeerCount = 0
            var lastPeerActivityNanos =
                System.nanoTime()
            var acceptLoop: Job? = null

            val windowCollector =
                launch {
                    desktopMultiplexer
                        .windows
                        .collect {
                            windows ->
                            windows.forEach {
                                window ->
                                val processPid =
                                    window.windowId
                                        ushr 32
                                if (
                                    processPid >
                                        0L &&
                                    processPid <=
                                        Int.MAX_VALUE
                                ) {
                                    executionController
                                        .associateActiveFamilyPid(
                                            processPid,
                                        )
                                }
                            }

                            mutableWindows.value =
                                windows
                        }
                }

            fun rememberFailure(
                value: String,
            ) {
                synchronized(stateLock) {
                    if (
                        peerFailures.size <
                            MAX_RECORDED_PEER_FAILURES
                    ) {
                        peerFailures +=
                            value
                    }
                }
            }

            fun startPeer(
                peer:
                    RuntimeDisplayBridgePeer,
            ) {
                val peerId =
                    synchronized(stateLock) {
                        if (
                            authenticatedPeerCount >=
                                MAX_AUTHENTICATED_PEERS
                        ) {
                            0
                        } else {
                            val allocated =
                                nextPeerId
                            nextPeerId += 1
                            authenticatedPeerCount +=
                                1
                            lastPeerActivityNanos =
                                System.nanoTime()
                            allocated
                        }
                    }

                if (peerId == 0) {
                    rememberFailure(
                        "DISPLAY_BRIDGE_PEER_LIMIT_REACHED",
                    )
                    peer.close()
                    return
                }

                val activeSession =
                    try {
                        RuntimeDisplaySessionController(
                            endpoint = peer,
                            hostTempDirectory =
                                hostTemp,
                            desktopBridge =
                                desktopMultiplexer,
                        )
                    } catch (
                        failure: Throwable
                    ) {
                        rememberFailure(
                            "DISPLAY_BRIDGE_PEER_SESSION_CREATE_FAILED:" +
                                (
                                    failure.message
                                        ?: failure
                                            .javaClass
                                            .simpleName
                                ),
                        )
                        peer.close()
                        return
                    }

                synchronized(stateLock) {
                    activeSessions[
                        peerId
                    ] = activeSession
                }

                val job =
                    launch {
                        val result =
                            activeSession
                                .runSession()
                        result
                            .exceptionOrNull()
                            ?.let {
                                failure ->
                                rememberFailure(
                                    "DISPLAY_BRIDGE_PEER_SESSION_FAILED:" +
                                        (
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                        ),
                                )
                            }

                        activeSession.close()
                        synchronized(
                            stateLock,
                        ) {
                            activeSessions
                                .remove(
                                    peerId,
                                )
                            activeJobs
                                .remove(
                                    peerId,
                                )
                            lastPeerActivityNanos =
                                System.nanoTime()
                        }
                    }

                synchronized(stateLock) {
                    activeJobs[peerId] =
                        job
                }
            }

            val handshakeSocketTimeoutMillis =
                handshakeTimeoutMillis
                    .coerceIn(
                        1_000L,
                        30_000L,
                    )
                    .toInt()

            val acceptDeferred =
                async(Dispatchers.IO) {
                    host.acceptAuthenticated(
                        readTimeoutMillis =
                            handshakeSocketTimeoutMillis,
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
                val startup =
                    try {
                        withTimeout(
                            handshakeTimeoutMillis
                                .coerceIn(
                                    1_000L,
                                    60_000L,
                                ),
                        ) {
                            select<
                                RuntimeDisplayStartup
                            > {
                                acceptDeferred
                                    .onAwait {
                                        RuntimeDisplayStartup
                                            .Peer(it)
                                    }
                                processDeferred
                                    .onAwait {
                                        RuntimeDisplayStartup
                                            .Process(it)
                                    }
                            }
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
                                    "DISPLAY_BRIDGE_HANDSHAKE_TIMEOUT_OR_FAILURE:" +
                                        (
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                        ),
                            )
                    }

                val rootProcessResult =
                    (
                        startup as?
                            RuntimeDisplayStartup.Process
                    )?.result

                val firstPeerResult =
                    if (
                        rootProcessResult != null
                    ) {
                        try {
                            withTimeout(
                                PEER_HANDOFF_GRACE_MILLIS,
                            ) {
                                acceptDeferred.await()
                            }
                        } catch (
                            cancellation:
                                CancellationException
                        ) {
                            throw cancellation
                        } catch (
                            failure: Throwable
                        ) {
                            Result.failure(
                                IllegalStateException(
                                    "DISPLAY_CHILD_PEER_HANDOFF_TIMEOUT:" +
                                        (
                                            failure.message
                                                ?: failure
                                                    .javaClass
                                                    .simpleName
                                        ),
                                ),
                            )
                        }
                    } else {
                        (
                            startup as
                                RuntimeDisplayStartup.Peer
                        ).result
                    }

                val firstPeer =
                    firstPeerResult
                        .getOrElse {
                            failure ->
                            if (
                                processDeferred
                                    .isActive
                            ) {
                                executionController
                                    .stopActive()
                            }
                            val process =
                                rootProcessResult
                                    ?: processDeferred
                                        .await()
                            return@coroutineScope
                                RuntimeDisplayExecutionResult(
                                    process =
                                        process,
                                    bridgeAuthenticated =
                                        false,
                                    bridgeError =
                                        if (
                                            rootProcessResult !=
                                                null
                                        ) {
                                            "DISPLAY_PROCESS_ENDED_BEFORE_BRIDGE_HANDSHAKE:" +
                                                (
                                                    failure.message
                                                        ?: failure
                                                            .javaClass
                                                            .simpleName
                                                )
                                        } else {
                                            "DISPLAY_BRIDGE_HANDSHAKE_FAILED:" +
                                                (
                                                    failure.message
                                                        ?: failure
                                                            .javaClass
                                                            .simpleName
                                                )
                                        },
                                )
                        }

                startPeer(
                    firstPeer,
                )

                acceptLoop =
                    launch(Dispatchers.IO) {
                        while (
                            !closed.get()
                        ) {
                            val accepted =
                                host.acceptAuthenticated(
                                    readTimeoutMillis =
                                        handshakeSocketTimeoutMillis,
                                )

                            accepted
                                .onSuccess {
                                    peer ->
                                    startPeer(
                                        peer,
                                    )
                                }
                                .onFailure {
                                    failure ->
                                    if (
                                        !closed.get()
                                    ) {
                                        rememberFailure(
                                            "DISPLAY_BRIDGE_ADDITIONAL_HANDSHAKE_FAILED:" +
                                                (
                                                    failure.message
                                                        ?: failure
                                                            .javaClass
                                                            .simpleName
                                                ),
                                        )
                                    }
                                }
                        }
                    }

                val process =
                    rootProcessResult
                        ?: processDeferred
                            .await()

                while (
                    !closed.get()
                ) {
                    val peerState =
                        synchronized(
                            stateLock,
                        ) {
                            activeSessions.size to
                                lastPeerActivityNanos
                        }
                    if (
                        peerState.first >
                            0
                    ) {
                        delay(
                            PEER_DRAIN_POLL_MILLIS,
                        )
                        continue
                    }

                    val quietNanos =
                        System.nanoTime() -
                            peerState.second
                    val graceNanos =
                        PEER_HANDOFF_GRACE_MILLIS *
                            1_000_000L
                    if (
                        quietNanos >=
                            graceNanos
                    ) {
                        break
                    }

                    val remainingMillis =
                        (
                            (
                                graceNanos -
                                    quietNanos
                            ) /
                                1_000_000L
                        ).coerceAtLeast(
                            1L,
                        )
                    delay(
                        minOf(
                            PEER_DRAIN_POLL_MILLIS,
                            remainingMillis,
                        ),
                    )
                }

                val peers =
                    synchronized(stateLock) {
                        authenticatedPeerCount
                    }
                val failures =
                    synchronized(stateLock) {
                        peerFailures.toList()
                    }

                RuntimeDisplayExecutionResult(
                    process = process,
                    bridgeAuthenticated =
                        peers > 0,
                    bridgeError =
                        failures
                            .takeIf {
                                it.isNotEmpty()
                            }
                            ?.joinToString(
                                separator = " | ",
                            ),
                    authenticatedPeerCount =
                        peers,
                )
            } finally {
                host.close()

                if (
                    !acceptDeferred
                        .isCompleted
                ) {
                    acceptDeferred.cancel()
                }

                acceptLoop
                    ?.cancelAndJoin()

                val sessions =
                    synchronized(stateLock) {
                        activeSessions
                            .values
                            .toList()
                    }
                sessions.forEach {
                    activeSession ->
                    activeSession.close()
                }

                val jobs =
                    synchronized(stateLock) {
                        activeJobs
                            .values
                            .toList()
                    }
                jobs.forEach {
                    job ->
                    job.cancelAndJoin()
                }

                windowCollector
                    .cancelAndJoin()
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

    companion object {
        private const val
            MAX_AUTHENTICATED_PEERS =
            16
        private const val
            MAX_RECORDED_PEER_FAILURES =
            8
        private const val
            PEER_HANDOFF_GRACE_MILLIS =
            5_000L
        private const val
            PEER_DRAIN_POLL_MILLIS =
            100L
    }
}
