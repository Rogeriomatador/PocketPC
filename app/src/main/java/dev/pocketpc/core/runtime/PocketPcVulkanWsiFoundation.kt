package dev.pocketpc.core.runtime

data class PocketPcVulkanWsiFoundationStatus(
    val nativeHostLoaded: Boolean,
    val sameProcessTransportStructurallyReady: Boolean,
    val crossProcessTransportVerified: Boolean,
    val distinctProcessesObserved: Boolean,
    val readyForWsiImplementation: Boolean,
    val blockers: List<String>,
)

object PocketPcVulkanWsiFoundationProbe {
    const val BLOCKER_NATIVE_HOST =
        "VULKAN_WSI_NATIVE_HOST_NOT_READY"
    const val BLOCKER_AHB_SAME_PROCESS =
        "VULKAN_WSI_AHARDWAREBUFFER_BASE_NOT_PROVEN"
    const val BLOCKER_AHB_CROSS_PROCESS =
        "VULKAN_WSI_AHARDWAREBUFFER_CROSS_PROCESS_NOT_PROVEN"
    const val BLOCKER_DISTINCT_PROCESS =
        "VULKAN_WSI_DISTINCT_PROCESS_NOT_PROVEN"

    fun assess(
        nativeHost: NativeHostStatus,
        crossProcessEvidence: HardwareBufferCrossProcessEvidence?,
    ): PocketPcVulkanWsiFoundationStatus {
        val sameProcessReady =
            nativeHost.hardwareBufferProbe.contains(
                ";cross_process_transport=structurally-ready",
            ) &&
                nativeHost.hardwareBufferProbe.contains(
                    ";send_handle=ok",
                ) &&
                nativeHost.hardwareBufferProbe.contains(
                    ";recv_handle=ok",
                ) &&
                nativeHost.hardwareBufferProbe.contains(
                    ";descriptor_match=yes",
                )

        val crossProcessVerified =
            crossProcessEvidence
                ?.handleTransportVerified == true &&
                crossProcessEvidence.error == null
        val distinctProcesses =
            crossProcessEvidence
                ?.distinctProcesses == true &&
                crossProcessEvidence.senderPid > 0 &&
                crossProcessEvidence.receiverPid > 0 &&
                crossProcessEvidence.senderPid !=
                    crossProcessEvidence.receiverPid

        val blockers = mutableListOf<String>()
        if (!nativeHost.loaded) {
            blockers += BLOCKER_NATIVE_HOST
        }
        if (!sameProcessReady) {
            blockers += BLOCKER_AHB_SAME_PROCESS
        }
        if (!crossProcessVerified) {
            blockers += BLOCKER_AHB_CROSS_PROCESS
        }
        if (!distinctProcesses) {
            blockers += BLOCKER_DISTINCT_PROCESS
        }

        return PocketPcVulkanWsiFoundationStatus(
            nativeHostLoaded = nativeHost.loaded,
            sameProcessTransportStructurallyReady = sameProcessReady,
            crossProcessTransportVerified = crossProcessVerified,
            distinctProcessesObserved = distinctProcesses,
            readyForWsiImplementation = blockers.isEmpty(),
            blockers = blockers.distinct(),
        )
    }
}
