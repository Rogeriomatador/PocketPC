package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTestReadinessTest {
    private val loadedHost =
        NativeHostStatus(
            loaded = true,
            probe = "ok",
            graphicsProbe = "vulkan=ok",
            nativeLibraryDir = "/native",
        )

    @Test
    fun nativeHostIsFirstBlocker() {
        val result =
            RuntimeTestReadinessProbe.assess(
                nativeHost =
                    loadedHost.copy(
                        loaded = false,
                    ),
                substrate =
                    substrate(
                        prootReady = false,
                    ),
                runtime = null,
                installedTools =
                    emptyList(),
                deployedLayers =
                    emptyList(),
            )

        assertEquals(
            "native-host",
            result.firstBlocker?.id,
        )
        assertEquals(
            "native-host",
            result.firstCoreBlocker?.id,
        )
        assertFalse(
            result.coreRuntimeReady,
        )
        assertFalse(
            result.d3dRuntimeReady,
        )
    }

    @Test
    fun prootIsNextBlockerAfterNativeHost() {
        val result =
            RuntimeTestReadinessProbe.assess(
                nativeHost = loadedHost,
                substrate =
                    substrate(
                        prootReady = false,
                    ),
                runtime = null,
                installedTools =
                    emptyList(),
                deployedLayers =
                    emptyList(),
            )

        assertEquals(
            "proot",
            result.firstBlocker?.id,
        )
        assertEquals(
            "proot",
            result.firstCoreBlocker?.id,
        )
    }

    @Test
    fun rootfsIsNextBlockerAfterProot() {
        val result =
            RuntimeTestReadinessProbe.assess(
                nativeHost = loadedHost,
                substrate =
                    substrate(
                        prootReady = true,
                    ),
                runtime = null,
                installedTools =
                    emptyList(),
                deployedLayers =
                    emptyList(),
            )

        assertEquals(
            "rootfs",
            result.firstBlocker?.id,
        )
        assertEquals(
            "rootfs",
            result.firstCoreBlocker?.id,
        )
    }

    @Test
    fun missingDxvkDoesNotBlockCoreWindowsReadiness() {
        val result =
            RuntimeTestReadiness(
                prerequisites =
                    listOf(
                        ready("native-host"),
                        ready("proot"),
                        ready("rootfs"),
                        ready("box64"),
                        ready("wine"),
                        RuntimeTestPrerequisite(
                            id = "dxvk",
                            label = "DXVK",
                            ready = false,
                            detail = "D3D pending",
                        ),
                    ),
            )

        assertTrue(result.coreRuntimeReady)
        assertNull(result.firstCoreBlocker)
        assertFalse(result.d3dRuntimeReady)
        assertEquals(
            "dxvk",
            result.firstD3dBlocker?.id,
        )
        assertEquals(
            "dxvk",
            result.firstBlocker?.id,
        )
    }

    @Test
    fun dxvkCompletesD3dReadinessAfterCoreIsReady() {
        val result =
            RuntimeTestReadiness(
                prerequisites =
                    listOf(
                        ready("native-host"),
                        ready("proot"),
                        ready("rootfs"),
                        ready("box64"),
                        ready("wine"),
                        ready("dxvk"),
                    ),
            )

        assertTrue(result.coreRuntimeReady)
        assertTrue(result.d3dRuntimeReady)
        assertNull(result.firstCoreBlocker)
        assertNull(result.firstD3dBlocker)
        assertNull(result.firstBlocker)
    }

    private fun ready(
        id: String,
    ): RuntimeTestPrerequisite =
        RuntimeTestPrerequisite(
            id = id,
            label = id,
            ready = true,
            detail = "ready",
        )

    private fun substrate(
        prootReady: Boolean,
    ): ExecutionSubstrateStatus =
        ExecutionSubstrateStatus(
            nativeLibraryDir = "/native",
            packagedHostReady = true,
            prootReady = prootReady,
            components = emptyList(),
            state =
                if (prootReady) {
                    "READY"
                } else {
                    "BLOCKED"
                },
            artifactContractApproved =
                prootReady,
            policyDigestsVerified =
                prootReady,
            artifactIntegrityVerified =
                prootReady,
        )
}
