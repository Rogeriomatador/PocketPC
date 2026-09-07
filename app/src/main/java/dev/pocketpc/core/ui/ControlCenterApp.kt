package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ControlCenterApp() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Central de Controle",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Acesso rápido aos controles do sistema",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AssistChip(
                onClick = {
                    openSetting(
                        context,
                        Settings.ACTION_SETTINGS,
                    )
                },
                label = { Text("Android", fontSize = 9.sp) },
            )
        }

        HorizontalDivider()

        QuickSettingsRow(
            left = QuickSetting(
                "Wi-Fi",
                "Rede sem fio",
                Settings.ACTION_WIFI_SETTINGS,
            ),
            right = QuickSetting(
                "Bluetooth",
                "Dispositivos",
                Settings.ACTION_BLUETOOTH_SETTINGS,
            ),
            context = context,
        )

        QuickSettingsRow(
            left = QuickSetting(
                "Som",
                "Volume e áudio",
                Settings.ACTION_SOUND_SETTINGS,
            ),
            right = QuickSetting(
                "Tela",
                "Brilho e exibição",
                Settings.ACTION_DISPLAY_SETTINGS,
            ),
            context = context,
        )

        QuickSettingsRow(
            left = QuickSetting(
                "Teclado",
                "Métodos de entrada",
                Settings.ACTION_INPUT_METHOD_SETTINGS,
            ),
            right = QuickSetting(
                "Bateria",
                "Economia de energia",
                Settings.ACTION_BATTERY_SAVER_SETTINGS,
            ),
            context = context,
        )

        QuickSettingsRow(
            left = QuickSetting(
                "Transmitir",
                "Wi-Fi Display",
                Settings.ACTION_CAST_SETTINGS,
            ),
            right = QuickSetting(
                "Notificações",
                "Painel do sistema",
                null,
            ),
            context = context,
        )

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = {
                openSetting(
                    context,
                    Settings.ACTION_SETTINGS,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Todas as configurações")
        }
    }
}

private data class QuickSetting(
    val title: String,
    val subtitle: String,
    val action: String?,
)

@Composable
private fun QuickSettingsRow(
    left: QuickSetting,
    right: QuickSetting,
    context: Context,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickSettingTile(
            item = left,
            modifier = Modifier.weight(1f),
            onClick = {
                if (left.action != null) {
                    openSetting(context, left.action)
                } else {
                    openNotificationsSetting(context)
                }
            },
        )
        QuickSettingTile(
            item = right,
            modifier = Modifier.weight(1f),
            onClick = {
                if (right.action != null) {
                    openSetting(context, right.action)
                } else {
                    openNotificationsSetting(context)
                }
            },
        )
    }
}

@Composable
private fun QuickSettingTile(
    item: QuickSetting,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(
            horizontal = 10.dp,
            vertical = 4.dp,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                item.title,
                fontSize = 11.sp,
                maxLines = 1,
            )
            Text(
                item.subtitle,
                fontSize = 8.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openNotificationsSetting(context: Context) {
    val action =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
        } else {
            Settings.ACTION_SETTINGS
        }

    openSetting(context, action)
}

private fun openSetting(
    context: Context,
    action: String,
) {
    val requested = Intent(action)
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val intent =
        if (
            requested.resolveActivity(
                context.packageManager
            ) != null
        ) {
            requested
        } else {
            fallback
        }

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
