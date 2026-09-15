package dev.pocketpc.core.runtime

data class WindowsPrefixReadiness(
    val ready: Boolean,
    val blockers: List<String>,
)

object WindowsPrefixReadinessProbe {
    fun assess(
        plan: WindowsPrefixPlan,
    ): WindowsPrefixReadiness {
        val layout =
            plan.layout
                ?: return WindowsPrefixReadiness(
                    ready = false,
                    blockers =
                        plan.blockers.ifEmpty {
                            listOf(
                                "WINDOWS_PREFIX_LAYOUT_MISSING",
                            )
                        },
                )

        val blockers = mutableListOf<String>()

        if (
            !SafeTreeOps.isPlainDirectory(
                layout.prefixRoot.toPath(),
            )
        ) {
            blockers +=
                "WINDOWS_PREFIX_ROOT_MISSING"
        }
        if (
            !SafeTreeOps.isPlainDirectory(
                layout.driveC.toPath(),
            )
        ) {
            blockers +=
                "WINDOWS_PREFIX_DRIVE_C_MISSING"
        }
        if (
            !SafeTreeOps.isPlainDirectory(
                layout.dosDevices.toPath(),
            )
        ) {
            blockers +=
                "WINDOWS_PREFIX_DOSDEVICES_MISSING"
        }

        listOf(
            "system.reg" to
                layout.systemRegistry,
            "user.reg" to
                layout.userRegistry,
            "userdef.reg" to
                layout.userDefRegistry,
        ).forEach { (name, file) ->
            if (
                !SafeTreeOps.isPlainFile(
                    file.toPath(),
                ) ||
                !file.canRead() ||
                file.length() <= 0L
            ) {
                blockers +=
                    "WINDOWS_PREFIX_REGISTRY_INVALID:" +
                        name
            }
        }

        return WindowsPrefixReadiness(
            ready = blockers.isEmpty(),
            blockers = blockers.distinct(),
        )
    }
}
