package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeGraphicsGuestDeclarationResolverTest {
    private val runtimeIdentity = "runtime-test-identity"
    private val pocketPcRevision = "a".repeat(40)
    private val otherPocketPcRevision = "b".repeat(40)

    @Test
    fun requestOffDoesNotResolveExperimentalMetadata() {
        assertNull(
            RuntimeGraphicsGuestDeclarationResolver.resolveForTest(
                expectedRuntimeIdentity = runtimeIdentity,
                wineTool = null,
                requestContinuousPresentV52 = false,
                expectedPocketPcSourceRevision = pocketPcRevision,
                expectedPocketPcSourceRevisionPinned = true,
            ),
        )
    }

    @Test
    fun exactPinnedRevisionAndVerifiedSidecarPromoteV52() = withWineTool(
        pocketPcSourceRevision = pocketPcRevision,
    ) { tool ->
        val declaration = resolve(tool)

        assertTrue(declaration.verifiedArtifactMetadata)
        assertTrue(declaration.requestContinuousPresentV52)
        assertEquals(
            RuntimeGraphicsPresentPolicy.WINE_VULKAN_ABI_V52,
            declaration.wineVulkanAbi,
        )
        assertEquals(
            setOf(RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52),
            declaration.capabilities,
        )
        assertEquals(runtimeIdentity, declaration.runtimeIdentity)
        assertTrue(
            RuntimeGraphicsGuestDeclarationResolver
                .isResolverVerifiedInstalledPackage(
                    declaration = declaration,
                    expectedRuntimeIdentity = runtimeIdentity,
                ),
        )

        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = runtimeIdentity,
                declaration = declaration,
            )
        assertEquals(
            RuntimeGraphicsPresentMode.V52_CONTINUOUS_EXPERIMENTAL,
            selection.mode,
        )
        assertTrue(selection.usable)
        assertTrue(selection.continuousV52Selected)
    }

    @Test
    fun verificationSealCannotBeRetargetedWithDataClassCopy() = withWineTool(
        pocketPcSourceRevision = pocketPcRevision,
    ) { tool ->
        val copied = resolve(tool).copy(runtimeIdentity = "runtime-other")

        assertFalse(
            RuntimeGraphicsGuestDeclarationResolver
                .isResolverVerifiedInstalledPackage(
                    declaration = copied,
                    expectedRuntimeIdentity = "runtime-other",
                ),
        )
        val selection =
            RuntimeGraphicsPresentPolicy.select(
                expectedRuntimeIdentity = "runtime-other",
                declaration = copied,
            )
        assertEquals(
            RuntimeGraphicsPresentPolicy.BLOCKER_PACKAGE_BINDING_UNVERIFIED,
            selection.blocker,
        )
        assertFalse(selection.usable)
    }

    @Test
    fun differentPocketPcRevisionFailsClosed() = withWineTool(
        pocketPcSourceRevision = otherPocketPcRevision,
    ) { tool ->
        assertFalse(resolve(tool).verifiedArtifactMetadata)
    }

    @Test
    fun unpinnedRunningApkFailsClosed() = withWineTool(
        pocketPcSourceRevision = pocketPcRevision,
    ) { tool ->
        val declaration =
            RuntimeGraphicsGuestDeclarationResolver.resolveForTest(
                expectedRuntimeIdentity = runtimeIdentity,
                wineTool = tool,
                requestContinuousPresentV52 = true,
                expectedPocketPcSourceRevision = pocketPcRevision,
                expectedPocketPcSourceRevisionPinned = false,
            )

        assertFalse(requireNotNull(declaration).verifiedArtifactMetadata)
    }

    @Test
    fun sidecarMutationAfterManifestHashFailsClosed() = withWineTool(
        pocketPcSourceRevision = pocketPcRevision,
    ) { tool ->
        val sidecar =
            File(
                tool.directory,
                RuntimeGraphicsGuestDeclarationResolver.CAPABILITY_PATH,
            )
        sidecar.appendText("\n ", Charsets.UTF_8)

        assertFalse(resolve(tool).verifiedArtifactMetadata)
    }

    @Test
    fun duplicateCapabilityFailsClosedEvenWhenHashMatches() = withWineTool(
        pocketPcSourceRevision = pocketPcRevision,
        capabilities =
            listOf(
                RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52,
                RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52,
            ),
    ) { tool ->
        assertFalse(resolve(tool).verifiedArtifactMetadata)
    }

    private fun resolve(tool: InstalledGuestTool): RuntimeGraphicsGuestDeclaration =
        requireNotNull(
            RuntimeGraphicsGuestDeclarationResolver.resolveForTest(
                expectedRuntimeIdentity = runtimeIdentity,
                wineTool = tool,
                requestContinuousPresentV52 = true,
                expectedPocketPcSourceRevision = pocketPcRevision,
                expectedPocketPcSourceRevisionPinned = true,
            ),
        )

    private fun withWineTool(
        pocketPcSourceRevision: String,
        capabilities: List<String> =
            listOf(RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52),
        block: (InstalledGuestTool) -> Unit,
    ) {
        val root = Files.createTempDirectory("pocketpc-v52-resolver-").toFile()
        try {
            val sidecar =
                File(
                    root,
                    RuntimeGraphicsGuestDeclarationResolver.CAPABILITY_PATH,
                )
            require(sidecar.parentFile?.mkdirs() == true || sidecar.parentFile?.isDirectory == true)
            val capabilityJson =
                buildString {
                    append("{\n")
                    append("  \"schemaVersion\": 1,\n")
                    append("  \"wineVulkanAbi\": 52,\n")
                    append("  \"capabilities\": [")
                    append(capabilities.joinToString(",") { "\"$it\"" })
                    append("],\n")
                    append("  \"pocketPcSourceRevision\": \"$pocketPcSourceRevision\",\n")
                    append("  \"experimental\": true,\n")
                    append("  \"officialBuildSelected\": false,\n")
                    append("  \"runtimeExecuted\": false,\n")
                    append("  \"integrationExecuted\": false,\n")
                    append("  \"physicalVisibleFrame\": false,\n")
                    append("  \"robloxExecuted\": false\n")
                    append("}\n")
                }
            sidecar.writeText(capabilityJson, Charsets.UTF_8)

            val entrypoint = File(root, "bin/wine64").apply {
                parentFile?.mkdirs()
                writeText("test", Charsets.UTF_8)
            }
            val manifest =
                GuestToolManifest(
                    schemaVersion = 1,
                    id = "wine",
                    version = "test-v52",
                    architecture = "x86_64",
                    guestRoot = "/opt/pocketpc/wine",
                    entrypoint = "bin/wine64",
                    sourceCommit = "c".repeat(40),
                    license = "LGPL-2.1-or-later",
                    files =
                        listOf(
                            GuestToolFile(
                                path = RuntimeGraphicsGuestDeclarationResolver.CAPABILITY_PATH,
                                sha256 = sha256(sidecar),
                                bytes = sidecar.length(),
                                executable = false,
                            ),
                        ),
                    executionMode = "box64-x86_64",
                )
            block(
                InstalledGuestTool(
                    manifest = manifest,
                    directory = root,
                    entrypoint = entrypoint,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
