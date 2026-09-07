package dev.pocketpc.core.ui

import android.app.DownloadManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun DownloadsApp() {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Downloads")
        Text(
            "Downloads iniciados pelo Navegador usam o gerenciador seguro " +
                "do Android e podem ser acessados aqui."
        )
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            },
        ) {
            Text("Abrir Downloads")
        }
    }
}
