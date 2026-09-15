package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HeadlessDxvkDiagnosticPlanTest {
    private fun capabilities(
        headless: Boolean = true,
        swapchain: Boolean = true,
    ) =
        VulkanWsiCapabilitySnapshot(
            status = "ok",
            protocol = VulkanWsiCapabilitySnapshot.CURRENT_PROTOCOL,
            vendorId = 1L,
            deviceId = 2L,
            instanceExtensionCount = 10,
            deviceExtensionCount = 20,
            khrSurface = true,
            khrAndroidSurface = false,
            extHeadlessSurface = headless,
            khrExternalMemoryCapabilities = true,
            khrSwapchain = swapchain,
            androidExternalMemoryAhb = false,
            khrExternalMemory = true,
            khrExternalMemoryFd = true,
            khrTimelineSemaphore = true,
            khrSynchronization2 = true,
            raw = "fixture",
        )

    @Test
    fun validatedDependenciesProduceDiagnosticOnlyLaunch() {
        val root = Files.createTempDirectory("pocketpc-headless-dxvk-").toFile()
        try {
            val plan =
                HeadlessDxvkDiagnosticPlanner.build(
                    prefixPlan = WindowsPrefixPlanner.plan(root, "headless"),
                    capabilities = capabilities(),
                    box64RuntimeValidated = true,
                    wineRuntimeValidated = true,
                    dxvkLayerReady = true,
                )

            assertTrue(plan.blockers.joinToString(), plan.ready)
            assertTrue(plan.diagnosticOnly)
            assertFalse(plan.visiblePresentExpected)
            assertFalse(plan.mayPromoteRobloxGraphics)
            assertEquals(
                "1",
                plan.environment[
                    HeadlessDxvkDiagnosticPlanner.HEADLESS_ENV
                ],
            )
            assertEquals(
                WineLaunchPlanner.DXVK_DLL_OVERRIDES,
                plan.environment["WINEDLLOVERRIDES"],
            )
            assertEquals(
                HeadlessDxvkDiagnosticPlanner.PRESENT_SMOKE_EXE,
                plan.argv.last(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingHeadlessCapabilityFailsClosed() {
        val root = Files.createTempDirectory("pocketpc-headless-noext-").toFile()
        try {
            val plan =
                HeadlessDxvkDiagnosticPlanner.build(
                    prefixPlan = WindowsPrefixPlanner.plan(root, "headless"),
                    capabilities = capabilities(headless = false),
                    box64RuntimeValidated = true,
                    wineRuntimeValidated = true,
                    dxvkLayerReady = true,
                )

            assertFalse(plan.ready)
            assertTrue(plan.argv.isEmpty())
            assertTrue(
                plan.blockers.contains(
                    "VK_EXT_HEADLESS_SURFACE_NOT_ADVERTISED",
                ),
            )
            assertFalse(
                plan.environment.containsKey(
                    HeadlessDxvkDiagnosticPlanner.HEADLESS_ENV,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDxvkOrRuntimeNeverGetsDiagnosticEnvironment() {
        val root = Files.createTempDirectory("pocketpc-headless-blocked-").toFile()
        try {
            val plan =
                HeadlessDxvkDiagnosticPlanner.build(
                    prefixPlan = WindowsPrefixPlanner.plan(root, "headless"),
                    capabilities = capabilities(),
                    box64RuntimeValidated = false,
                    wineRuntimeValidated = true,
                    dxvkLayerReady = false,
                )

            assertFalse(plan.ready)
            assertTrue(plan.blockers.contains("DXVK_LAYER_NOT_READY"))
            assertTrue(plan.blockers.contains("BOX64_RUNTIME_NOT_VALIDATED"))
            assertTrue(plan.environment.isEmpty())
            assertFalse(plan.mayPromoteRobloxGraphics)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun swapchainCapabilityIsRequiredEvenForHeadlessDiagnostic() {
        val root = Files.createTempDirectory("pocketpc-headless-noswap-").toFile()
        try {
            val plan =
                HeadlessDxvkDiagnosticPlanner.build(
                    prefixPlan = WindowsPrefixPlanner.plan(root, "headless"),
                    capabilities = capabilities(swapchain = false),
                    box64RuntimeValidated = true,
                    wineRuntimeValidated = true,
                    dxvkLayerReady = true,
                )

            assertFalse(plan.ready)
            assertTrue(
                plan.blockers.contains("VK_KHR_SWAPCHAIN_NOT_ADVERTISED"),
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
