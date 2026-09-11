package dev.pocketpc.core.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

enum class GraphicsProotSessionState {
    BLOCKED,
    START_FAILED,
    AUTHENTICATION_FAILED,
    RESOURCE_OFFER_FAILED,
    EXITED,
}

data class GraphicsProotSessionResult(
    val state: GraphicsProotSessionState,
    val processStarted: Boolean,
    val graphicsAuthenticated: Boolean,
    val hostResourceOffered: Boolean,
    val resourceId: Long?,
    val generation: Long?,
    val exitCode: Int?,
    val output: String,
    val outputTruncated: Boolean,
    val blockers: List<String>,
    val error: String?,
) {
    /**
     * Deliberately means only that the host launch/auth/offer path completed.
     * It is not a guest Vulkan import, GPU synchronization, visible Present or
     * Roblox readiness signal.
     */
    val hostTransportAttemptCompleted: Boolean
        get() =
            processStarted &&
                graphicsAuthenticated &&
                hostResourceOffered
}

/**
 * Explicit graphics-enabled PRoot session path.
 *
 * The ordinary PRoot controller remains unchanged. This controller creates the
 * PGH1 server before ProcessBuilder starts, injects the private session values
 * into the exact guest environment, starts PRoot/Box64/Wine, authenticates the
 * connecting guest and only then sends PVI1 + PVS1.
 *
 * Failures stop the supervised process family. No host-side success here is
 * allowed to promote GuestGraphicsTransportContract guestReceive/import/sync.
 */
