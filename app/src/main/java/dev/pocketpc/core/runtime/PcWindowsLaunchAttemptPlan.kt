package dev.pocketpc.core.runtime

import java.io.File

data class PcWindowsLaunchAttemptPlan(
    val ready: Boolean,
    val target:
        MaterializedPcApplicationTarget,
    val wine: WineLaunchPlan,
    val graphicsConfigurationInvocation:
        ProotInvocationPlan,
    val invocation:
        ProotInvocationPlan,
    val blockers: List<String>,
)

object PcWindowsLaunchAttemptPlanner {
    fun build(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        prefixPlan: WindowsPrefixPlan,
        target:
            MaterializedPcApplicationTarget,
        evidence: RuntimeProbeEvidenceState,
        deployedLayers:
            List<DeployedWindowsRuntimeLayer> =
            emptyList(),
    ): PcWindowsLaunchAttemptPlan {
        val blockers =
            mutableListOf<String>()
        val requiresDxvk =
            target.source.graphicsProfile ==
                PcApplicationGraphicsProfile.D3D_DXVK

        if (
            !evidence.displayBridgeSmokePassed
        ) {
            blockers +=
                "DISPLAY_BRIDGE_RUNTIME_NOT_VALIDATED"
        }
        if (
            !evidence
                .winePocketPcWindowSmokePassed
        ) {
            blockers +=
                "WINE_POCKETPC_WINDOW_NOT_VALIDATED"
        }
        if (
            !evidence.windowsProcessSmokePassed
        ) {
            blockers +=
                "WINDOWS_PROCESS_RUNTIME_NOT_VALIDATED"
        }
        blockers +=
            graphicsRuntimeBlockers(
                profile =
                    target.source.graphicsProfile,
                evidence = evidence,
                deployedLayers = deployedLayers,
            )

        val prefixReadiness =
            WindowsPrefixReadinessProbe
                .assess(prefixPlan)
        if (!prefixReadiness.ready) {
            blockers +=
                prefixReadiness.blockers
        }

        val extension =
            target.source.fileName
                .substringAfterLast(
                    '.',
                    "",
                )
                .lowercase()

        val executable: String
        val windowsArgs:
            List<String>

        when (extension) {
            "exe" -> {
                executable =
                    target.guestPath
                windowsArgs =
                    emptyList()
            }

            "msi" -> {
                executable =
                    "msiexec"
                windowsArgs =
                    listOf(
                        "/i",
                        target.guestPath,
                    )
            }

            else -> {
                blockers +=
                    "WINDOWS_TARGET_TYPE_UNSUPPORTED"
                executable =
                    target.guestPath
                windowsArgs =
                    emptyList()
            }
        }

        val wine =
            WineLaunchPlanner.build(
                prefixPlan =
                    prefixPlan,
                windowsExecutable =
                    executable,
                windowsArgs =
                    windowsArgs,
                box64RuntimeValidated =
                    evidence.box64SmokePassed,
                wineRuntimeValidated =
                    evidence.wineSmokePassed,
                enableDxvk = requiresDxvk,
            )
        blockers +=
            wine.blockers

        val shellRuntime =
            runtime.copy(
                manifest =
                    runtime.manifest.copy(
                        entrypoint =
                            "/bin/sh",
                    ),
            )

        fun buildInvocation(
            guestArguments: List<String>,
        ): ProotInvocationPlan {
            val base =
                ProotInvocationPlanner
                    .build(
                        runtime =
                            shellRuntime,
                        substrate =
                            substrate,
                        binds = binds,
                        allowedHostRoots =
                            allowedHostRoots,
                        guestArguments =
                            guestArguments,
                    )
            val environment =
                LinkedHashMap(
                    base.environment,
                ).apply {
                    putAll(
                        wine.environment,
                    )
                }
            val environmentErrors =
                RuntimeEnvironment
                    .validate(
                        environment,
                    )

            return base.copy(
                environment =
                    environment,
                blockers =
                    (
                        base.blockers +
                            environmentErrors
                    ).distinct(),
            )
        }

        val graphicsConfigurationInvocation =
            if (
                blockers.isEmpty() &&
                wine.ready
            ) {
                buildInvocation(
                    graphicsConfigurationShellArguments(
                        box64 =
                            WineLaunchPlanner
                                .DEFAULT_BOX64,
                        wine =
                            WineLaunchPlanner
                                .DEFAULT_WINE,
                    ),
                )
            } else {
                blockedInvocation(
                    blockers,
                )
            }

        val invocation =
            if (
                blockers.isEmpty() &&
                wine.ready
            ) {
                buildInvocation(
                    shellArguments(
                        wine.argv,
                    ),
                )
            } else {
                blockedInvocation(
                    blockers,
                )
            }

        val structuralBlockers =
            (
                blockers +
                    graphicsConfigurationInvocation
                        .blockers
                        .filterNot {
                            it ==
                                ProotExecutionController
                                    .EXECUTION_APPROVAL_BLOCKER
                        } +
                    invocation.blockers
                        .filterNot {
                            it ==
                                ProotExecutionController
                                    .EXECUTION_APPROVAL_BLOCKER
                        }
            ).distinct()

        return PcWindowsLaunchAttemptPlan(
            ready =
                structuralBlockers.isEmpty() &&
                    graphicsConfigurationInvocation
                        .argv
                        .isNotEmpty() &&
                    invocation.argv
                        .isNotEmpty(),
            target = target,
            wine = wine,
            graphicsConfigurationInvocation =
                graphicsConfigurationInvocation,
            invocation = invocation,
            blockers =
                structuralBlockers,
        )
    }

