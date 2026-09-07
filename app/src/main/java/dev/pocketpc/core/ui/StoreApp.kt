package dev.pocketpc.core.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun StoreApp() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Loja")
        Text(
            "Instale apps e jogos pela loja oficial disponivel no Android."
        )

        Button(
            onClick = {
                val market = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://search?q=apps"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val web = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store"),
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                runCatching {
                    if (market.resolveActivity(context.packageManager) != null) {
                        context.startActivity(market)
                    } else {
                        context.startActivity(web)
                    }
                }.onFailure { error ->
                    status =
                        "Falha ao abrir a loja: " +
                            (error.message ?: error.javaClass.simpleName)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Google Play")
        }

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/games"),
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Explorar jogos")
        }

        status?.let { Text(it) }
    }
}
