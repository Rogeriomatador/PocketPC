package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull

data class RuntimeDisplayExecutionResult(
    val process: ProotExecutionResult,
    val bridgeAuthenticated: Boolean,
    val bridgeError: String?,
    val authenticatedPeerCount: Int = 0,
    val graphicsAuthenticated: Boolean = false,
    val graphicsHostResourceOffered: Boolean = false,
    val graphicsResourceId: Long? = null,
    val graphicsGeneration: Long? = null,
    val graphicsError: String? = null,
)

private sealed interface RuntimeDisplayStartup {
    data class Peer(
        val result: Result<RuntimeDisplayBridgePeer>,
    ) : RuntimeDisplayStartup

    data class Process(
        val result: ProotExecutionResult,
    ) : RuntimeDisplayStartup
}

/**
 * Runs the real Wine display session and, when a Vulkan device is created,
 * offers the authenticated PocketPC external graphics resource over PGH1.
 *
 * Display Bridge and graphics transport intentionally share the same PRoot /
 * Box64 / Wine process family. A graphics transport failure is recorded but
 * does not silently turn a generic Win32 display test into a Vulkan PASS.
 */
class RuntimeDisplayExecutionController(
    private val executionController: ProotExecutionController,
) : Closeable {
    private val closed = AtomicBoolean(false)

    private val mutableWindows =
        MutableStateFlow<List<RuntimeDisplayCompositorWindow>>(emptyList())

    val windows: StateFlow<List<RuntimeDisplayCompositorWindow>> =
        mutableWindows.asStateFlow()

    suspend fun execute(
        basePlan: ProotInvocationPlan,
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers: List<DeployedWindowsRuntimeLayer>,
        userApproved: Boolean,
        handshakeTimeoutMillis: Long = 15_000L,
        desktopBridge: RuntimeDesktopBridge? = null,
    ): RuntimeDisplayExecutionResult =
        coroutineScope {
            check(!closed.get()) { "DISPLAY_EXECUTION_CONTROLLER_CLOSED" }

            val structural =
                basePlan.blockers.filterNot {
                    it == ProotExecutionController.EXECUTION_APPROVAL_BLOCKER
                }

            if (!userApproved || structural.isNotEmpty() || basePlan.argv.isEmpty()) {
                val process =
                    executionController.executeSession(
                        plan = basePlan,
                        userApproved = userApproved,
                    )
                return@coroutineScope RuntimeDisplayExecutionResult(
                    process = process,
                    bridgeAuthenticated = false,
                    bridgeError = "DISPLAY_SESSION_NOT_STARTED",
                )
            }

            val hostTemp =
                basePlan.environment["PROOT_TMP_DIR"]?.let(::File)
                    ?: return@coroutineScope RuntimeDisplayExecutionResult(
                        process = blockedResult(
                            "HOST_TEMP_BIND_MISSING",
                            "DISPLAY_BRIDGE_TEMP_MISSING",
                        ),
                        bridgeAuthenticated = false,
                        bridgeError = "DISPLAY_BRIDGE_TEMP_MISSING",
                    )

            val identity = RuntimeExecutionIdentity.of(runtime, tools, layers)
            val displaySession = RuntimeDisplayBridgeSessionFactory.create(identity)
            val graphicsSession = GuestGraphicsSessionOrchestrator.create()

            val environment = LinkedHashMap(basePlan.environment).apply {
                putAll(displaySession.environment())
                graphicsSession?.let { putAll(it.launchEnvironment) }
            }
            val environmentErrors = RuntimeEnvironment.validate(environment)
            if (environmentErrors.isNotEmpty()) {
                graphicsSession?.close()
                return@coroutineScope RuntimeDisplayExecutionResult(
                    process = ProotExecutionResult(
                        state = ProotExecutionState.BLOCKED,
                        started = false,
                        exitCode = null,
                        output = "",
                        outputTruncated = false,
                        blockers = environmentErrors,
                        error = "DISPLAY_BRIDGE_ENVIRONMENT_INVALID",
                    ),
                    bridgeAuthenticated = false,
                    bridgeError = "DISPLAY_BRIDGE_ENVIRONMENT_INVALID",
                    graphicsError =
                        if (graphicsSession == null) {
                            GuestGraphicsSessionOrchestrator.BLOCKER_SESSION_CREATE_FAILED
                        } else {
                            null
                        },
                )
            }

            val plan = basePlan.copy(environment = environment)
            val host = RuntimeDisplayBridgeHost(displaySession)
            val desktopMultiplexer = desktopBridge ?: RuntimeDesktopBridge()
            val stateLock = Any()
            val activeSessions = LinkedHashMap<Int, RuntimeDisplaySessionController>()
            val activeJobs = LinkedHashMap<Int, Job>()
            val peerFailures = mutableListOf<String>()
            val firstGraphicsExtent = CompletableDeferred<Pair<Int, Int>>()

            var nextPeerId = 1
            var authenticatedPeerCount = 0
            var lastPeerActivityNanos = System.nanoTime()
            var acceptLoop: Job? = null
            var graphicsOfferJob: Job? = null

            var graphicsAuthenticated = false
            var graphicsHostResourceOffered = false
            var graphicsResourceId: Long? = null
            var graphicsGeneration: Long? = null
            var graphicsError: String? =
                if (graphicsSession == null) {
                    GuestGraphicsSessionOrchestrator.BLOCKER_SESSION_CREATE_FAILED
                } else {
                    null
                }

            val windowCollector = launch {
                desktopMultiplexer.windows.collect { currentWindows ->
                    currentWindows.forEach { window ->
                        val processPid = window.windowId.ushr(32)
                        if (processPid > 0L && processPid <= Int.MAX_VALUE) {
                            executionController.associateActiveFamilyPid(processPid)
                        }
                    }

                    if (!firstGraphicsExtent.isCompleted) {
                        currentWindows.asSequence()
                            .mapNotNull { it.geometry }
                            .firstOrNull { geometry ->
                                geometry.width in 1..VulkanExternalImageFdBroker.MAX_DIMENSION &&
                                    geometry.height in 1..VulkanExternalImageFdBroker.MAX_DIMENSION
                            }
                            ?.let { geometry ->
                                firstGraphicsExtent.complete(
                                    geometry.width to geometry.height,
                                )
                            }
                    }

                    mutableWindows.value = currentWindows
                }
            }

            fun rememberFailure(value: String) {
                synchronized(stateLock) {
                    if (peerFailures.size < MAX_RECORDED_PEER_FAILURES) {
                        peerFailures += value
                    }
                }
            }

            fun updateGraphicsState(
                authenticated: Boolean? = null,
                offered: Boolean? = null,
                resourceId: Long? = null,
                generation: Long? = null,
                error: String? = null,
                clearError: Boolean = false,
            ) {
                synchronized(stateLock) {
                    authenticated?.let { graphicsAuthenticated = it }
                    offered?.let { graphicsHostResourceOffered = it }
                    resourceId?.let { graphicsResourceId = it }
                    generation?.let { graphicsGeneration = it }
                    if (clearError) graphicsError = null
                    if (error != null) graphicsError = error
                }
            }

            fun startPeer(peer: RuntimeDisplayBridgePeer) {
                val peerId = synchronized(stateLock) {
                    if (activeSessions.size >= MAX_AUTHENTICATED_PEERS) {
                        0
                    } else {
                        val allocated = nextPeerId
                        check(allocated > 0) { "DISPLAY_BRIDGE_PEER_ID_EXHAUSTED" }
                        nextPeerId =
                            if (allocated == Int.MAX_VALUE) 0 else allocated + 1
                        allocated
                    }
                }

                if (peerId == 0) {
                    rememberFailure("DISPLAY_BRIDGE_PEER_LIMIT_REACHED")
                    peer.close()
                    return
                }

                val activeSession = try {
                    RuntimeDisplaySessionController(
                        endpoint = peer,
                        hostTempDirectory = hostTemp,
                        desktopBridge = desktopMultiplexer,
                    )
                } catch (failure: Throwable) {
                    rememberFailure(
                        "DISPLAY_BRIDGE_PEER_SESSION_CREATE_FAILED:" +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                    peer.close()
                    return
                }

                synchronized(stateLock) {
                    activeSessions[peerId] = activeSession
                    authenticatedPeerCount += 1
                    lastPeerActivityNanos = System.nanoTime()
                }

                val job = launch {
                    val result = activeSession.runSession()
                    result.exceptionOrNull()?.let { failure ->
                        rememberFailure(
                            "DISPLAY_BRIDGE_PEER_SESSION_FAILED:" +
                                (failure.message ?: failure.javaClass.simpleName),
                        )
                    }
                    activeSession.close()
                    synchronized(stateLock) {
                        activeSessions.remove(peerId)
                        activeJobs.remove(peerId)
                        lastPeerActivityNanos = System.nanoTime()
                    }
                }

                synchronized(stateLock) {
                    activeJobs[peerId] = job
                }
            }

            val handshakeSocketTimeoutMillis =
                handshakeTimeoutMillis.coerceIn(1_000L, 30_000L).toInt()

            val acceptDeferred = async(Dispatchers.IO) {
                host.acceptAuthenticated(
                    readTimeoutMillis = handshakeSocketTimeoutMillis,
                )
            }

            val graphicsAcceptDeferred = graphicsSession?.let { graphics ->
                async(Dispatchers.IO) {
                    graphics.acceptAuthenticated(
                        timeoutMillis =
                            handshakeTimeoutMillis.coerceIn(
                                GraphicsSeqpacketSessionHost.MIN_AUTH_TIMEOUT_MILLIS,
                                GraphicsSeqpacketSessionHost.MAX_AUTH_TIMEOUT_MILLIS,
                            ),
                    )
                }
            }

            val processDeferred = async {
                executionController.executeSession(
                    plan = plan,
                    userApproved = true,
                )
            }

            graphicsOfferJob = graphicsSession?.let { graphics ->
                launch(Dispatchers.IO) {
                    val authenticated =
                        runCatching {
                            graphicsAcceptDeferred?.await() == true
                        }.getOrDefault(false)
                    if (!authenticated) {
                        updateGraphicsState(
                            authenticated = false,
                            error = GuestGraphicsSessionOrchestrator.BLOCKER_NOT_AUTHENTICATED,
                        )
                        return@launch
                    }

                    updateGraphicsState(
                        authenticated = true,
                        clearError = true,
                    )

                    val extent = withTimeoutOrNull(GRAPHICS_EXTENT_WAIT_MILLIS) {
                        firstGraphicsExtent.await()
                    }
                    if (extent == null) {
                        updateGraphicsState(error = "GUEST_GRAPHICS_DISPLAY_EXTENT_NOT_OBSERVED")
                        return@launch
                    }

                    val offer = graphics.createAndOfferExternalImage(
                        width = extent.first,
                        height = extent.second,
                    )
                    val offered = offer.getOrNull()
                    if (offered == null || !offered.hostOfferComplete) {
                        updateGraphicsState(
                            offered = false,
                            error =
                                graphics.snapshot().blocker
                                    ?: offer.exceptionOrNull()?.message
                                    ?: "GUEST_GRAPHICS_RESOURCE_OFFER_FAILED",
                        )
                        return@launch
                    }

                    updateGraphicsState(
                        offered = true,
                        resourceId = offered.resourceId,
                        generation = offered.generation,
                        clearError = true,
                    )
                }
            }

            try {
                val startup = try {
                    withTimeout(handshakeTimeoutMillis.coerceIn(1_000L, 60_000L)) {
                        select<RuntimeDisplayStartup> {
                            acceptDeferred.onAwait { RuntimeDisplayStartup.Peer(it) }
                            processDeferred.onAwait { RuntimeDisplayStartup.Process(it) }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    executionController.stopActive()
                    val process = processDeferred.await()
                    return@coroutineScope RuntimeDisplayExecutionResult(
                        process = process,
                        bridgeAuthenticated = false,
                        bridgeError =
                            "DISPLAY_BRIDGE_HANDSHAKE_TIMEOUT_OR_FAILURE:" +
                                (failure.message ?: failure.javaClass.simpleName),
                        graphicsAuthenticated = synchronized(stateLock) { graphicsAuthenticated },
                        graphicsHostResourceOffered = synchronized(stateLock) { graphicsHostResourceOffered },
                        graphicsResourceId = synchronized(stateLock) { graphicsResourceId },
                        graphicsGeneration = synchronized(stateLock) { graphicsGeneration },
                        graphicsError = synchronized(stateLock) { graphicsError },
                    )
                }

                val rootProcessResult =
                    (startup as? RuntimeDisplayStartup.Process)?.result

                val firstPeerResult =
                    if (rootProcessResult != null) {
                        try {
                            withTimeout(PEER_HANDOFF_GRACE_MILLIS) {
                                acceptDeferred.await()
                            }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Throwable) {
                            Result.failure(
                                IllegalStateException(
                                    "DISPLAY_CHILD_PEER_HANDOFF_TIMEOUT:" +
                                        (failure.message ?: failure.javaClass.simpleName),
                                ),
                            )
                        }
                    } else {
                        (startup as RuntimeDisplayStartup.Peer).result
                    }

                val firstPeer = firstPeerResult.getOrElse { failure ->
                    if (processDeferred.isActive) executionController.stopActive()
                    val process = rootProcessResult ?: processDeferred.await()
                    return@coroutineScope RuntimeDisplayExecutionResult(
                        process = process,
                        bridgeAuthenticated = false,
                        bridgeError =
                            if (rootProcessResult != null) {
                                "DISPLAY_PROCESS_ENDED_BEFORE_BRIDGE_HANDSHAKE:" +
                                    (failure.message ?: failure.javaClass.simpleName)
                            } else {
                                "DISPLAY_BRIDGE_HANDSHAKE_FAILED:" +
                                    (failure.message ?: failure.javaClass.simpleName)
                            },
                        graphicsAuthenticated = synchronized(stateLock) { graphicsAuthenticated },
                        graphicsHostResourceOffered = synchronized(stateLock) { graphicsHostResourceOffered },
                        graphicsResourceId = synchronized(stateLock) { graphicsResourceId },
                        graphicsGeneration = synchronized(stateLock) { graphicsGeneration },
                        graphicsError = synchronized(stateLock) { graphicsError },
                    )
                }

                startPeer(firstPeer)

                acceptLoop = launch(Dispatchers.IO) {
                    while (!closed.get()) {
                        val accepted = host.acceptAuthenticated(
                            readTimeoutMillis = handshakeSocketTimeoutMillis,
                        )
                        accepted.onSuccess(::startPeer).onFailure { failure ->
                            if (!closed.get()) {
                                rememberFailure(
                                    "DISPLAY_BRIDGE_ADDITIONAL_HANDSHAKE_FAILED:" +
                                        (failure.message ?: failure.javaClass.simpleName),
                                )
                            }
                        }
                    }
                }

                val process = rootProcessResult ?: processDeferred.await()

                while (!closed.get()) {
                    val peerState = synchronized(stateLock) {
                        activeSessions.size to lastPeerActivityNanos
                    }
                    if (peerState.first > 0) {
                        delay(PEER_DRAIN_POLL_MILLIS)
                        continue
                    }

                    val quietNanos = System.nanoTime() - peerState.second
                    val graceNanos = PEER_HANDOFF_GRACE_MILLIS * 1_000_000L
                    if (quietNanos >= graceNanos) break

                    val remainingMillis =
                        ((graceNanos - quietNanos) / 1_000_000L).coerceAtLeast(1L)
                    delay(minOf(PEER_DRAIN_POLL_MILLIS, remainingMillis))
                }

                if (graphicsOfferJob?.isActive == true) {
                    updateGraphicsState(
                        error = "GUEST_GRAPHICS_OFFER_NOT_COMPLETED_BEFORE_PROCESS_EXIT",
                    )
                }

                val peers = synchronized(stateLock) { authenticatedPeerCount }
                val failures = synchronized(stateLock) { peerFailures.toList() }
                val graphicsSnapshot = synchronized(stateLock) {
                    listOf(
                        graphicsAuthenticated,
                        graphicsHostResourceOffered,
                        graphicsResourceId,
                        graphicsGeneration,
                        graphicsError,
                    )
                }

                RuntimeDisplayExecutionResult(
                    process = process,
                    bridgeAuthenticated = peers > 0,
                    bridgeError =
                        failures.takeIf { it.isNotEmpty() }
                            ?.joinToString(separator = " | "),
                    authenticatedPeerCount = peers,
                    graphicsAuthenticated = graphicsSnapshot[0] as Boolean,
                    graphicsHostResourceOffered = graphicsSnapshot[1] as Boolean,
                    graphicsResourceId = graphicsSnapshot[2] as Long?,
                    graphicsGeneration = graphicsSnapshot[3] as Long?,
                    graphicsError = graphicsSnapshot[4] as String?,
                )
            } finally {
                host.close()

                if (!acceptDeferred.isCompleted) acceptDeferred.cancel()
                if (graphicsAcceptDeferred?.isCompleted == false) {
                    graphicsAcceptDeferred.cancel()
                }

                acceptLoop?.cancelAndJoin()
                graphicsOfferJob?.cancelAndJoin()

                val sessions = synchronized(stateLock) {
                    activeSessions.values.toList()
                }
                sessions.forEach { it.close() }

                val jobs = synchronized(stateLock) {
                    activeJobs.values.toList()
                }
                jobs.forEach { it.cancelAndJoin() }

                graphicsSession?.close()
                windowCollector.cancelAndJoin()
                mutableWindows.value = emptyList()
            }
        }

    fun stopActive(): Boolean = executionController.stopActive()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            executionController.stopActive()
            mutableWindows.value = emptyList()
        }
    }

    private fun blockedResult(
        blocker: String,
        error: String,
    ) =
        ProotExecutionResult(
            state = ProotExecutionState.BLOCKED,
            started = false,
            exitCode = null,
            output = "",
            outputTruncated = false,
            blockers = listOf(blocker),
            error = error,
        )

    companion object {
        private const val MAX_AUTHENTICATED_PEERS = 16
        private const val MAX_RECORDED_PEER_FAILURES = 8
        private const val PEER_HANDOFF_GRACE_MILLIS = 5_000L
        private const val PEER_DRAIN_POLL_MILLIS = 100L
        private const val GRAPHICS_EXTENT_WAIT_MILLIS = 30_000L
    }
}
