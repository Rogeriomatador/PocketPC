package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.performance.GovernorInput
import dev.pocketpc.core.performance.PerformanceGovernor
import dev.pocketpc.core.telemetry.TelemetrySample
import dev.pocketpc.core.telemetry.thermalHeadroomHint
import dev.pocketpc.core.telemetry.thermalStatusLabel

@Composable
fun PerformanceApp(sample: TelemetrySample) {
    val governor = PerformanceGovernor.decide(
        GovernorInput(sample.thermalHeadroom, sample.lowMemory)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Desempenho", style = MaterialTheme.typography.titleMedium)
        ValueRow("UI cadence", "${sample.uiFps} FPS")
        ValueRow("Frame médio", "%.2f ms".format(sample.averageFrameMs))
        ValueRow("Pior frame (janela)", "%.2f ms".format(sample.worstFrameMs))
        ValueRow("CPU do processo*", "${sample.processCpuPercent}%")
        ValueRow("RAM do processo", "${sample.processRamMb} MB")
        ValueRow("RAM disponível", "${sample.availableRamMb} / ${sample.totalRamMb} MB")
        ValueRow("Low-memory", if (sample.lowMemory) "SIM" else "não")
        ValueRow("Thermal status", thermalStatusLabel(sample.thermalStatus))
        ValueRow(
            "Headroom 10 s",
            sample.thermalHeadroom?.let { "%.2f".format(it) } ?: "indisponível",
        )
        Text(thermalHeadroomHint(sample.thermalHeadroom), style = MaterialTheme.typography.bodySmall)
        ValueRow("Governor (advisory)", governor.action.name)
        ValueRow("Quality ceiling*", "${(governor.suggestedQualityCeiling * 100).toInt()}%")
        Text(governor.reason, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "*CPU é tempo acumulado do processo e pode passar de 100% em múltiplos núcleos. " +
                "Os FPS/frametimes medem a UI do PocketPC, não jogos de terceiros e não a GPU global. " +
                "O quality ceiling é apenas recomendação na Alpha 3; nenhuma qualidade é alterada automaticamente.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun PerformanceHud(sample: TelemetrySample, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 8.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${sample.uiFps} UI FPS", fontSize = 14.sp)
            Text("${"%.1f".format(sample.averageFrameMs)} ms", fontSize = 10.sp)
            Text("RAM ${sample.processRamMb} MB", fontSize = 10.sp)
            sample.thermalHeadroom?.let { Text("TH ${"%.2f".format(it)}", fontSize = 10.sp) }
        }
    }
}
