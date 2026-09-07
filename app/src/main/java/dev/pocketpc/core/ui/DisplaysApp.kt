package dev.pocketpc.core.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot
import java.util.Locale

@Composable
fun DisplaysApp(capabilities: DesktopCapabilitySnapshot) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Telas e desktop externo")
        Text(
            "PocketPC observa apenas displays que o Android realmente expoe. " +
                "Transmitir a tela nao garante uma sessao desktop separada."
        )

        ValueRow(
            "Activities em display secundario",
            if (capabilities.secondaryDisplayActivities) {
                "SUPPORTED"
            } else {
                "NOT ADVERTISED"
            },
        )
        ValueRow(
            "Displays externos",
            capabilities.externalDisplayCount.toString(),
        )
        ValueRow(
            "Presentation displays",
            capabilities.presentationDisplayCount.toString(),
        )

        capabilities.externalDisplays.forEach { display ->
            HorizontalDivider()
            Text("Display #${display.displayId}: ${display.name}")
            Text(
                "${display.widthPx}x${display.heightPx} • " +
                    String.format(
                        Locale.ROOT,
                        "%.1f Hz",
                        display.refreshRateHz,
                    )
            )
            Text(
                if (display.presentation) {
                    "Presentation display: SIM"
                } else {
                    "Presentation display: NAO"
                }
            )
        }

        HorizontalDivider()

        Button(
            onClick = {
                openSettingsSafely(
                    context = context,
                    primaryAction = Settings.ACTION_CAST_SETTINGS,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Transmitir / Wi-Fi Display")
        }

        OutlinedButton(
            onClick = {
                openSettingsSafely(
                    context = context,
                    primaryAction = Settings.ACTION_BLUETOOTH_SETTINGS,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Bluetooth")
        }

        Text(
            if (capabilities.preferredExternalDisplayId != null) {
                "Um display externo foi detectado. O launcher de Aplicativos " +
                    "pode tentar abrir apps nele quando permitido pelo Android."
            } else {
                "Nenhum display externo foi detectado agora. Em aparelhos sem " +
                    "video USB-C, tente Wi-Fi Display e volte aqui para observar " +
                    "se o sistema criou um display separado."
            }
        )
    }
}

private fun openSettingsSafely(
    context: android.content.Context,
    primaryAction: String,
) {
    val primary = Intent(primaryAction)
    val chosen =
        if (primary.resolveActivity(context.packageManager) != null) {
            primary
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chosen)
}
