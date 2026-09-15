package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestGraphicsTransportPlannerTest {
    private fun snapshot(
        opaqueFd: Boolean = true,
        dmaBuf: Boolean = true,
        ahb: Boolean = true,
        syncFd: Boolean = true,
    ) =
        VulkanExternalResourceSnapshot(
            status = "ok",
            protocol = VulkanExternalResourceProbe.PROTOCOL_VERSION,
            vendorId = 1,
            deviceId = 2,
            instanceExtensionCount = 10,
            deviceExtensionCount = 20,
            khrExternalMemoryCapabilities = true,
            khrExternalMemory = true,
            khrExternalMemoryFd = opaqueFd,
            extExternalMemoryDmaBuf = dmaBuf,
            androidExternalMemoryAhb = ahb,
            khrExternalSemaphore = syncFd,
            khrExternalSemaphoreFd = syncFd,
            khrExternalFence = syncFd,
            khrExternalFenceFd = syncFd,
            queryExternalBufferProperties = true,
            opaqueFdQueried = opaqueFd,
            opaqueFdImportable = opaqueFd,
            opaqueFdExportable = opaqueFd,
            ahbQueried = ahb,
            ahbImportable = ahb,
            ahbExportable = ahb,
            dmaBufQueried = dmaBuf,
            dmaBufImportable = dmaBuf,
            dmaBufExportable = dmaBuf,
            raw = "fixture",
        )

    private fun ahbSnapshot(
        canonical: Boolean,
    ) =
        VulkanAhardwareBufferImportSnapshot(
            status = "ok",
            protocol = VulkanAhardwareBufferImportProbe.PROTOCOL_VERSION,
            apiMajor = 1,
            apiMinor = 3,
            vendorId = 1,
            deviceId = 2,
            allocationSize = if (canonical) 16_384 else 0,
            memoryTypeBits = if (canonical) 3 else 0,
            ahbAllocateResult = 0,
            propertyQueryResult = if (canonical) 0 else -9,
            vulkan11OrNewer = true,
            ahbExtension = true,
            foreignQueueExtension = true,
            queueFamilyAvailable = true,
            deviceCreated = true,
            queryFunctionAvailable = true,
            ahbAllocated = true,
            propertiesQuerySucceeded = canonical,
            allocationSizeNonzero = canonical,
            memoryTypeBitsNonzero = canonical,
            nativeCanonicalImportClaim = canonical,
            raw = "fixture-ahb",
        )

    @Test
    fun `opaque fd is preferred but never promoted without guest implementation`() {
        val plan =
            GuestGraphicsTransportPlanner.plan(snapshot())

        assertEquals(
            GuestGraphicsTransportCandidate.OPAQUE_FD,
            plan.candidate,
        )
        assertTrue(plan.capabilityProbeReady)
        assertTrue(plan.hostFoundationReady)
        assertFalse(plan.guestTransportReady)
        assertFalse(plan.implementationAttemptEligible)
        assertTrue(
            "GUEST_GRAPHICS_HANDLE_RECEIVE_NOT_IMPLEMENTED" in
                plan.blockers,
        )
        assertTrue(
            "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED" in
                plan.blockers,
        )
        assertTrue(
            "GUEST_GRAPHICS_SYNCHRONIZATION_NOT_IMPLEMENTED" in
                plan.blockers,
        )
    }

    @Test
    fun `dma buf becomes fallback when opaque fd route is unavailable`() {
        val plan =
            GuestGraphicsTransportPlanner.plan(
                snapshot(opaqueFd = false),
            )

        assertEquals(
            GuestGraphicsTransportCandidate.DMA_BUF_FD,
            plan.candidate,
        )
        assertFalse(plan.implementationAttemptEligible)
    }

    @Test
    fun `ahb host broker requires canonical device query`() {
        val plan =
            GuestGraphicsTransportPlanner.plan(
                snapshot(
                    opaqueFd = false,
                    dmaBuf = false,
                    ahb = true,
                    syncFd = false,
                ),
                ahbImportSnapshot = ahbSnapshot(canonical = false),
            )

        assertEquals(
            GuestGraphicsTransportCandidate.AHB_HOST_BROKER_ONLY,
            plan.candidate,
        )
        assertFalse(plan.canonicalAhbCapabilityReady)
        assertTrue(
            "AHB_CANONICAL_IMPORT_QUERY_NOT_VERIFIED" in
                plan.blockers,
        )
        assertTrue(
            "BOX64_AHARDWAREBUFFER_BRIDGE_NOT_VERIFIED" in
                plan.blockers,
        )
        assertFalse(plan.implementationAttemptEligible)
    }

    @Test
    fun `canonical ahb evidence removes only the capability blocker`() {
        val plan =
            GuestGraphicsTransportPlanner.plan(
                snapshot(
                    opaqueFd = false,
                    dmaBuf = false,
                    ahb = true,
                    syncFd = false,
                ),
                ahbImportSnapshot = ahbSnapshot(canonical = true),
            )

        assertTrue(plan.canonicalAhbCapabilityReady)
        assertFalse(
            "AHB_CANONICAL_IMPORT_QUERY_NOT_VERIFIED" in
                plan.blockers,
        )
        assertTrue(
            "BOX64_AHARDWAREBUFFER_BRIDGE_NOT_VERIFIED" in
                plan.blockers,
        )
        assertTrue(
            "GUEST_GRAPHICS_RESOURCE_IMPORT_NOT_IMPLEMENTED" in
                plan.blockers,
        )
        assertFalse(plan.implementationAttemptEligible)
    }

    @Test
    fun `failed capability probe returns no candidate`() {
        val failed =
            snapshot().copy(
                status = "instance-create-failed",
            )

        val plan =
            GuestGraphicsTransportPlanner.plan(failed)

        assertEquals(
            GuestGraphicsTransportCandidate.NONE,
            plan.candidate,
        )
        assertFalse(plan.capabilityProbeReady)
        assertFalse(plan.implementationAttemptEligible)
        assertEquals(
            listOf("HOST_VULKAN_EXTERNAL_RESOURCE_PROBE_NOT_READY"),
            plan.blockers,
        )
    }
}
