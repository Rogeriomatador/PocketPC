package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import dev.pocketpc.core.storage.PocketGzipExtractionReport
import dev.pocketpc.core.storage.extractPocketGzipToDownloads
import kotlinx.coroutines.launch

private sealed interface PocketGzipUiState {
    data object Idle : PocketGzipUiState
    data object Extracting : PocketGzipUiState
    data class Done(val report: PocketGzipExtractionReport) : PocketGzipUiState
    data class Failed(val message: String) : PocketGzipUiState
}

@Composable
fun PocketGzipArchivePane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = request.uri
    var state by remember(uri) {
        mutableStateOf<PocketGzipUiState>(PocketGzipUiState.Idle)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "GZIP de arquivo único",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            if (request.plan.fileName.endsWith(".tar.gz", ignoreCase = true) ||
                request.plan.fileName.endsWith(".tgz", ignoreCase = true)
            ) {
                "O PocketPC vai expandir primeiro o GZIP para um arquivo TAR em P:\\Downloads. Depois o TAR poderá ser aberto pelo compactador interno."
            } else {
                "O PocketPC vai expandir este GZIP diretamente para P:\\Downloads, sem abrir um gerenciador de arquivos Android."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val current = state) {
            PocketGzipUiState.Idle -> Unit
            PocketGzipUiState.Extracting ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Expandindo GZIP dentro do PocketPC...")
                }
            is PocketGzipUiState.Done ->
                Text(
                    "${current.report.fileName} criado em P:\\Downloads • " +
                        formatGzipBytes(current.report.extractedBytes),
                    color = MaterialTheme.colorScheme.primary,
                )
            is PocketGzipUiState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled =
                uri != null &&
                    state !is PocketGzipUiState.Extracting,
            onClick = {
                val targetUri = uri ?: return@Button
                state = PocketGzipUiState.Extracting
                scope.launch {
                    extractPocketGzipToDownloads(
                        context = context,
                        uriString = targetUri,
                        archiveFileName = request.plan.fileName,
                    ).onSuccess { report ->
                        state = PocketGzipUiState.Done(report)
                    }.onFailure { error ->
                        state = PocketGzipUiState.Failed(
                            "Falha ao expandir GZIP: " +
                                (error.message ?: error.javaClass.simpleName)
                        )
                    }
                }
            },
        ) {
            Text(
                if (state is PocketGzipUiState.Extracting) {
                    "Expandindo..."
                } else {
                    "Extrair em P:\\Downloads"
                }
            )
        }

        Text(
            "Limite atual: até 1 GiB de conteúdo expandido. A saída parcial é removida se a operação falhar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatGzipBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format("%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
