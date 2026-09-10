package dev.pocketpc.core.runtime

enum class RobloxLaunchReadinessState {
    NOT_INSTALLED,
    RUNTIME_BLOCKED,
    READY_FOR_CONTROLLED_ATTEMPT,
    INTEGRATION_SMOKE_PASSED,
}

data class RobloxRuntimeEvidence(
    val playerProcessStarted: Boolean = false,
    val playerWindowPresented: Boolean = false,
    val d3d11PresentObserved: Boolean = false,
    val externalNetworkObserved: Boolean = false,
    val audioOutputObserved: Boolean = false,
    val pointerInputObserved: Boolean = false,
    val keyboardInputObserved: Boolean = false,
    val stableSessionMillis: Long = 0L,
    val crashObserved: Boolean = false,
) {
    val integrationSmokePassed: Boolean
        get() =
            playerProcessStarted &&
                playerWindowPresented &&
                d3d11PresentObserved &&
                externalNetworkObserved &&
                audioOutputObserved &&
                pointerInputObserved &&
                keyboardInputObserved &&
                stableSessionMillis >= MIN_ROBLOX_SMOKE_SESSION_MILLIS &&
                !crashObserved

    companion object {
        const val MIN_ROBLOX_SMOKE_SESSION_MILLIS =
            60_000L
    }
}

data class RobloxLaunchReadiness(
    val state: RobloxLaunchReadinessState,
    val controlledAttemptReady: Boolean,
    val integrationSmokePassed: Boolean,
    val gameplayValidated: Boolean,
    val selectedInstallation: RobloxPlayerInstallation?,
    val blockers: List<String>,
    val detail: String,
)

object RobloxLaunchReadinessProbe {
    const val BLOCKER_VULKAN_TRANSPORT_FOUNDATION =
        "ROBLOX_VULKAN_TRANSPORT_FOUNDATION_NOT_PROVEN"
    const val BLOCKER_GUEST_GRAPHICS_TRANSPORT =
        "ROBLOX_GUEST_GRAPHICS_TRANSPORT_NOT_READY"

    fun assess(
        runtimeReadiness: PcRuntimeReadiness,
        probeEvidence: RuntimeProbeEvidenceState,
        installation: RobloxInstallationDiscovery,
        robloxEvidence: RobloxRuntimeEvidence =
            RobloxRuntimeEvidence(),
        wsiFoundation: PocketPcVulkanWsiFoundationStatus? = null,
    ): RobloxLaunchReadiness {
        val blockers = mutableListOf<String>()
        val selected = installation.selected

        if (selected == null) {
            blockers += installation.blockers.ifEmpty {
                listOf("ROBLOX_CLASSIC_PLAYER_NOT_INSTALLED")
            }
        }
        if (!runtimeReadiness.controlledAttemptReady) {
            blockers += "ROBLOX_BASE_RUNTIME_NOT_READY"
        }
        if (!probeEvidence.box64SmokePassed) {
            blockers += "ROBLOX_BOX64_SMOKE_NOT_PROVEN"
        }
        if (!probeEvidence.wineSmokePassed) {
            blockers += "ROBLOX_WINE_SMOKE_NOT_PROVEN"
        }
        if (!probeEvidence.displayBridgeSmokePassed) {
            blockers += "ROBLOX_DISPLAY_BRIDGE_NOT_PROVEN"
        }
        if (!probeEvidence.winePocketPcWindowSmokePassed) {
            blockers += "ROBLOX_WINE_WINDOW_NOT_PROVEN"
        }
        if (!probeEvidence.windowsProcessSmokePassed) {
            blockers += "ROBLOX_WINDOWS_PROCESS_NOT_PROVEN"
        }
        if (!probeEvidence.d3d11SmokePassed) {
            blockers += "ROBLOX_D3D11_NOT_PROVEN"
        }
        if (wsiFoundation?.readyForWsiImplementation != true) {
            blockers += BLOCKER_VULKAN_TRANSPORT_FOUNDATION
            blockers +=
                wsiFoundation
                    ?.blockers
                    .orEmpty()
                    .map { "ROBLOX_$it" }
        }
        if (wsiFoundation?.guestGraphicsTransportReady != true) {
            blockers += BLOCKER_GUEST_GRAPHICS_TRANSPORT
        }
        if (!PocketPcVulkanWsiContract.implemented) {
            blockers += PocketPcVulkanWsiContract.blocker
        }
        if (!probeEvidence.graphicsPresentationSmokePassed) {
            blockers += "ROBLOX_D3D11_PRESENT_NOT_PROVEN"
        }
        if (!probeEvidence.winsockSmokePassed) {
            blockers += "ROBLOX_WINSOCK_API_NOT_PROVEN"
        }
        if (!probeEvidence.winmmAudioApiSmokePassed) {
            blockers += "ROBLOX_WINMM_API_NOT_PROVEN"
        }
        if (!probeEvidence.rawInputApiSmokePassed) {
            blockers += "ROBLOX_RAW_INPUT_API_NOT_PROVEN"
        }

        val controlledAttemptReady =
            selected != null &&
                blockers.isEmpty()
        val smokePassed =
            controlledAttemptReady &&
                robloxEvidence.integrationSmokePassed

        val state =
            when {
                selected == null ->
                    RobloxLaunchReadinessState.NOT_INSTALLED

                smokePassed ->
                    RobloxLaunchReadinessState.INTEGRATION_SMOKE_PASSED

                controlledAttemptReady ->
                    RobloxLaunchReadinessState.READY_FOR_CONTROLLED_ATTEMPT

                else ->
                    RobloxLaunchReadinessState.RUNTIME_BLOCKED
            }

        val detail =
            when (state) {
                RobloxLaunchReadinessState.NOT_INSTALLED ->
                    "Nenhum RobloxPlayerBeta.exe clássico válido foi encontrado no prefixo Wine selecionado."

                RobloxLaunchReadinessState.RUNTIME_BLOCKED ->
                    "O Player foi localizado, mas um ou mais gates do runtime Windows/WSI ainda não possuem evidência suficiente."

                RobloxLaunchReadinessState.READY_FOR_CONTROLLED_ATTEMPT ->
                    "O runtime atingiu os pré-requisitos para uma tentativa controlada do Player. Isso não afirma que Roblox funciona."

                RobloxLaunchReadinessState.INTEGRATION_SMOKE_PASSED ->
                    "Uma execução Roblox atingiu o smoke de processo, janela, apresentação, rede, áudio e input pelo período mínimo. Gameplay completo continua não validado."
            }

        return RobloxLaunchReadiness(
            state = state,
            controlledAttemptReady = controlledAttemptReady,
            integrationSmokePassed = smokePassed,
            gameplayValidated = false,
            selectedInstallation = selected,
            blockers = blockers.distinct(),
            detail = detail,
        )
    }
}
