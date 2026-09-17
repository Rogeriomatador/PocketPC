package dev.pocketpc.core.runtime

import java.io.File

data class ProotInvocationPlan(
    val ready: Boolean,
    val argv: List<String>,
    val environment: Map<String, String>,
    val blockers: List<String>,
)

object ProotInvocationPlanner {
    private val deviceValidationProbes =
        setOf(
            GuestRuntimeProbe.SHELL,
            GuestRuntimeProbe.ROOTFS,
        )

    private fun permitsDeviceValidationProbe(
        substrate: ExecutionSubstrateStatus,
        probe: GuestRuntimeProbe,
        allowDeviceValidationCandidate: Boolean,
    ): Boolean =
        allowDeviceValidationCandidate &&
            !substrate.prootReady &&
            substrate.deviceValidationReady &&
            probe in deviceValidationProbes

    fun buildProbe(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        probe: GuestRuntimeProbe,
        allowDeviceValidationCandidate:
            Boolean = true,
    ): ProotInvocationPlan {
        /*
         * A fully attested device-validation candidate is intentionally
         * executable by probe callers without requiring each UI surface to
         * remember a separate opt-in. The scope remains fail-closed here:
         * only SHELL/ROOTFS are eligible, production readiness stays false,
         * and ProotExecutionController still requires explicit user approval.
         * Generic build() below never inherits this validation exception.
         */
        val validationCandidateSelected =
            permitsDeviceValidationProbe(
                substrate = substrate,
                probe = probe,
                allowDeviceValidationCandidate =
                    allowDeviceValidationCandidate,
            )

        return buildInternal(
            runtime =
                runtime.copy(
                    manifest =
                        runtime.manifest
                            .copy(
                                entrypoint =
                                    "/bin/sh",
                            ),
                ),
            substrate = substrate,
            binds = binds,
            allowedHostRoots =
                allowedHostRoots,
            guestArguments =
                probe.arguments,
            substrateExecutionReady =
                substrate.prootReady ||
                    validationCandidateSelected,
        )
    }

    fun build(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        guestArguments:
            List<String> =
            emptyList(),
    ): ProotInvocationPlan =
        buildInternal(
            runtime = runtime,
            substrate = substrate,
            binds = binds,
            allowedHostRoots =
                allowedHostRoots,
            guestArguments =
                guestArguments,
            substrateExecutionReady =
                substrate.prootReady,
        )

    private fun buildInternal(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        guestArguments: List<String>,
        substrateExecutionReady: Boolean,
    ): ProotInvocationPlan {
        val blockers =
            mutableListOf<String>()
        if (
            guestArguments.size >
                64 ||
            guestArguments.any {
                it.length >
                    16_384 ||
                    '\u0000' in it
            }
        ) {
            blockers +=
                "INVALID_GUEST_ARGUMENTS"
        }

        if (
            !substrateExecutionReady
        ) {
            blockers +=
                "SUBSTRATE_NOT_READY"
        }

        val launch =
            RuntimeLaunchPlanner.assess(
                runtime = runtime,
                substrate = substrate,
                substrateExecutionReady =
                    substrateExecutionReady,
            )
        launch.blockers
            .filter {
                it !=
                    LaunchBlocker
                        .EXECUTOR_DISABLED
            }
            .forEach {
                blockers += it.name
            }

        val bindValidation =
            RuntimeBindPolicy.validate(
                binds,
                allowedHostRoots,
            )
        blockers +=
            bindValidation.errors

        binds
            .filter {
                it.readOnly
            }
            .forEach { bind ->
                blockers +=
                    "READ_ONLY_BIND_UNIMPLEMENTED:" +
                        bind.guestPath
            }

        val tempBind =
            binds.singleOrNull {
                RuntimeBindPolicy
                    .normalizeGuestPath(
                        it.guestPath,
                    ) ==
                    "/tmp" &&
                    it.authority ==
                    BindAuthority.SYSTEM &&
                    !it.readOnly
            }
        if (tempBind == null) {
            blockers +=
                "HOST_TEMP_BIND_MISSING"
        }

        val environment =
            RuntimeEnvironment.forProot(
                substrate
                    .nativeLibraryDir,
                tempBind?.hostPath
                    ?.canonicalPath,
            )
        blockers +=
            RuntimeEnvironment
                .validate(environment)

        val proot =
            File(
                substrate
                    .nativeLibraryDir,
                "libproot.so",
            )
        val loader =
            File(
                substrate
                    .nativeLibraryDir,
                "libproot_loader.so",
            )

        if (
            !proot.isFile ||
            !proot.canRead() ||
            !proot.canExecute()
        ) {
            blockers +=
                "PROOT_EXECUTABLE_UNAVAILABLE"
        }
        if (
            !loader.isFile ||
            !loader.canRead() ||
            !loader.canExecute()
        ) {
            blockers +=
                "PROOT_LOADER_UNAVAILABLE"
        }

        val candidateArgv =
            if (blockers.isEmpty()) {
                buildList {
                    add(proot.path)
                    add("-0")
                    add("-r")
                    add(
                        runtime
                            .rootfsData.path,
                    )
                    add("-w")
                    add(
                        binds.firstOrNull {
                            RuntimeBindPolicy
                                .normalizeGuestPath(
                                    it.guestPath,
                                ) ==
                                "/home/pocket"
                        }?.guestPath
                            ?.let {
                                RuntimeBindPolicy
                                    .normalizeGuestPath(
                                        it,
                                    )
                            }
                            ?: "/",
                    )
                    binds.forEach {
                        bind ->
                        val guest =
                            RuntimeBindPolicy
                                .normalizeGuestPath(
                                    bind.guestPath,
                                )
                                ?: error(
                                    "bind guest became invalid after validation",
                                )
                        add("-b")
                        add(
                            bind.hostPath
                                .canonicalPath +
                                ":" +
                                guest +
                                "!",
                        )
                    }
                    add(
                        runtime.manifest
                            .entrypoint,
                    )
                    addAll(
                        guestArguments,
                    )
                }
            } else {
                emptyList()
            }

        val finalBlockers =
            (
                blockers +
                    "EXECUTION_REQUIRES_USER_APPROVAL"
                ).distinct()

        return ProotInvocationPlan(
            ready = false,
            argv =
                candidateArgv,
            environment =
                environment,
            blockers =
                finalBlockers,
        )
    }
}
