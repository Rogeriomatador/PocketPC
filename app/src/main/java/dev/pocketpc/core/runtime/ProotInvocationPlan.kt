package dev.pocketpc.core.runtime

import java.io.File

data class ProotInvocationPlan(
    val ready: Boolean,
    val argv: List<String>,
    val environment: Map<String, String>,
    val blockers: List<String>,
)

object ProotInvocationPlanner {
    fun build(
        runtime: InstalledRuntime,
        substrate: ExecutionSubstrateStatus,
        binds: List<RuntimeBindSpec>,
        allowedHostRoots: List<File>,
    ): ProotInvocationPlan {
        val blockers = mutableListOf<String>()

        if (!substrate.prootReady) blockers += "SUBSTRATE_NOT_READY"

        val launch = RuntimeLaunchPlanner.assess(runtime, substrate)
        launch.blockers
            .filter { it != LaunchBlocker.EXECUTOR_NOT_IMPLEMENTED }
            .forEach { blockers += it.name }

        val bindValidation = RuntimeBindPolicy.validate(binds, allowedHostRoots)
        blockers += bindValidation.errors

        binds
            .filter { it.readOnly }
            .forEach { bind ->
                blockers += "READ_ONLY_BIND_UNIMPLEMENTED:${bind.guestPath}"
            }

        val environment = RuntimeEnvironment.forProot(substrate.nativeLibraryDir)
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
                add("/home/pocket")
                binds.forEach { bind ->
                    val guest = RuntimeBindPolicy.normalizeGuestPath(bind.guestPath)
                        ?: error("bind guest became invalid after validation")
                    add("-b")
                    add("${bind.hostPath.canonicalPath}:$guest!")
                }
                add(runtime.manifest.entrypoint)
            }
        } else {
            emptyList()
        }

        // Foundation only. The executor remains deliberately disabled until
        // PRoot provenance, packaging, link semantics and device behavior pass.
        val finalBlockers = (blockers + "EXECUTOR_NOT_ENABLED").distinct()

        return ProotInvocationPlan(
            ready = false,
            argv = candidateArgv,
            environment = environment,
            blockers = finalBlockers,
        )
    }
}