    internal fun graphicsRuntimeBlockers(
        profile: PcApplicationGraphicsProfile,
        evidence: RuntimeProbeEvidenceState,
        deployedLayers:
            List<DeployedWindowsRuntimeLayer>,
    ): List<String> {
        if (
            profile !=
            PcApplicationGraphicsProfile.D3D_DXVK
        ) {
            return emptyList()
        }

        return buildList {
            if (!evidence.d3d11SmokePassed) {
                add("D3D11_RUNTIME_NOT_VALIDATED")
            }
            if (
                !PocketPcVulkanWsiContract
                    .implemented
            ) {
                add(
                    PocketPcVulkanWsiContract
                        .blocker,
                )
            }
            if (
                !evidence
                    .graphicsPresentationSmokePassed
            ) {
                add(
                    "GRAPHICS_PRESENTATION_NOT_VALIDATED",
                )
            }
            if (
                deployedLayers.none {
                    it.manifest.id ==
                        "dxvk"
                }
            ) {
                add("DXVK_LAYER_NOT_DEPLOYED")
            }
        }
    }

    internal fun shellArguments(
        wineArgv: List<String>,
    ): List<String> =
        buildList {
            require(
                wineArgv.size >= 3,
            ) {
                "WINDOWS_LAUNCH_ARGV_INVALID"
            }
            add("-c")
            add("exec \"\$@\"")
            add(
                "pocketpc-windows-launch",
            )
            addAll(wineArgv)
        }

    internal fun graphicsConfigurationShellArguments(
        box64: String,
        wine: String,
    ): List<String> =
        listOf(
            "-c",
            "exec \"\$@\"",
            "pocketpc-wine-graphics-config",
            box64,
            wine,
            "reg.exe",
            "add",
            "HKCU\\Software\\Wine\\Drivers",
            "/v",
            "Graphics",
            "/t",
            "REG_SZ",
            "/d",
            "pocketpc",
            "/f",
        )

    private fun blockedInvocation(
        blockers: List<String>,
    ): ProotInvocationPlan =
        ProotInvocationPlan(
            ready = false,
            argv = emptyList(),
            environment = emptyMap(),
            blockers =
                blockers.distinct(),
        )

}