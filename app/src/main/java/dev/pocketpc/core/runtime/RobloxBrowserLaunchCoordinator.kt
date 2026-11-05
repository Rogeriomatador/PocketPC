package dev.pocketpc.core.runtime

enum class RobloxBrowserLaunchDecisionKind {
    INVALID_DEEP_LINK,
    NOT_INSTALLED,
    RUNTIME_BLOCKED,
    READY_FOR_CONTROLLED_ATTEMPT,
}

data class RobloxBrowserLaunchDecision(
    val kind: RobloxBrowserLaunchDecisionKind,
    val deepLink: RobloxPlayerDeepLink?,
    val installation: RobloxPlayerInstallation?,
    val blockers: List<String>,
    val detail: String,
) {
    val mayBuildControlledAttempt: Boolean
        get() =
            kind == RobloxBrowserLaunchDecisionKind.READY_FOR_CONTROLLED_ATTEMPT &&
                deepLink != null &&
                installation != null &&
                blockers.isEmpty()
}

/**
 * Pure decision boundary between the PocketPC browser and the Windows runtime.
 *
 * This object never launches Android, Wine, Box64 or a shell. It only validates
 * the Roblox Player protocol request and checks the already-computed readiness
 * state. The opaque deep link may later become one argv element in
 * RobloxInstalledLaunchAttemptPlanner.
 */
object RobloxBrowserLaunchCoordinator {
    const val BLOCKER_INVALID_DEEP_LINK =
        "ROBLOX_BROWSER_DEEP_LINK_INVALID"
    const val BLOCKER_NOT_INSTALLED =
        "ROBLOX_BROWSER_PLAYER_NOT_INSTALLED"
    const val BLOCKER_RUNTIME =
        "ROBLOX_BROWSER_RUNTIME_NOT_READY"

    fun decide(
        rawUri: String,
        readiness: RobloxLaunchReadiness,
    ): RobloxBrowserLaunchDecision {
        val deepLink =
            RobloxPlayerDeepLink.parse(rawUri)
                ?: return RobloxBrowserLaunchDecision(
                    kind = RobloxBrowserLaunchDecisionKind.INVALID_DEEP_LINK,
                    deepLink = null,
                    installation = readiness.selectedInstallation,
                    blockers = listOf(BLOCKER_INVALID_DEEP_LINK),
                    detail =
                        "O link Roblox Player foi rejeitado pelo roteador interno do PocketPC.",
                )

        val installation = readiness.selectedInstallation
        if (
            installation == null ||
            readiness.state == RobloxLaunchReadinessState.NOT_INSTALLED
        ) {
            return RobloxBrowserLaunchDecision(
                kind = RobloxBrowserLaunchDecisionKind.NOT_INSTALLED,
                deepLink = deepLink,
                installation = null,
                blockers =
                    (listOf(BLOCKER_NOT_INSTALLED) + readiness.blockers)
                        .distinct(),
                detail =
                    "O site solicitou o Roblox Player, mas uma instalação Windows válida ainda não foi encontrada no prefixo selecionado.",
            )
        }

        if (!readiness.controlledAttemptReady) {
            return RobloxBrowserLaunchDecision(
                kind = RobloxBrowserLaunchDecisionKind.RUNTIME_BLOCKED,
                deepLink = deepLink,
                installation = installation,
                blockers =
                    (listOf(BLOCKER_RUNTIME) + readiness.blockers)
                        .distinct(),
                detail =
                    "O Roblox Player foi localizado, mas o PocketPC manteve a execução bloqueada porque o runtime ainda não possui todos os gates exigidos.",
            )
        }

        return RobloxBrowserLaunchDecision(
            kind = RobloxBrowserLaunchDecisionKind.READY_FOR_CONTROLLED_ATTEMPT,
            deepLink = deepLink,
            installation = installation,
            blockers = emptyList(),
            detail =
                "O deep-link foi aceito para construir uma tentativa controlada do Roblox Player dentro do runtime Windows do PocketPC.",
        )
    }
}
