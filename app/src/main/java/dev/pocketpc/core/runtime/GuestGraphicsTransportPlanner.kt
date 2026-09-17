package dev.pocketpc.core.runtime

enum class GuestGraphicsTransportCandidate {
    NONE,
    OPAQUE_FD,
    DMA_BUF_FD,
    AHB_HOST_BROKER_ONLY,
}

data class GuestGraphicsTransportPlan(
    val candidate: GuestGraphicsTransportCandidate,
    val capabilityProbeReady: Boolean,
    val hostFoundationReady: Boolean,
    val guestTransportReady: Boolean,
    val blockers: List<String>,
    val canonicalAhbCapabilityReady: Boolean = false,
) {
    val implementationAttemptEligible: Boolean
        get() =
            candidate != GuestGraphicsTransportCandidate.NONE &&
                capabilityProbeReady &&
                hostFoundationReady &&
                guestTransportReady &&
                blockers.isEmpty()
}

object GuestGraphicsTransportPlanner {
    const val BLOCKER_HOST_OPAQUE_FD_BROKER =
        "HOST_OPAQUE_FD_IMAGE_BROKER_NOT_IMPLEMENTED"
    const val BLOCKER_HOST_DMA_BUF_BROKER =
        "HOST_DMA_BUF_IMAGE_BROKER_NOT_IMPLEMENTED"
    const val BLOCKER_PVI1_PROTOCOL =
        "PVI1_EXTERNAL_IMAGE_PROTOCOL_NOT_IMPLEMENTED"
    const val BLOCKER_GUEST_VULKAN_IMPORT_PRIMITIVE =
        "GUEST_VULKAN_IMPORT_PRIMITIVE_NOT_IMPLEMENTED"

    fun plan(
        snapshot: VulkanExternalResourceSnapshot?,
        ahbImportSnapshot: VulkanAhardwareBufferImportSnapshot? = null,
    ): GuestGraphicsTransportPlan {
        if (snapshot?.probeSucceeded != true) {
            return GuestGraphicsTransportPlan(
                candidate = GuestGraphicsTransportCandidate.NONE,
                capabilityProbeReady = false,
                hostFoundationReady = baseFoundationReady(),
                guestTransportReady = false,
                blockers = listOf(
                    "HOST_VULKAN_EXTERNAL_RESOURCE_PROBE_NOT_READY",
                ),
                canonicalAhbCapabilityReady =
                    ahbImportSnapshot?.canonicalImportQuerySupported == true,
            )
        }

        val canonicalAhbReady =
            ahbImportSnapshot?.canonicalImportQuerySupported == true

        val candidate =
            when {
                snapshot.opaqueFdMemoryRouteAdvertised &&
                    snapshot.fdSynchronizationRouteAdvertised ->
                    GuestGraphicsTransportCandidate.OPAQUE_FD

                snapshot.dmaBufMemoryRouteAdvertised &&
                    snapshot.fdSynchronizationRouteAdvertised ->
                    GuestGraphicsTransportCandidate.DMA_BUF_FD

                snapshot.ahardwareBufferMemoryRouteAdvertised &&
                    GuestGraphicsTransportContract
                        .hostAhardwareBufferBrokerImplemented ->
                    GuestGraphicsTransportCandidate
                        .AHB_HOST_BROKER_ONLY

                else ->
                    GuestGraphicsTransportCandidate.NONE
            }

        val hostFoundationReady =
            candidateFoundationReady(candidate)
        val blockers = mutableListOf<String>()
        if (!hostFoundationReady) {
            blockers += "HOST_GRAPHICS_TRANSPORT_FOUNDATION_NOT_READY"
        }

        when (candidate) {
            GuestGraphicsTransportCandidate.NONE ->
                blockers += "NO_VULKAN_EXTERNAL_RESOURCE_ROUTE_ADVERTISED"

            GuestGraphicsTransportCandidate.OPAQUE_FD -> {
                if (!GuestGraphicsTransportContract.hostOpaqueFdImageBrokerImplemented) {
                    blockers += BLOCKER_HOST_OPAQUE_FD_BROKER
                }
                if (!GuestGraphicsTransportContract.externalImagePvi1ProtocolImplemented) {
                    blockers += BLOCKER_PVI1_PROTOCOL
                }
                if (!GuestGraphicsTransportContract.guestVulkanImportPrimitiveImplemented) {
                    blockers += BLOCKER_GUEST_VULKAN_IMPORT_PRIMITIVE
                }
            }

            GuestGraphicsTransportCandidate.DMA_BUF_FD -> {
                if (!GuestGraphicsTransportContract.hostDmaBufImageBrokerImplemented) {
                    blockers += BLOCKER_HOST_DMA_BUF_BROKER
                }
            }

            GuestGraphicsTransportCandidate.AHB_HOST_BROKER_ONLY -> {
                if (!canonicalAhbReady) {
                    blockers += "AHB_CANONICAL_IMPORT_QUERY_NOT_VERIFIED"
                }
                if (
                    !GuestGraphicsTransportContract
                        .box64DirectAhardwareBufferBridgeVerified
                ) {
                    blockers += "BOX64_AHARDWAREBUFFER_BRIDGE_NOT_VERIFIED"
                }
            }
        }

        if (!GuestGraphicsTransportContract.guestReceiveImplemented) {
            blockers += "GUEST_GRAPHICS_HANDLE_RECEIVE_NOT_IMPLEMENTED"
        }
        if (!GuestGraphicsTransportContract.guestImportImplemented) {
            blockers += "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED"
        }
        if (!GuestGraphicsTransportContract.synchronizationImplemented) {
            blockers += "GUEST_GRAPHICS_SYNCHRONIZATION_NOT_IMPLEMENTED"
        }

        val guestReady =
            GuestGraphicsTransportContract.guestReceiveImplemented &&
                GuestGraphicsTransportContract.guestImportImplemented &&
                GuestGraphicsTransportContract.synchronizationImplemented

        return GuestGraphicsTransportPlan(
            candidate = candidate,
            capabilityProbeReady = true,
            hostFoundationReady = hostFoundationReady,
            guestTransportReady = guestReady,
            blockers = blockers.distinct(),
            canonicalAhbCapabilityReady = canonicalAhbReady,
        )
    }