class GraphicsProotSessionController(
    private val supervisor: RuntimeProcessSupervisor = RuntimeProcessSupervisor(),
) {
    suspend fun executeSession(
        plan: ProotInvocationPlan,
        userApproved: Boolean,
        width: Int,
        height: Int,
        authenticationTimeoutMillis: Long =
            GraphicsSeqpacketSessionHost.DEFAULT_AUTH_TIMEOUT_MILLIS,
    ): GraphicsProotSessionResult =
        coroutineScope {
            val structuralBlockers =
                plan.blockers.filterNot {
                    it == ProotExecutionController.EXECUTION_APPROVAL_BLOCKER
                }

            if (!userApproved) {
                return@coroutineScope blocked(
                    blockers =
                        (
                            structuralBlockers +
                                ProotExecutionController.EXECUTION_APPROVAL_BLOCKER
                        ).distinct(),
                    error = "A execução exige aprovação explícita do usuário.",
                )
            }

            if (structuralBlockers.isNotEmpty() || plan.argv.isEmpty()) {
                return@coroutineScope blocked(
                    blockers =
                        structuralBlockers.ifEmpty {
                            listOf("INVOCATION_ARGV_NOT_READY")
                        },
                    error = "O plano PRoot ainda possui gates estruturais bloqueados.",
                )
            }

            if (
                width !in 1..VulkanExternalImageFdBroker.MAX_DIMENSION ||
                height !in 1..VulkanExternalImageFdBroker.MAX_DIMENSION
            ) {
                return@coroutineScope blocked(
                    blockers = listOf("GRAPHICS_EXTERNAL_IMAGE_DIMENSIONS_INVALID"),
                    error = "Dimensões da imagem Vulkan externa inválidas.",
                )
            }

            if (
                authenticationTimeoutMillis !in
                    GraphicsSeqpacketSessionHost.MIN_AUTH_TIMEOUT_MILLIS..
                        GraphicsSeqpacketSessionHost.MAX_AUTH_TIMEOUT_MILLIS
            ) {
                return@coroutineScope blocked(
                    blockers = listOf("GRAPHICS_AUTH_TIMEOUT_INVALID"),
                    error = "Timeout de autenticação gráfica inválido.",
                )
            }

            val graphics = GuestGraphicsSessionOrchestrator.create()
                ?: return@coroutineScope blocked(
                    blockers = listOf(
                        GuestGraphicsSessionOrchestrator.BLOCKER_SESSION_CREATE_FAILED,
                    ),
                    error = "Não foi possível criar a sessão gráfica privada.",
                )

            try {
                val environment =
                    buildGuestEnvironment(
                        base = plan.environment,
                        graphics = graphics.launchEnvironment,
                    )

                val processDeferred =
                    async(Dispatchers.IO) {
                        supervisor.runSession(
                            ProcessSessionSpec(
                                argv = plan.argv,
                                environment = environment,
                                maxOutputBytes = MAX_CAPTURE_BYTES,
                            )
                        )
                    }

                val authenticationDeferred =
                    async(Dispatchers.IO) {
                        graphics.acceptAuthenticated(
                            timeoutMillis = authenticationTimeoutMillis,
                        )
                    }

                val authenticated = authenticationDeferred.await()
                if (!authenticated) {
                    supervisor.stopActive()
                    val process = processDeferred.await()
                    return@coroutineScope GraphicsProotSessionResult(
                        state =
                            if (process.started) {
                                GraphicsProotSessionState.AUTHENTICATION_FAILED
                            } else {
                                GraphicsProotSessionState.START_FAILED
                            },
                        processStarted = process.started,
                        graphicsAuthenticated = false,
                        hostResourceOffered = false,
                        resourceId = null,
                        generation = null,
                        exitCode = process.exitCode,
                        output = process.output,
                        outputTruncated = process.outputTruncated,
                        blockers = listOf(
                            GuestGraphicsSessionOrchestrator.BLOCKER_NOT_AUTHENTICATED,
                        ),
                        error = process.error ?: "O guest não concluiu PGH1 no prazo.",
                    )
                }

                val offerResult =
                    graphics.createAndOfferExternalImage(
                        width = width,
                        height = height,
                    )
                val offer = offerResult.getOrNull()
                if (offer == null || !offer.hostOfferComplete) {
                    supervisor.stopActive()
                    val process = processDeferred.await()
                    return@coroutineScope GraphicsProotSessionResult(
                        state = GraphicsProotSessionState.RESOURCE_OFFER_FAILED,
                        processStarted = process.started,
                        graphicsAuthenticated = true,
                        hostResourceOffered = false,
                        resourceId = null,
                        generation = null,
                        exitCode = process.exitCode,
                        output = process.output,
                        outputTruncated = process.outputTruncated,
                        blockers = listOf(
                            graphics.snapshot().blocker
                                ?: "GUEST_GRAPHICS_RESOURCE_OFFER_FAILED",
                        ),
                        error =
                            offerResult.exceptionOrNull()?.message
                                ?: process.error
                                ?: "Falha ao enviar PVI1/PVS1 ao guest autenticado.",
                    )
                }

                val process = processDeferred.await()
                GraphicsProotSessionResult(
                    state = GraphicsProotSessionState.EXITED,
                    processStarted = process.started,
                    graphicsAuthenticated = true,
                    hostResourceOffered = true,
                    resourceId = offer.resourceId,
                    generation = offer.generation,
                    exitCode = process.exitCode,
                    output = process.output,
                    outputTruncated = process.outputTruncated,
                    blockers = emptyList(),
                    error = process.error,
                )
            } finally {
                graphics.close()
            }
        }

    fun activeFamilyId(): Long? = supervisor.activeFamilyId()

    fun associateActiveFamilyPid(pid: Long): Boolean =
        supervisor.associateActiveFamilyPid(pid)

    fun stopActive(): Boolean = supervisor.stopActive()

    companion object {
        private const val MAX_CAPTURE_BYTES = 1024 * 1024

        private val RESERVED_GRAPHICS_ENVIRONMENT_KEYS =
            setOf(
                "POCKETPC_GRAPHICS_SOCKET_NAME",
                "POCKETPC_GRAPHICS_SESSION_TOKEN",
                "POCKETPC_GRAPHICS_SESSION_PROTOCOL",
            )

        /**
         * Graphics values always win over an inherited/base environment. The
         * exact token/socket generated for this session cannot be overridden by
         * a stale plan value.
         */
        internal fun buildGuestEnvironment(
            base: Map<String, String>,
            graphics: Map<String, String>,
        ): Map<String, String> {
            require(graphics.keys == RESERVED_GRAPHICS_ENVIRONMENT_KEYS) {
                "GRAPHICS_LAUNCH_ENVIRONMENT_INVALID"
            }
            require(graphics.values.none(String::isBlank)) {
                "GRAPHICS_LAUNCH_ENVIRONMENT_BLANK"
            }
            return LinkedHashMap<String, String>(base.size + graphics.size).apply {
                putAll(base)
                putAll(graphics)
            }.toMap()
        }
    }

    private fun blocked(
        blockers: List<String>,
        error: String,
    ) =
        GraphicsProotSessionResult(
            state = GraphicsProotSessionState.BLOCKED,
            processStarted = false,
            graphicsAuthenticated = false,
            hostResourceOffered = false,
            resourceId = null,
            generation = null,
            exitCode = null,
            output = "",
            outputTruncated = false,
            blockers = blockers.distinct(),
            error = error,
        )
}
