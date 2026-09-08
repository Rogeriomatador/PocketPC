package dev.pocketpc.core.runtime

import java.io.File

data class ProotInvocationPlan(
    val ready: Boolean,
    val argv: List<String>,
    val environment: Map<String, String>,
    val blockers: List<String>,
)

object ProotInvocationPlanner {
    fun buildProbe(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        probe: GuestRuntimeProbe,
    ): ProotInvocationPlan = build(
        // Resolve and audit /bin/sh using the same metadata/link/path gates.
        runtime = runtime.copy(manifest = runtime.manifest.copy(entrypoint = "/bin/sh")),
        substrate = substrate,
        binds = binds,
        allowedHostRoots = allowedHostRoots,
        guestArguments = probe.arguments,
    )

    fun build(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
        guestArguments: List<String> = emptyList(),
    ): ProotInvocationPlan {
        val blockers = mutableListOf<String>()
        if (guestArguments.size > 64 || guestArguments.any { it.length > 16_384 || '\u0000' in it }) {
            blockers += "INVALID_GUEST_ARGUMENTS"
        }

        if (!substrate.prootReady) blockers += "SUBSTRATE_NOT_READY"

        val launch = RuntimeLaunchPlanner.assess(runtime, substrate)
        launch.blockers
            .filter { it != LaunchBlocker.EXECUTOR_DISABLED }
            .forEach { blockers += it.name }

        val bindValidation = RuntimeBindPolicy.validate(binds, allowedHostRoots)
        blockers += bindValidation.errors

        binds
            .filter { it.readOnly }
            .forEach { bind ->
                blockers += "READ_ONLY_BIND_UNIMPLEMENTED:${bind.guestPath}"
            }

        val tempBind = binds.singleOrNull {
            RuntimeBindPolicy.normalizeGuestPath(it.guestPath) == "/tmp" &&
                it.authority == BindAuthority.SYSTEM && !it.readOnly
        }
        if (tempBind == null) blockers += "HOST_TEMP_BIND_MISSING"
        val environment = RuntimeEnvironment.forProot(
            substrate.nativeLibraryDir,
            tempBind?.hostPath?.canonicalPath,
        )
        blockers += RuntimeEnvironment.validate(environment)

        val proot = File(substrate.nativeLibraryDir, "libproot.so")
        val loader = File(substrate.nativeLibraryDir, "libproot_loader.so")

        if (!proot.isFile || !proot.canRead() || !proot.canExecute()) {
            blockers += "PROOT_EXECUTABLE_UNAVAILABLE"
        }
        if (!loader.isFile || !loader.canRead() || !loader.canExecute()) {
            blockers += "PROOT_LOADER_UNAVAILABLE"
        }

        val candidateArgv = if (blockers.isEmpty()) {
            buildList {
                add(proot.path)
                add("-0")
                add("-r")
                add(runtime.rootfsData.path)
                add("-w")
                add(
                    binds.firstOrNull {
                        RuntimeBindPolicy
                            .normalizeGuestPath(
                                it.guestPath
                            ) ==
                            "/home/pocket"
                    }?.guestPath
                        ?.let {
                            RuntimeBindPolicy
                                .normalizeGuestPath(it)
                        }
                        ?: "/"
                )
                binds.forEach { bind ->
                    val guest = RuntimeBindPolicy.normalizeGuestPath(bind.guestPath)
                        ?: error("bind guest became invalid after validation")
                    add("-b")
                    add("${bind.hostPath.canonicalPath}:$guest!")
                }
                add(runtime.manifest.entrypoint)
                addAll(guestArguments)
            }
        } else {
            emptyList()
        }

        // The candidate is executable only after explicit user approval.
        // All substrate, path, link, environment and artifact gates above must
        // already be clear before argv is exposed.
        val finalBlockers =
            (
                blockers +
                    "EXECUTION_REQUIRES_USER_APPROVAL"
            ).distinct()

        return ProotInvocationPlan(
            ready = false,
            argv = candidateArgv,
            environment = environment,
            blockers = finalBlockers,
        )
    }
}
