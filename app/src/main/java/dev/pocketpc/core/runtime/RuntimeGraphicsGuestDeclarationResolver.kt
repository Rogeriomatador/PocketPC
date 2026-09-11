package dev.pocketpc.core.runtime

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Resolves v52 graphics metadata only from an already-installed, manifest-
 * verified Wine guest tool. The capability sidecar itself must be listed in the
 * verified guest-tool manifest and its current SHA-256 must still match.
 */
object RuntimeGraphicsGuestDeclarationResolver {
    const val CAPABILITY_PATH =
        "share/pocketpc/runtime-graphics-capabilities.json"

    fun resolve(
        expectedRuntimeIdentity: String,
        wineTool: InstalledGuestTool?,
        requestContinuousPresentV52: Boolean,
    ): RuntimeGraphicsGuestDeclaration? {
        if (!requestContinuousPresentV52) return null

        val failed =
            RuntimeGraphicsGuestDeclaration(
                runtimeIdentity = expectedRuntimeIdentity,
                wineVulkanAbi = 0,
                capabilities = emptySet(),
                verifiedArtifactMetadata = false,
                requestContinuousPresentV52 = true,
            )
        val tool = wineTool ?: return failed
        if (tool.manifest.id != "wine") return failed

        val listed =
            tool.manifest.files.singleOrNull {
                it.path == CAPABILITY_PATH &&
                    !it.executable &&
                    it.bytes in 1L..(64L * 1024L)
            } ?: return failed

        val root = runCatching { tool.directory.canonicalFile }.getOrNull()
            ?: return failed
        val file = runCatching {
            File(root, CAPABILITY_PATH).canonicalFile
        }.getOrNull() ?: return failed
        if (
            !file.path.startsWith(root.path + File.separator) ||
            !file.isFile ||
            file.length() != listed.bytes ||
            sha256(file) != listed.sha256
        ) {
            return failed
        }

        val json = runCatching {
            JSONObject(file.readText(Charsets.UTF_8))
        }.getOrNull() ?: return failed
        if (
            json.optInt("schemaVersion", 0) != 1 ||
            json.optInt("wineVulkanAbi", 0) !=
                RuntimeGraphicsPresentPolicy.WINE_VULKAN_ABI_V52 ||
            !json.optBoolean("experimental", false) ||
            json.optBoolean("officialBuildSelected", true) ||
            json.optBoolean("runtimeExecuted", true) ||
            json.optBoolean("integrationExecuted", true) ||
            json.optBoolean("physicalVisibleFrame", true) ||
            json.optBoolean("robloxExecuted", true)
        ) {
            return failed
        }

        val rawCapabilities = json.optJSONArray("capabilities")
            ?: return failed
        val capabilities = buildSet {
            for (index in 0 until rawCapabilities.length()) {
                val value = rawCapabilities.optString(index, "")
                if (value.isBlank() || value.length > 128) return failed
                add(value)
            }
        }
        if (
            capabilities !=
                setOf(RuntimeGraphicsPresentPolicy.CAPABILITY_CONTINUOUS_PRESENT_V52)
        ) {
            return failed
        }

        return RuntimeGraphicsGuestDeclaration(
            runtimeIdentity = expectedRuntimeIdentity,
            wineVulkanAbi = RuntimeGraphicsPresentPolicy.WINE_VULKAN_ABI_V52,
            capabilities = capabilities,
            verifiedArtifactMetadata = true,
            requestContinuousPresentV52 = true,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
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
