package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import dev.pocketpc.core.storage.PocketTarExtractionReport
import dev.pocketpc.core.storage.PocketTarListing
import dev.pocketpc.core.storage.extractPocketTarToDownloads
import dev.pocketpc.core.storage.inspectPocketTar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_VISIBLE_TAR_ENTRIES = 200

private sealed interface PocketTarPreviewState {
    data object Loading : PocketTarPreviewState
    data class Ready(val listing: PocketTarListing) : PocketTarPreviewState
    data class Failed(val message: String) : PocketTarPreviewState
}

private sealed interface PocketTarExtractState {
    data object Idle : PocketTarExtractState
    data object Extracting : PocketTarExtractState
    data class Done(val report: PocketTarExtractionReport) : PocketTarExtractState
    data class Failed(val message: String) : PocketTarExtractState
}

@Composable
fun PocketTarArchivePane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = request.uri

    var previewState by remember(uri) {
        mutableStateOf<PocketTarPreviewState>(PocketTarPreviewState.Loading)
    }
    var extractState by remember(uri) {
        mutableStateOf<PocketTarExtractState>(PocketTarExtractState.Idle)
    }

    LaunchedEffect(uri) {
        extractState = PocketTarExtractState.Idle
        if (uri == null) {
            previewState =
                PocketTarPreviewState.Failed(
                    "O PocketPC não recebeu acesso ao TAR."
                )
            return@LaunchedEffect
        }

        previewState = PocketTarPreviewState.Loading
        previewState =
            withContext(Dispatchers.IO) {
                inspectPocketTar(context, uri)
                    .fold(
                        onSuccess = PocketTarPreviewState::Ready,
                        onFailure = { error ->
                            PocketTarPreviewState.Failed(
                                "Não foi possível ler o TAR: " +
                                    (error.message ?: error.javaClass.simpleName)
                            )
                        },
                    )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val state = previewState) {
            PocketTarPreviewState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Lendo TAR dentro do PocketPC...")
                }

            is PocketTarPreviewState.Failed ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketTarPreviewState.Ready -> {
                val listing = state.listing
                Text(
                    "${listing.fileCount} arquivo(s) • " +
                        "${listing.directoryCount} pasta(s) • " +
                        formatTarBytes(listing.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listing.entries
                        .take(MAX_VISIBLE_TAR_ENTRIES)
                        .forEach { entry ->
                            Text(
                                text =
                                    (if (entry.directory) "📁 " else "📄 ") +
                                        entry.path +
                                        if (!entry.directory) {
                                            "  (${formatTarBytes(entry.size)})"
                                        } else {
                                            ""
                                        },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                    if (listing.entries.size > MAX_VISIBLE_TAR_ENTRIES) {
                        Text(
                            "+ ${listing.entries.size - MAX_VISIBLE_TAR_ENTRIES} entrada(s) não exibidas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (val extraction = extractState) {
                    PocketTarExtractState.Idle -> Unit
                    PocketTarExtractState.Extracting ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator()
                            Text("Extraindo TAR para P:\\Downloads...")
                        }
                    is PocketTarExtractState.Done ->
                        Text(
                            "Extraído para P:\\Downloads\\${extraction.report.destinationName} • " +
                                "${extraction.report.fileCount} arquivo(s) • " +
                                formatTarBytes(extraction.report.extractedBytes),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    is PocketTarExtractState.Failed ->
                        Text(
                            extraction.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                }

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = extractState !is PocketTarExtractState.Extracting,
                    onClick = {
                        val targetUri = uri ?: return@Button
                        extractState = PocketTarExtractState.Extracting
                        scope.launch {
                            extractPocketTarToDownloads(
                                context = context,
                                uriString = targetUri,
                                archiveFileName = request.plan.fileName,
                            ).onSuccess { report ->
                                extractState = PocketTarExtractState.Done(report)
                            }.onFailure { error ->
                                extractState = PocketTarExtractState.Failed(
                                    "Falha ao extrair TAR: " +
                                        (error.message ?: error.javaClass.simpleName)
                                )
                            }
                        }
                    },
                ) {
                    Text(
                        if (extractState is PocketTarExtractState.Extracting) {
                            "Extraindo..."
                        } else {
                            "Extrair em P:\\Downloads"
                        }
                    )
                }

                Text(
                    "Links e entradas especiais de TAR são recusados pelo PocketPC em vez de serem materializados no PocketDrive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTarBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format("%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
