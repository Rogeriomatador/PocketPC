package dev.pocketpc.core.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Lifecycle-owned source runner for the experimental v52 continuous Present
 * path. A caller must already have authenticated PGH1, offered PVI1/PVS1 and
 * confirmed the guest import before entering this loop.
 *
 * Cancellation is the normal lifetime boundary. Model delivery is deliberately
 * not promoted to Android physical-visible or Roblox evidence.
 */
object RuntimeDisplayContinuousPresentV52Runner {
    const val BLOCKER_SELECTION_REQUIRED =
        "VULKAN_CONTINUOUS_PRESENT_V52_SELECTION_REQUIRED"
    const val BLOCKER_IMPORT_REQUIRED =
        "VULKAN_CONTINUOUS_PRESENT_V52_IMPORT_REQUIRED"
    const val BLOCKER_PORT_OPEN_FAILED =
        "VULKAN_CONTINUOUS_PRESENT_V52_PORT_OPEN_FAILED"
    const val BLOCKER_GENERATION_POISONED =
        "VULKAN_CONTINUOUS_PRESENT_V52_GENERATION_POISONED"
    const val BLOCKER_TIMELINE_REJECTED =
        "VULKAN_CONTINUOUS_PRESENT_V52_TIMELINE_REJECTED"
    const val BLOCKER_INTERNAL_STATE =
        "VULKAN_CONTINUOUS_PRESENT_V52_INTERNAL_STATE_REJECTED"

    data class TerminalState(
        val blocker: String,
        val resourceId: Long,
        val generation: Long,
        val frameSequence: Long?,
        val generationMustBeRecreated: Boolean,
        val modelFramesDelivered: Long,
        val hostVisibleFrameValidated: Boolean = false,
        val robloxValidated: Boolean = false,
    )

    suspend fun runUntilCancelled(
        selection: RuntimeGraphicsPresentSelection,
        importedOffer: GuestGraphicsSessionOrchestrator.ResourceOffer,
        windowId: Long,
        desktopBridge: RuntimeDesktopBridge,
        timeoutMillis: Long = VulkanContinuousPresentHostCoordinator.DEFAULT_TIMEOUT_MILLIS,
        onHostStep: (VulkanContinuousPresentHostStep) -> Unit = {},
    ): TerminalState? {
        require(selection.continuousV52Selected) {
            BLOCKER_SELECTION_REQUIRED
        }
        require(importedOffer.guestImportConfirmed) {
            BLOCKER_IMPORT_REQUIRED
        }
        require(windowId > 0L) {
            "VULKAN_CONTINUOUS_PRESENT_V52_WINDOW_INVALID"
        }

        val portResult =
            VulkanContinuousPresentBrokerPort.open(
                offer = importedOffer,
                timeoutMillis = timeoutMillis,
            )
        val port =
            portResult.getOrElse { failure ->
                return TerminalState(
                    blocker =
                        BLOCKER_PORT_OPEN_FAILED + ":" +
                            safeDetail(failure),
                    resourceId = importedOffer.resourceId,
                    generation = importedOffer.generation,
                    frameSequence = null,
                    generationMustBeRecreated = false,
                    modelFramesDelivered = 0L,
                )
            }

        val resource =
            VulkanContinuousPresentResourceIdentity(
                resourceId = importedOffer.resourceId,
                generation = importedOffer.generation,
                offerSequence = importedOffer.ownership.sequence,
            )
        val coordinator =
            VulkanContinuousPresentHostCoordinator(
                initialResource = resource,
                windowId = windowId,
                port = port,
                desktopFrameSender = desktopBridge::presentExternalVulkanFrame,
            )

        var delivered = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val step = coordinator.consumeNextFrame(timeoutMillis)
                onHostStep(step)
                when (step.status) {
                    VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_GUEST_RELEASED -> {
                        delivered += 1L
                    }

                    VulkanContinuousPresentHostStepStatus.MODEL_DELIVERED_SIGNAL_PENDING -> {
                        // The coordinator owns the pending even-value retry. Do not
                        // read or deliver another frame until it clears that signal.
                    }

                    VulkanContinuousPresentHostStepStatus.WAIT_FAILED,
                    VulkanContinuousPresentHostStepStatus.READBACK_RETRYABLE,
                    VulkanContinuousPresentHostStepStatus.MODEL_DELIVERY_RETRYABLE -> {
                        // These states preserve the same generation/frame contract.
                        // Retrying cannot skip to frame N+1.
                    }

                    VulkanContinuousPresentHostStepStatus.READBACK_POISONED_GENERATION -> {
                        return TerminalState(
                            blocker = BLOCKER_GENERATION_POISONED + ":" + safeToken(step.detail),
                            resourceId = step.resource.resourceId,
                            generation = step.resource.generation,
                            frameSequence = step.frameSequence,
                            generationMustBeRecreated = true,
                            modelFramesDelivered = delivered,
                        )
                    }

                    VulkanContinuousPresentHostStepStatus.TIMELINE_REJECTED -> {
                        return TerminalState(
                            blocker = BLOCKER_TIMELINE_REJECTED + ":" + safeToken(step.detail),
                            resourceId = step.resource.resourceId,
                            generation = step.resource.generation,
                            frameSequence = step.frameSequence,
                            generationMustBeRecreated = coordinator.generationMustBeRecreated,
                            modelFramesDelivered = delivered,
                        )
                    }

                    VulkanContinuousPresentHostStepStatus.INTERNAL_STATE_REJECTED -> {
                        return TerminalState(
                            blocker = BLOCKER_INTERNAL_STATE + ":" + safeToken(step.detail),
                            resourceId = step.resource.resourceId,
                            generation = step.resource.generation,
                            frameSequence = step.frameSequence,
                            generationMustBeRecreated = coordinator.generationMustBeRecreated,
                            modelFramesDelivered = delivered,
                        )
                    }
                }
            }
        } finally {
            port.close()
        }
    }

    private fun safeDetail(failure: Throwable): String =
        safeToken(failure.message ?: failure.javaClass.simpleName)

    private fun safeToken(value: String): String =
        value.take(384)
            .map { character ->
                if (
                    character.isLetterOrDigit() ||
                    character == '_' || character == '-' || character == '.' ||
                    character == ':'
                ) {
                    character
                } else {
                    '_'
                }
            }.joinToString(separator = "")
            .ifBlank { "UNKNOWN" }
}
