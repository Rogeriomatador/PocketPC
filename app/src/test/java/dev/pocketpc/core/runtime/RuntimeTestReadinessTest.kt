package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(
            result.coreRuntimeReady,
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
    }

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
