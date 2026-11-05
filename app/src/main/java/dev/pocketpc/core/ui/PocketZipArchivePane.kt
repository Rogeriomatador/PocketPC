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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import dev.pocketpc.core.storage.PocketZipExtractionReport
import dev.pocketpc.core.storage.PocketZipListing
import dev.pocketpc.core.storage.extractPocketZipToDownloads
import dev.pocketpc.core.storage.inspectPocketZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_VISIBLE_ZIP_ENTRIES = 200

private sealed interface PocketZipPreviewState {
    data object Loading : PocketZipPreviewState
    data class Ready(val listing: PocketZipListing) : PocketZipPreviewState
    data class Failed(val message: String) : PocketZipPreviewState
}

private sealed interface PocketZipExtractState {
    data object Idle : PocketZipExtractState
    data object Extracting : PocketZipExtractState
    data class Done(val report: PocketZipExtractionReport) : PocketZipExtractState
    data class Failed(val message: String) : PocketZipExtractState
}

@Composable
fun PocketZipArchivePane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = request.uri

    var previewState by remember(uri) {
        mutableStateOf<PocketZipPreviewState>(PocketZipPreviewState.Loading)
    }
    var extractState by remember(uri) {
        mutableStateOf<PocketZipExtractState>(PocketZipExtractState.Idle)
    }

    LaunchedEffect(uri) {
        previewState = PocketZipPreviewState.Loading
        extractState = PocketZipExtractState.Idle

        if (uri == null) {
            previewState =
                PocketZipPreviewState.Failed(
                    "O PocketPC não recebeu acesso ao arquivo ZIP."
                )
            return@LaunchedEffect
        }

        previewState =
            withContext(Dispatchers.IO) {
                inspectPocketZip(context, uri)
                    .fold(
                        onSuccess = PocketZipPreviewState::Ready,
                        onFailure = { error ->
                            PocketZipPreviewState.Failed(
                                "Não foi possível ler o ZIP: " +
                                    (error.message
                                        ?: error.javaClass.simpleName)
                            )
                        },
                    )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val state = previewState) {
            PocketZipPreviewState.Loading ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Lendo ZIP dentro do PocketPC...")
                }

            is PocketZipPreviewState.Failed ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketZipPreviewState.Ready -> {
                val listing = state.listing
                Text(
                    buildString {
                        append(listing.fileCount)
                        append(" arquivo(s) • ")
                        append(listing.directoryCount)
                        append(" pasta(s)")
                        if (listing.knownUncompressedBytes > 0L) {
                            append(" • ")
                            append(formatPocketZipBytes(listing.knownUncompressedBytes))
                            append(" conhecido(s)")
                        }
                    },
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
                        .take(MAX_VISIBLE_ZIP_ENTRIES)
                        .forEach { entry ->
                            Text(
                                text =
                                    (if (entry.directory) "📁 " else "📄 ") +
                                        entry.path +
                                        if (
                                            !entry.directory &&
                                            entry.uncompressedSize >= 0L
                                        ) {
                                            "  (${formatPocketZipBytes(entry.uncompressedSize)})"
                                        } else {
                                            ""
                                        },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                    if (listing.entries.size > MAX_VISIBLE_ZIP_ENTRIES) {
                        Text(
                            "+ ${listing.entries.size - MAX_VISIBLE_ZIP_ENTRIES} entrada(s) não exibidas nesta prévia",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when (val extraction = extractState) {
                    PocketZipExtractState.Idle -> Unit
                    PocketZipExtractState.Extracting ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text("Extraindo para P:\\Downloads...")
                        }

                    is PocketZipExtractState.Done ->
                        Text(
                            "Extraído para P:\\Downloads\\${extraction.report.destinationName} • " +
                                "${extraction.report.fileCount} arquivo(s) • " +
                                formatPocketZipBytes(extraction.report.extractedBytes),
                            color = MaterialTheme.colorScheme.primary,
                        )

                    is PocketZipExtractState.Failed ->
                        Text(
                            extraction.message,
                            color = MaterialTheme.colorScheme.error,
                        )
                }

                Button(
                    enabled = extractState !is PocketZipExtractState.Extracting,
                    onClick = {
                        val archiveUri = uri ?: return@Button
                        extractState = PocketZipExtractState.Extracting
                        scope.launch {
                            extractState =
                                extractPocketZipToDownloads(
                                    context = context,
                                    uriString = archiveUri,
                                    archiveFileName = request.plan.fileName,
                                ).fold(
                                    onSuccess = PocketZipExtractState::Done,
                                    onFailure = { error ->
                                        PocketZipExtractState.Failed(
                                            "Falha ao extrair: " +
                                                (error.message
                                                    ?: error.javaClass.simpleName)
                                        )
                                    },
                                )
                        }
                    },
                ) {
                    Text(
                        if (extractState is PocketZipExtractState.Extracting) {
                            "Extraindo..."
                        } else {
                            "Extrair em P:\\Downloads"
                        }
                    )
                }

                Text(
                    "A extração permanece dentro do PocketDrive e não abre o gerenciador de arquivos do Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatPocketZipBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(
                "%.2f GiB",
                bytes / (1024.0 * 1024.0 * 1024.0),
            )

        bytes >= 1024L * 1024L ->
            String.format(
                "%.1f MiB",
                bytes / (1024.0 * 1024.0),
            )

        bytes >= 1024L ->
            String.format("%.1f KiB", bytes / 1024.0)

        else -> "$bytes B"
    }
