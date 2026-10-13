package dev.pocketpc.core.runtime

/** Immutable identity of one exported PocketPC image generation. */
data class VulkanContinuousPresentResourceIdentity(
    val resourceId: Long,
    val generation: Long,
    val offerSequence: Long,
) {
    val structurallyValid: Boolean
        get() = resourceId > 0L && generation > 0L && offerSequence > 0L
}

data class VulkanContinuousPresentHostLease(
    val resource: VulkanContinuousPresentResourceIdentity,
    val frameSequence: Long,
    val guestReadyValue: Long,
    val hostConsumedValue: Long,
)

enum class VulkanContinuousPresentHostState {
    WAITING_GUEST,
    HOST_CONSUMING,
    POISONED_RECREATE_GENERATION,
}

/**
 * Fail-closed host ownership state for the proposed v52 continuous-present path.
 *
 * This class deliberately contains no JNI/Vulkan execution. It validates the
 * resource generation and the PVS1 timeline transition surrounding a future
 * native host consumer. A caller may signal [VulkanContinuousPresentHostLease.hostConsumedValue]
 * only after [complete] succeeds.
 *
 * A failed consume that cannot prove the image was returned to
 * VK_QUEUE_FAMILY_EXTERNAL poisons this generation. The only safe recovery is
 * to recreate the exported resource/generation rather than pretending the
 * shared image is reusable.
 */
class VulkanContinuousPresentOwnershipState(
    initialResource: VulkanContinuousPresentResourceIdentity,
) {
    private var resource = initialResource.also(::requireResource)
    private var state = VulkanContinuousPresentHostState.WAITING_GUEST
    private var nextFrameSequence = VulkanContinuousPresentTimeline.FIRST_FRAME_SEQUENCE
    private var activeLease: VulkanContinuousPresentHostLease? = null

    val currentResource: VulkanContinuousPresentResourceIdentity
        get() = resource

    val currentState: VulkanContinuousPresentHostState
        get() = state

    val expectedFrameSequence: Long
        get() = nextFrameSequence

    val poisoned: Boolean
        get() = state == VulkanContinuousPresentHostState.POISONED_RECREATE_GENERATION

    /**
     * Accepts only the exact odd guest-ready value for the next expected frame.
     * Duplicate, skipped, stale-generation and cross-resource frames fail closed.
     */
    fun beginHostConsume(
        observedResource: VulkanContinuousPresentResourceIdentity,
        observedTimelineValue: Long,
    ): Result<VulkanContinuousPresentHostLease> =
        runCatching {
            check(state == VulkanContinuousPresentHostState.WAITING_GUEST) {
                if (poisoned) {
                    "VULKAN_CONTINUOUS_PRESENT_GENERATION_POISONED"
                } else {
                    "VULKAN_CONTINUOUS_PRESENT_HOST_ALREADY_CONSUMING"
                }
            }
            requireResource(observedResource)
            require(observedResource == resource) {
                "VULKAN_CONTINUOUS_PRESENT_RESOURCE_IDENTITY_MISMATCH"
            }

            val frameSequence =
                VulkanContinuousPresentTimeline
                    .frameSequenceFromGuestReady(observedTimelineValue)
                    ?: error("VULKAN_CONTINUOUS_PRESENT_GUEST_READY_VALUE_INVALID")
            require(frameSequence == nextFrameSequence) {
                if (frameSequence < nextFrameSequence) {
                    "VULKAN_CONTINUOUS_PRESENT_STALE_FRAME"
                } else {
                    "VULKAN_CONTINUOUS_PRESENT_SKIPPED_FRAME"
                }
            }

            val lease =
                VulkanContinuousPresentHostLease(
                    resource = resource,
                    frameSequence = frameSequence,
                    guestReadyValue = observedTimelineValue,
                    hostConsumedValue =
                        VulkanContinuousPresentTimeline
                            .hostConsumedValue(frameSequence),
                )
            activeLease = lease
            state = VulkanContinuousPresentHostState.HOST_CONSUMING
            lease
        }

    /**
     * Completes a host consume only when native code proved external ownership
     * was restored. The returned even timeline value is then safe to signal.
     */
    fun complete(
        lease: VulkanContinuousPresentHostLease,
        returnedExternal: Boolean,
    ): Result<Long> =
        runCatching {
            check(state == VulkanContinuousPresentHostState.HOST_CONSUMING) {
                "VULKAN_CONTINUOUS_PRESENT_NO_ACTIVE_HOST_CONSUME"
            }
            require(activeLease == lease) {
                "VULKAN_CONTINUOUS_PRESENT_ACTIVE_LEASE_MISMATCH"
            }
            if (!returnedExternal) {
                poison()
                error("VULKAN_CONTINUOUS_PRESENT_EXTERNAL_OWNERSHIP_NOT_RETURNED")
            }

            val consumedValue = lease.hostConsumedValue
            val completedFrame = lease.frameSequence
            activeLease = null

            if (completedFrame == VulkanContinuousPresentTimeline.MAX_FRAME_SEQUENCE) {
                state = VulkanContinuousPresentHostState.POISONED_RECREATE_GENERATION
            } else {
                nextFrameSequence = completedFrame + 1L
                state = VulkanContinuousPresentHostState.WAITING_GUEST
            }
            consumedValue
        }

    /**
     * Aborts a native consume. If external ownership was restored the same odd
     * guest-ready frame may be retried. Otherwise this generation is poisoned.
     */
    fun abort(
        lease: VulkanContinuousPresentHostLease,
        returnedExternal: Boolean,
    ): Result<Unit> =
        runCatching {
            check(state == VulkanContinuousPresentHostState.HOST_CONSUMING) {
                "VULKAN_CONTINUOUS_PRESENT_NO_ACTIVE_HOST_CONSUME"
            }
            require(activeLease == lease) {
                "VULKAN_CONTINUOUS_PRESENT_ACTIVE_LEASE_MISMATCH"
            }

            activeLease = null
            if (returnedExternal) {
                state = VulkanContinuousPresentHostState.WAITING_GUEST
            } else {
                state = VulkanContinuousPresentHostState.POISONED_RECREATE_GENERATION
            }
        }

    /**
     * Installs a freshly-created resource generation after resize/recreation.
     * Reusing the same identity cannot clear a poisoned or exhausted generation.
     */
    fun replaceGeneration(
        replacement: VulkanContinuousPresentResourceIdentity,
    ): Result<Unit> =
        runCatching {
            requireResource(replacement)
            check(state != VulkanContinuousPresentHostState.HOST_CONSUMING) {
                "VULKAN_CONTINUOUS_PRESENT_REPLACE_DURING_HOST_CONSUME"
            }
            require(replacement != resource) {
                "VULKAN_CONTINUOUS_PRESENT_GENERATION_NOT_REPLACED"
            }
            require(
                replacement.resourceId != resource.resourceId ||
                    replacement.generation > resource.generation
            ) {
                "VULKAN_CONTINUOUS_PRESENT_REPLACEMENT_NOT_NEWER"
            }

            resource = replacement
            nextFrameSequence = VulkanContinuousPresentTimeline.FIRST_FRAME_SEQUENCE
            activeLease = null
            state = VulkanContinuousPresentHostState.WAITING_GUEST
        }

    private fun poison() {
        activeLease = null
        state = VulkanContinuousPresentHostState.POISONED_RECREATE_GENERATION
    }

    private fun requireResource(value: VulkanContinuousPresentResourceIdentity) {
        require(value.structurallyValid) {
            "VULKAN_CONTINUOUS_PRESENT_RESOURCE_IDENTITY_INVALID"
        }
    }
}
