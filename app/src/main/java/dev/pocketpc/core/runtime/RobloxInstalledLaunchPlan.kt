package dev.pocketpc.core.runtime

import java.io.File

data class RobloxInstalledLaunchAttemptPlan(
    val ready: Boolean,
    val installation: RobloxPlayerInstallation?,
    val deepLink: RobloxPlayerDeepLink?,
    val wine: WineLaunchPlan,
    val graphicsConfigurationInvocation: ProotInvocationPlan,
    val invocation: ProotInvocationPlan,
    val blockers: List<String>,
)

object RobloxInstalledLaunchAttemptPlanner {
    fun build(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        prefixPlan: WindowsPrefixPlan,
        evidence: RuntimeProbeEvidenceState,
        runtimeReadiness: PcRuntimeReadiness,
        deployedLayers: List<DeployedWindowsRuntimeLayer> = emptyList(),
        deepLink: RobloxPlayerDeepLink? = null,
    ): RobloxInstalledLaunchAttemptPlan {
        val blockers = mutableListOf<String>()
        val layout = prefixPlan.layout
        val discovery =
            if (layout != null) {
                RobloxInstallationDiscoveryProbe.discover(layout)
            } else {
                null
            }
        val installation = discovery?.selected

        if (!prefixPlan.valid || layout == null) {
            blockers += prefixPlan.blockers.ifEmpty {
                listOf("WINDOWS_PREFIX_NOT_READY")
            }
        }
        if (installation == null) {
            blockers += discovery?.blockers
                ?: listOf("ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED")
        }

        val robloxReadiness =
            if (discovery != null) {
                RobloxLaunchReadinessProbe.assess(
                    runtimeReadiness = runtimeReadiness,
                    probeEvidence = evidence,
                    installation = discovery,
                )
            } else {
                null
            }
        if (robloxReadiness?.controlledAttemptReady != true) {
            blockers += robloxReadiness?.blockers
                ?: listOf("ROBLOX_RUNTIME_NOT_READY")
        }

        if (
            deployedLayers.none {
                it.manifest.id == "dxvk"
            }
        ) {
            blockers += "ROBLOX_DXVK_LAYER_NOT_DEPLOYED"
        }

        val prefixReadiness =
            WindowsPrefixReadinessProbe.assess(prefixPlan)
        if (!prefixReadiness.ready) {
            blockers += prefixReadiness.blockers
        }

        val windowsArguments =
            deepLink?.let {
                // Keep the protocol request opaque. It becomes one
                // Windows argv element and is never interpreted as shell.
                listOf(it.raw)
            }.orEmpty()

        val wine =
            if (installation != null) {
                WineLaunchPlanner.build(
                    prefixPlan = prefixPlan,
                    windowsExecutable =
                        installation.windowsExecutable,
                    windowsArgs = windowsArguments,
                    box64RuntimeValidated =
                        evidence.box64SmokePassed,
                    wineRuntimeValidated =
                        evidence.wineSmokePassed,
                    enableDxvk = true,
                )
            } else {
                WineLaunchPlan(
                    ready = false,
                    argv = emptyList(),
                    environment = emptyMap(),
                    blockers =
                        listOf(
                            "ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED",
                        ),
                )
            }
        blockers += wine.blockers

        val shellRuntime =
            runtime.copy(
                manifest =
                    runtime.manifest.copy(
                        entrypoint = "/bin/sh",
                    ),
            )

        fun buildInvocation(
            guestArguments: List<String>,
        ): ProotInvocationPlan {
            val base =
                ProotInvocationPlanner.build(
                    runtime = shellRuntime,
                    substrate = substrate,
                    binds = binds,
                    allowedHostRoots = allowedHostRoots,
                    guestArguments = guestArguments,
                )
            val environment =
                LinkedHashMap(base.environment).apply {
                    putAll(wine.environment)
                }
            val environmentErrors =
                RuntimeEnvironment.validate(environment)
            return base.copy(
                environment = environment,
                blockers =
                    (base.blockers + environmentErrors)
                        .distinct(),
            )
        }

        val structural = blockers.distinct()
        val graphicsConfigurationInvocation =
            if (structural.isEmpty() && wine.ready) {
                buildInvocation(
                    PcWindowsLaunchAttemptPlanner
                        .graphicsConfigurationShellArguments(
                            box64 = WineLaunchPlanner.DEFAULT_BOX64,
                            wine = WineLaunchPlanner.DEFAULT_WINE,
                        ),
                )
            } else {
                blockedInvocation(structural)
            }

        val invocation =
            if (structural.isEmpty() && wine.ready) {
                buildInvocation(
                    PcWindowsLaunchAttemptPlanner
                        .shellArguments(wine.argv),
                )
            } else {
                blockedInvocation(structural)
            }

        val finalBlockers =
            (
                structural +
                    graphicsConfigurationInvocation.blockers
                        .filterNot {
                            it == ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER
                        } +
                    invocation.blockers
                        .filterNot {
                            it == ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER
                        }
            ).distinct()

        return RobloxInstalledLaunchAttemptPlan(
            ready =
                finalBlockers.isEmpty() &&
                    graphicsConfigurationInvocation.argv.isNotEmpty() &&
                    invocation.argv.isNotEmpty(),
            installation = installation,
            deepLink = deepLink,
            wine = wine,
            graphicsConfigurationInvocation =
                graphicsConfigurationInvocation,
            invocation = invocation,
            blockers = finalBlockers,
        )
    }

    private fun blockedInvocation(
        blockers: List<String>,
    ): ProotInvocationPlan =
        ProotInvocationPlan(
            ready = false,
            argv = emptyList(),
            environment = emptyMap(),
            blockers = blockers.distinct(),
        )
}
