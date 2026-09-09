package dev.pocketpc.core.runtime

import java.io.File

data class PcWindowsLaunchAttemptPlan(
    val ready: Boolean,
    val target:
        MaterializedPcApplicationTarget,
    val wine: WineLaunchPlan,
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

        if (
            !evidence.displayBridgeSmokePassed
        ) {
            blockers +=
                "DISPLAY_BRIDGE_RUNTIME_NOT_VALIDATED"
        }
        if (
            !evidence.windowsProcessSmokePassed
        ) {
            blockers +=
                "WINDOWS_PROCESS_RUNTIME_NOT_VALIDATED"
        }
        if (!evidence.d3d11SmokePassed) {
            blockers +=
                "D3D11_RUNTIME_NOT_VALIDATED"
        }
        if (
            !evidence
                .graphicsPresentationSmokePassed
        ) {
            blockers +=
                "GRAPHICS_PRESENTATION_NOT_VALIDATED"
        }
        if (
            deployedLayers.none {
                it.manifest.id ==
                    "dxvk"
            }
        ) {
            blockers +=
                "DXVK_LAYER_NOT_DEPLOYED"
        }

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
                enableDxvk = true,
            )
        blockers +=
            wine.blockers

        val invocation =
            if (
                blockers.isEmpty() &&
                wine.ready
            ) {
                /*
                 * PRoot validates that its entrypoint exists inside the
                 * rootfs. Box64 is supplied through a bind, so use the
                 * already validated /bin/sh rootfs entrypoint with a
                 * constant command and pass every dynamic value through
                 * positional arguments. No target path is interpolated
                 * into shell source.
                 */
                val shellRuntime =
                    runtime.copy(
                        manifest =
                            runtime.manifest.copy(
                                entrypoint =
                                    "/bin/sh",
                            ),
                    )
                val shellArgs =
                    shellArguments(
                        wine.argv,
                    )
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
                                shellArgs,
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

                base.copy(
                    environment =
                        environment,
                    blockers =
                        (
                            base.blockers +
                                environmentErrors
                        ).distinct(),
                )
            } else {
                ProotInvocationPlan(
                    ready = false,
                    argv = emptyList(),
                    environment =
                        emptyMap(),
                    blockers =
                        blockers.distinct(),
                )
            }

        val structuralBlockers =
            (
                blockers +
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
                    invocation.argv
                        .isNotEmpty(),
            target = target,
            wine = wine,
            invocation = invocation,
            blockers =
                structuralBlockers,
        )
    }

    internal fun shellArguments(
        wineArgv: List<String>,
    ): List<String> =
        buildList {
            add("-c")
            add("exec \"\$@\"")
            add(
                "pocketpc-windows-launch",
            )
            addAll(wineArgv)
        }
}
