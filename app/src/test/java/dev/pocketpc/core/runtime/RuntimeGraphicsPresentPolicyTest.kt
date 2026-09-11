package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for experimental Wine Vulkan ABI v52 selection.
 *
 * These are source-level unit tests. They do not execute Wine, Vulkan, JNI,
 * Android display presentation, or Roblox.
 */
class RuntimeGraphicsPresentPolicyTest {
    private val runtimeIdentity = "runtime:test-identity"

    @Test
    fun defaultWithoutExplicitV52RequestRemainsV51() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = null,
            )

        assertEquals(RuntimeGraphicsPresentMode.V51_ONE_SHOT, selection.mode)
        assertNull(selection.blocker)
        assertTrue(selection.usable)
        assertFalse(selection.continuousV52Selected)
        assertTrue(RuntimeGraphicsPresentPolicy.launchEnvironment(selection).isEmpty())
    }

    @Test
    fun explicitV52RequiresVerifiedMetadataExactIdentityAbiAndCapability() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = validDeclaration(),
            )

        assertEquals(
            RuntimeGraphicsPresentMode.V52_CONTINUOUS_EXPERIMENTAL,
            selection.mode,
        )
        assertNull(selection.blocker)
        assertTrue(selection.usable)
        assertTrue(selection.continuousV52Selected)
        assertEquals(
            mapOf(RuntimeGraphicsPresentPolicy.ENV_CONTINUOUS_PRESENT_V52 to "1"),
            RuntimeGraphicsPresentPolicy.launchEnvironment(selection),
        )
    }

    @Test
    fun unverifiedMetadataFailsClosed() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = validDeclaration().copy(verifiedArtifactMetadata = false),
            )

        assertRejected(
            selection,
            RuntimeGraphicsPresentPolicy.BLOCKER_DECLARATION_UNVERIFIED,
        )
    }

    @Test
    fun runtimeIdentityMismatchFailsClosed() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = validDeclaration().copy(runtimeIdentity = "runtime:other"),
            )

        assertRejected(
            selection,
            RuntimeGraphicsPresentPolicy.BLOCKER_RUNTIME_IDENTITY_MISMATCH,
        )
    }

    @Test
    fun wineVulkanAbiMismatchFailsClosed() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = validDeclaration().copy(wineVulkanAbi = 51),
            )

        assertRejected(
            selection,
            RuntimeGraphicsPresentPolicy.BLOCKER_WINE_VULKAN_ABI_MISMATCH,
        )
    }

    @Test
    fun missingContinuousPresentCapabilityFailsClosed() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = validDeclaration().copy(capabilities = emptySet()),
            )

        assertRejected(
            selection,
            RuntimeGraphicsPresentPolicy.BLOCKER_CAPABILITY_MISSING,
        )
    }

    @Test
    fun declarationCanRemainV51WithoutRequestingV52() {
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration =
                    validDeclaration().copy(
                        requestContinuousPresentV52 = false,
                        verifiedArtifactMetadata = false,
                        wineVulkanAbi = 51,
                        capabilities = emptySet(),
                    ),
            )

        assertEquals(RuntimeGraphicsPresentMode.V51_ONE_SHOT, selection.mode)
        assertNull(selection.blocker)
        assertTrue(selection.usable)
        assertFalse(selection.continuousV52Selected)
        assertTrue(RuntimeGraphicsPresentPolicy.launchEnvironment(selection).isEmpty())
    }

    private fun validDeclaration() =
        RuntimeGraphicsGuestDeclaration(
            runtimeIdentity = runtimeIdentity,
            wineVulkanAbi = RuntimeGraphicsPresentPolicy.WINE_VULKAN_ABI_V52,
            capabilities =
                setOf(RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52),
            verifiedArtifactMetadata = true,
            requestContinuousPresentV52 = true,
        )

    private fun assertRejected(
        selection: RuntimeGraphicsPresentSelection,
        blocker: String,
    ) {
        assertEquals(RuntimeGraphicsPresentMode.V51_ONE_SHOT, selection.mode)
        assertEquals(blocker, selection.blocker)
        assertFalse(selection.usable)
        assertFalse(selection.continuousV52Selected)
        assertTrue(RuntimeGraphicsPresentPolicy.launchEnvironment(selection).isEmpty())
    }
}
