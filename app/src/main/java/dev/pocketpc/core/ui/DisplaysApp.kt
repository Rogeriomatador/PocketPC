package dev.pocketpc.core.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot
import java.util.Locale

@Composable
fun DisplaysApp(
    capabilities: DesktopCapabilitySnapshot,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            ),
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
                    "Telas",
                    style =
                        MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Monitores e capacidades que o Android realmente expõe",
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
                        if (
                            capabilities
                                .externalDisplayCount > 0
                        ) {
                            "${capabilities.externalDisplayCount} externa(s)"
                        } else {
                            "Tela principal"
                        },
                        fontSize = 9.sp,
                    )
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            DisplayCapabilityCard(
                title = "Janela livre",
                value =
                    if (
                        capabilities
                            .freeformWindowManagement
                    ) {
                        "Disponível"
                    } else {
                        "Não anunciada"
                    },
                modifier = Modifier.weight(1f),
            )
            DisplayCapabilityCard(
                title = "Display secundário",
                value =
                    if (
                        capabilities
                            .secondaryDisplayActivities
                    ) {
                        "Disponível"
                    } else {
                        "Não anunciado"
                    },
                modifier = Modifier.weight(1f),
            )
            DisplayCapabilityCard(
                title = "Android PC",
                value =
                    if (capabilities.pcHardwareType) {
                        "Anunciado"
                    } else {
                        "Não anunciado"
                    },
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        Text(
            "Displays detectados",
            style =
                MaterialTheme.typography.titleSmall,
        )

        if (capabilities.externalDisplays.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Nenhum monitor externo separado",
                        style =
                            MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Uma transmissão pode ser apenas espelhamento. " +
                            "O PocketPC só conta um monitor quando o Android " +
                            "o expõe como Display separado.",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            capabilities.externalDisplays
                .forEach { display ->
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth(),
                        shape =
                            RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(12.dp),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Text(
                                "▣",
                                fontSize = 28.sp,
                            )
                            Spacer(
                                Modifier.width(10.dp)
                            )
                            Column(
                                Modifier.weight(1f)
                            ) {
                                Text(
                                    display.name,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .titleSmall,
                                )
                                Text(
                                    "${display.widthPx}×" +
                                        "${display.heightPx} • " +
                                        String.format(
                                            Locale.ROOT,
                                            "%.1f Hz",
                                            display.refreshRateHz,
                                        ),
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                )
                            }
                            Column(
                                horizontalAlignment =
                                    Alignment.End,
                            ) {
                                Text(
                                    "Display #${display.displayId}",
                                    fontSize = 9.sp,
                                )
                                Text(
                                    if (display.presentation) {
                                        "Presentation"
                                    } else {
                                        "Externo"
                                    },
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    openSettingsSafely(
                        context = context,
                        primaryAction =
                            Settings.ACTION_CAST_SETTINGS,
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Transmitir / Wi-Fi Display")
            }

            OutlinedButton(
                onClick = {
                    openSettingsSafely(
                        context = context,
                        primaryAction =
                            Settings.ACTION_BLUETOOTH_SETTINGS,
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("Bluetooth")
            }
        }

        Text(
            if (
                capabilities
                    .preferredExternalDisplayId != null
            ) {
                "Há um display externo elegível. O launcher de Aplicativos " +
                    "pode solicitar ao Android que apps sejam abertos nele."
            } else {
                "Sem display externo elegível agora. O PocketPC continuará " +
                    "usando a tela atual e não marcará suporte externo como PASS."
            },
            style =
                MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DisplayCapabilityCard(
    title: String,
    value: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 70.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp),
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
                    MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private fun openSettingsSafely(
    context: android.content.Context,
    primaryAction: String,
) {
    val primary = Intent(primaryAction)
    val chosen =
        if (
            primary.resolveActivity(
                context.packageManager
            ) != null
        ) {
            primary
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

    chosen.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK
    )
    context.startActivity(chosen)
}
