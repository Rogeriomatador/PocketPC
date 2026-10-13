package dev.pocketpc.core.runtime

import java.io.ByteArrayInputStream

object RuntimeExecutionIdentity {
    fun of(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        layers:
            List<DeployedWindowsRuntimeLayer> =
            emptyList(),
    ): String {
        val canonical =
            buildString {
                appendLine("pocketpc-runtime-identity-v1")
                appendLine(
                    runtime.manifest
                        .rootfsSha256
                        .lowercase(),
                )
                appendLine(runtime.manifest.id)
                appendLine(runtime.manifest.version)

                tools
                    .sortedBy {
                        it.manifest.id
                    }
                    .forEach { tool ->
                        append("tool:")
                        append(tool.manifest.id)
                        append('=')
                        appendLine(
                            GuestToolFingerprint.of(
                                tool.manifest,
                            ),
                        )
                    }

                layers
                    .sortedBy {
                        it.manifest.id
                    }
                    .forEach { layer ->
                        append("layer:")
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
