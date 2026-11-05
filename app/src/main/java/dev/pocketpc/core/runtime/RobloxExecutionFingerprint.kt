package dev.pocketpc.core.runtime

import java.io.ByteArrayInputStream
import java.io.File

data class RobloxClientIdentity(
    val versionDirectoryName: String,
    val windowsExecutable: String,
    val executableSha256: String,
    val executableBytes: Long,
)

data class RobloxExecutionFingerprint(
    val value: String,
    val client: RobloxClientIdentity,
    val runtimeIdentity: String,
    val layerIdentity: String,
    val guestPrefixRoot: String,
)

object RobloxExecutionFingerprintProbe {
    private const val MAX_ROBLOX_PLAYER_BYTES =
        2L * 1024L * 1024L * 1024L

    fun capture(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers: List<DeployedWindowsRuntimeLayer>,
        prefixPlan: WindowsPrefixPlan,
        discovery: RobloxInstallationDiscovery,
    ): RobloxExecutionFingerprint? {
        val layout = prefixPlan.layout
            ?: return null
        val installation = discovery.selected
            ?: return null

        val versionsRoot =
            runCatching {
                discovery.versionsRoot.canonicalFile
            }.getOrNull()
                ?: return null
        val executable =
            runCatching {
                installation.hostExecutable
                    .canonicalFile
            }.getOrNull()
                ?: return null

        if (
            !RobloxInstallationDiscoveryProbe
                .isStrictDescendant(
                    versionsRoot,
                    executable,
                ) ||
            executable.parentFile?.parentFile !=
                versionsRoot ||
            executable.parentFile?.name !=
                installation.versionDirectoryName ||
            executable.name !=
                "RobloxPlayerBeta.exe" ||
            !SafeTreeOps.isPlainFile(
                executable.toPath(),
            ) ||
            !executable.canRead() ||
            executable.length() <= 0L ||
            executable.length() >
                MAX_ROBLOX_PLAYER_BYTES
        ) {
            return null
        }

        val digest =
            runCatching {
                executable.inputStream().use {
                    Sha256.digest(
                        input = it,
                        maxBytes =
                            MAX_ROBLOX_PLAYER_BYTES,
                    )
                }
            }.getOrNull()
                ?: return null

        if (
            digest.bytes !=
                executable.length() ||
            digest.bytes !=
                installation.bytes
        ) {
            return null
        }

        val runtimeIdentity =
            RuntimeExecutionIdentity.of(
                runtime = runtime,
                tools = tools,
                layers = layers,
            )
        val layerIdentity =
            layerSetIdentity(layers)
        val client =
            RobloxClientIdentity(
                versionDirectoryName =
                    installation
                        .versionDirectoryName,
                windowsExecutable =
                    installation
                        .windowsExecutable,
                executableSha256 =
                    digest.sha256,
                executableBytes =
                    digest.bytes,
            )

        val canonical =
            buildString {
                appendLine(
                    "pocketpc-roblox-execution-v1",
                )
                append("client.version=")
                appendLine(
                    client.versionDirectoryName,
                )
                append("client.windowsPath=")
                appendLine(
                    client.windowsExecutable,
                )
                append("client.sha256=")
                appendLine(
                    client.executableSha256,
                )
                append("client.bytes=")
                appendLine(
                    client.executableBytes,
                )
                append("runtime=")
                appendLine(runtimeIdentity)
                append("layers=")
                appendLine(layerIdentity)
                append("prefix=")
                appendLine(
                    layout.guestPrefixRoot,
                )
            }

        val value =
            Sha256.digest(
                ByteArrayInputStream(
                    canonical.toByteArray(
                        Charsets.UTF_8,
                    ),
                ),
            ).sha256

        return RobloxExecutionFingerprint(
            value = value,
            client = client,
            runtimeIdentity = runtimeIdentity,
            layerIdentity = layerIdentity,
            guestPrefixRoot =
                layout.guestPrefixRoot,
        )
    }

    private fun layerSetIdentity(
        layers: List<DeployedWindowsRuntimeLayer>,
    ): String {
        val canonical =
            buildString {
                appendLine(
                    "pocketpc-windows-layer-set-v1",
                )
                layers
                    .sortedBy {
                        it.manifest.id
                    }
                    .forEach { layer ->
                        append(layer.manifest.id)
                        append('=')
                        appendLine(
                            WindowsRuntimeLayerFingerprint
                                .of(
                                    layer.manifest,
                                ),
                        )
                    }
            }
        return Sha256.digest(
            ByteArrayInputStream(
                canonical.toByteArray(
                    Charsets.UTF_8,
                ),
            ),
        ).sha256
    }
}
