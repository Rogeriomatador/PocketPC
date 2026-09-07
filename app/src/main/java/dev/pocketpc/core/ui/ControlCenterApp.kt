package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ControlCenterApp() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Central de Controle")
        Text(
            "Acesso rapido aos controles do Android usados em modo desktop."
        )

        SettingRow(
            leftLabel = "Wi-Fi",
            leftAction = { openSetting(context, Settings.ACTION_WIFI_SETTINGS) },
            rightLabel = "Bluetooth",
            rightAction = {
                openSetting(context, Settings.ACTION_BLUETOOTH_SETTINGS)
            },
        )
        SettingRow(
            leftLabel = "Som",
            leftAction = { openSetting(context, Settings.ACTION_SOUND_SETTINGS) },
            rightLabel = "Tela",
            rightAction = {
                openSetting(context, Settings.ACTION_DISPLAY_SETTINGS)
            },
        )
        SettingRow(
            leftLabel = "Teclado",
            leftAction = {
                openSetting(context, Settings.ACTION_INPUT_METHOD_SETTINGS)
            },
            rightLabel = "Notificacoes",
            rightAction = {
                openNotificationsSetting(context)
            },
        )
        SettingRow(
            leftLabel = "Bateria",
            leftAction = {
                openSetting(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)
            },
            rightLabel = "Transmitir",
            rightAction = {
                openSetting(context, Settings.ACTION_CAST_SETTINGS)
            },
        )

        OutlinedButton(
            onClick = { openSetting(context, Settings.ACTION_SETTINGS) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Todas as configuracoes do Android")
        }
    }
}

@Composable
private fun SettingRow(
    leftLabel: String,
    leftAction: () -> Unit,
    rightLabel: String,
    rightAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = leftAction,
            modifier = Modifier.weight(1f),
        ) {
            Text(leftLabel)
        }
        Button(
            onClick = rightAction,
            modifier = Modifier.weight(1f),
        ) {
            Text(rightLabel)
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

private fun openSetting(context: Context, action: String) {
    val requested = Intent(action)
    val fallback = Intent(Settings.ACTION_SETTINGS)
    val intent =
        if (requested.resolveActivity(context.packageManager) != null) {
            requested
        } else {
            fallback
        }

    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