    private fun baseFoundationReady(): Boolean =
        GuestGraphicsTransportContract.descriptorProtocolImplemented &&
            GuestGraphicsTransportContract.ownershipProtocolImplemented &&
            GuestGraphicsTransportContract.externalResourceCapabilityProbeImplemented &&
            GuestGraphicsTransportContract.guestReceivePrimitiveImplemented

    private fun candidateFoundationReady(
        candidate: GuestGraphicsTransportCandidate,
    ): Boolean =
        when (candidate) {
            GuestGraphicsTransportCandidate.OPAQUE_FD ->
                baseFoundationReady() &&
                    GuestGraphicsTransportContract.ancillaryFdTransportPrimitiveImplemented &&
                    GuestGraphicsTransportContract.handleBindingImplemented &&
                    GuestGraphicsTransportContract.hostOpaqueFdImageBrokerImplemented &&
                    GuestGraphicsTransportContract.externalImagePvi1ProtocolImplemented &&
                    GuestGraphicsTransportContract.guestVulkanImportPrimitiveImplemented

            GuestGraphicsTransportCandidate.DMA_BUF_FD ->
                baseFoundationReady() &&
                    GuestGraphicsTransportContract.ancillaryFdTransportPrimitiveImplemented &&
                    GuestGraphicsTransportContract.handleBindingImplemented &&
                    GuestGraphicsTransportContract.hostDmaBufImageBrokerImplemented

            GuestGraphicsTransportCandidate.AHB_HOST_BROKER_ONLY ->
                baseFoundationReady() &&
                    GuestGraphicsTransportContract.hostAhardwareBufferBrokerImplemented &&
                    GuestGraphicsTransportContract.canonicalAhardwareBufferImportProbeImplemented &&
                    GuestGraphicsTransportContract.handleBindingImplemented

            GuestGraphicsTransportCandidate.NONE ->
                baseFoundationReady()
        }
}
