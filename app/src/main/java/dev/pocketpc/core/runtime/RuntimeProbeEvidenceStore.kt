package dev.pocketpc.core.runtime

import android.content.Context
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

data class RuntimeProbeEvidenceState(
    val box64SmokePassed: Boolean,
    val wineSmokePassed: Boolean,
)

object GuestToolFingerprint {
    fun of(
        manifest: GuestToolManifest,
    ): String {
        val canonical =
            buildString {
                appendLine(manifest.id)
                appendLine(manifest.version)
                appendLine(manifest.architecture)
                appendLine(manifest.executionMode)
                appendLine(manifest.sourceCommit)
                appendLine(manifest.license)
                appendLine(manifest.entrypoint)
                manifest.files
                    .sortedBy { it.path }
                    .forEach { item ->
                        append(item.path)
                        append('|')
                        append(item.bytes)
                        append('|')
                        append(item.sha256)
                        append('|')
                        append(item.executable)
                        appendLine()
                    }
            }

        return Sha256.digest(
            ByteArrayInputStream(
                canonical.toByteArray(
                    StandardCharsets.UTF_8,
                ),
            ),
        ).sha256
    }
}

class RuntimeProbeEvidenceStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    fun stateFor(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
    ): RuntimeProbeEvidenceState {
        val box64Key =
            evidenceKey(
                runtime = runtime,
                tools = tools,
                ids = listOf("box64"),
            )
        val wineKey =
            evidenceKey(
                runtime = runtime,
                tools = tools,
                ids = listOf("box64", "wine"),
            )

        return RuntimeProbeEvidenceState(
            box64SmokePassed =
                box64Key != null &&
                    prefs.getString(
                        KEY_BOX64,
                        null,
                    ) == box64Key,
            wineSmokePassed =
                wineKey != null &&
                    prefs.getString(
                        KEY_WINE,
                        null,
                    ) == wineKey,
        )
    }

    fun recordIfValid(
        probe: GuestRuntimeProbe,
        result: ProotExecutionResult,
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
    ): Boolean {
        if (
            !result.passed ||
            result.outputTruncated
        ) {
            return false
        }

        val key =
            when (probe) {
                GuestRuntimeProbe.BOX64_SMOKE -> {
                    if (
                        !result.output.contains(
                            "POCKETPC_BOX64_SMOKE_OK",
                        ) ||
                        !result.output.contains(
                            "box64_x86_64_smoke=passed",
                        )
                    ) {
                        return false
                    }
                    evidenceKey(
                        runtime,
                        tools,
                        listOf("box64"),
                    )
                }
                GuestRuntimeProbe.WINE_SMOKE -> {
                    if (
                        !result.output.contains(
                            "POCKETPC_WIN64_SMOKE_OK",
                        ) ||
                        !result.output.contains(
                            "wine_win64_smoke=passed",
                        )
                    ) {
                        return false
                    }
                    evidenceKey(
                        runtime,
                        tools,
                        listOf("box64", "wine"),
                    )
                }
                else -> null
            } ?: return false

        val prefKey =
            when (probe) {
                GuestRuntimeProbe.BOX64_SMOKE ->
                    KEY_BOX64
                GuestRuntimeProbe.WINE_SMOKE ->
                    KEY_WINE
                else ->
                    return false
            }

        return prefs.edit()
            .putString(
                prefKey,
                key,
            )
            .commit()
    }

    private fun evidenceKey(
        runtime: InstalledRuntime,
        tools: List<InstalledGuestTool>,
        ids: List<String>,
    ): String? {
        val byId =
            tools.associateBy {
                it.manifest.id
            }

        val selected =
            ids.map { id ->
                byId[id]
                    ?: return null
            }

        val canonical =
            buildString {
                appendLine(
                    runtime.manifest
                        .rootfsSha256
                        .lowercase(),
                )
                appendLine(
                    runtime.manifest.id,
                )
                appendLine(
                    runtime.manifest.version,
                )
                selected.forEach { tool ->
                    append(tool.manifest.id)
                    append('=')
                    append(
                        GuestToolFingerprint.of(
                            tool.manifest,
                        ),
                    )
                    appendLine()
                }
            }

        return Sha256.digest(
            ByteArrayInputStream(
                canonical.toByteArray(
                    StandardCharsets.UTF_8,
                ),
            ),
        ).sha256
    }

    companion object {
        private const val PREFS =
            "runtime-probe-evidence-v1"
        private const val KEY_BOX64 =
            "box64-smoke-key"
        private const val KEY_WINE =
            "wine-smoke-key"
    }
}
