package dev.pocketpc.core.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.performance.GovernorInput
import dev.pocketpc.core.performance.PerformanceGovernor
import dev.pocketpc.core.telemetry.TelemetrySample
import dev.pocketpc.core.telemetry.thermalHeadroomHint
import dev.pocketpc.core.telemetry.thermalStatusLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceApp(
    sample: TelemetrySample,
) {
    val governor =
        PerformanceGovernor.decide(
            GovernorInput(
                sample.thermalHeadroom,
                sample.lowMemory,
            )
        )

    val ramFraction =
        if (sample.totalRamMb > 0) {
            (
                1f -
                    sample.availableRamMb.toFloat() /
                        sample.totalRamMb.toFloat()
                ).coerceIn(0f, 1f)
        } else {
            0f
        }

    val frameBudget =
        if (sample.uiFps > 0) {
            (1_000f / sample.uiFps)
                .coerceAtLeast(1f)
        } else {
            16.67f
        }
    val framePressure =
        (
            sample.averageFrameMs.toFloat() /
                frameBudget
            ).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement =
            Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Desempenho",
                    style =
                        MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Telemetria do host PocketPC",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "${sample.uiFps} UI FPS",
                        fontSize = 9.sp,
                    )
                },
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (LocalAppViewport.current.widthDp < 500f) 1 else 3,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            PerformanceMeter(
                title = "Memória",
                value =
                    "${(ramFraction * 100f).roundToInt()}%",
                detail =
                    "${sample.availableRamMb} MB livres",
                progress = ramFraction,
                modifier = Modifier.weight(1f),
            )
            PerformanceMeter(
                title = "Frame UI",
                value =
                    String.format(
                        java.util.Locale.ROOT,
                        "%.1f ms",
                        sample.averageFrameMs,
                    ),
                detail =
                    "pior ${String.format(java.util.Locale.ROOT, "%.1f", sample.worstFrameMs)} ms",
                progress = framePressure,
                modifier = Modifier.weight(1f),
            )
            PerformanceMeter(
                title = "Processo",
                value =
                    "${sample.processCpuPercent}% CPU",
                detail =
                    "${sample.processRamMb} MB RAM",
                progress =
                    (
                        sample.processCpuPercent
                            .coerceAtLeast(0)
                            .toFloat() / 100f
                        ).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Térmico e memória",
                    style =
                        MaterialTheme.typography.titleSmall,
                )
                ValueRow(
                    "Thermal status",
                    thermalStatusLabel(
                        sample.thermalStatus
                    ),
                )
                ValueRow(
                    "Headroom 10 s",
                    sample.thermalHeadroom
                        ?.let {
                            String.format(
                                java.util.Locale.ROOT,
                                "%.2f",
                                it,
                            )
                        }
                        ?: "indisponível",
                )
                ValueRow(
                    "Low-memory",
                    if (sample.lowMemory) {
                        "SIM"
                    } else {
                        "não"
                    },
                )
                Text(
                    thermalHeadroomHint(
                        sample.thermalHeadroom
                    ),
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Governor",
                    style =
                        MaterialTheme.typography.titleSmall,
                )
                ValueRow(
                    "Ação sugerida",
                    governor.action.name,
                )
                ValueRow(
                    "Teto de qualidade",
                    "${(governor.suggestedQualityCeiling * 100).toInt()}%",
                )
                Text(
                    governor.reason,
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "As métricas acima medem o processo e a UI do PocketPC. " +
                "Não representam FPS de jogos externos nem uso global da GPU. " +
                "O governor continua apenas consultivo; nenhuma qualidade " +
                "de aplicativo de terceiros é alterada automaticamente.",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
        )
    }
}

@Composable
private fun PerformanceMeter(
    title: String,
    value: String,
    detail: String,
    progress: Float,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 104.dp),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp),
        ) {
            Text(
                title,
                fontSize = 9.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
            Text(
                value,
                style =
                    MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = {
                    progress.coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                detail,
                fontSize = 9.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PerformanceHud(
    sample: TelemetrySample,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 8.dp,
    ) {
        Column(
            Modifier.padding(
                horizontal = 10.dp,
                vertical = 7.dp,
            )
        ) {
            Text(
                "${sample.uiFps} UI FPS",
                fontSize = 12.sp,
            )
            Text(
                "${String.format(java.util.Locale.ROOT, "%.1f", sample.averageFrameMs)} ms",
                fontSize = 9.sp,
            )
            Text(
                "RAM ${sample.processRamMb} MB",
                fontSize = 9.sp,
            )
            sample.thermalHeadroom?.let {
                Text(
                    "TH ${String.format(java.util.Locale.ROOT, "%.2f", it)}",
                    fontSize = 9.sp,
                )
            }
        }
    }
}
