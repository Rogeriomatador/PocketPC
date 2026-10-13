package dev.pocketpc.core.runtime

data class GuestToolOverlayPlan(
    val valid: Boolean,
    val binds: List<RuntimeBindSpec>,
    val blockers: List<String>,
)

object GuestToolOverlayPlanner {
    fun plan(
        tools: List<InstalledGuestTool>,
        allowedHostRoots: List<java.io.File>,
    ): GuestToolOverlayPlan {
        val blockers = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val seenGuestRoots = HashSet<String>()
        val binds = mutableListOf<RuntimeBindSpec>()

        tools.forEach { tool ->
            val manifestErrors =
                GuestToolManifestValidator.errors(tool.manifest)
            if (manifestErrors.isNotEmpty()) {
                blockers +=
                    manifestErrors.map {
                        "GUEST_TOOL_MANIFEST_INVALID:" +
                            tool.manifest.id + ":" + it
                    }
                return@forEach
            }

            if (!seenIds.add(tool.manifest.id)) {
                blockers +=
                    "GUEST_TOOL_DUPLICATE_ID:" +
                        tool.manifest.id
                return@forEach
            }
            if (!seenGuestRoots.add(tool.manifest.guestRoot)) {
                blockers +=
                    "GUEST_TOOL_DUPLICATE_GUEST_ROOT:" +
                        tool.manifest.guestRoot
                return@forEach
            }

            val verification =
                GuestToolPackageVerifier.verify(
                    packageRoot = tool.directory,
                    manifest = tool.manifest,
                )
            if (!verification.valid) {
                blockers +=
                    verification.errors.map {
                        "GUEST_TOOL_ATTESTATION_FAILED:" +
                            tool.manifest.id + ":" + it
                    }
                return@forEach
            }

            if (
                tool.entrypoint.canonicalFile !=
                java.io.File(
                    tool.directory,
                    tool.manifest.entrypoint,
                ).canonicalFile
            ) {
                blockers +=
                    "GUEST_TOOL_ENTRYPOINT_PATH_MISMATCH:" +
                        tool.manifest.id
                return@forEach
            }

            binds +=
                RuntimeBindSpec(
                    hostPath = tool.directory,
                    guestPath = tool.manifest.guestRoot,
                    readOnly = false,
                    purpose =
                        "guest tool " +
                            tool.manifest.id +
                            " " +
                            tool.manifest.version,
                    authority = BindAuthority.SYSTEM,
                )
        }

        if (blockers.isEmpty()) {
            val validation =
                RuntimeBindPolicy.validate(
                    binds = binds,
                    allowedHostRoots = allowedHostRoots,
                )
            blockers += validation.errors
        }

        return GuestToolOverlayPlan(
            valid = blockers.isEmpty(),
            binds =
                if (blockers.isEmpty()) {
                    binds
                } else {
                    emptyList()
                },
            blockers = blockers.distinct(),
        )
    }
}
