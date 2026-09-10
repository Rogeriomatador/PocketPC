package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileHandler
import dev.pocketpc.core.storage.PocketFileOpenRequest
import dev.pocketpc.core.storage.PocketZipCreationReport
import dev.pocketpc.core.storage.createPocketZipFileInDownloads
import kotlinx.coroutines.launch

private sealed interface PocketQuickCompressState {
    data object Idle : PocketQuickCompressState
    data object Compressing : PocketQuickCompressState
    data class Done(val report: PocketZipCreationReport) : PocketQuickCompressState
    data class Failed(val message: String) : PocketQuickCompressState
}

@Composable
fun PocketFileQuickActions(
    request: PocketFileOpenRequest,
) {
    val uri = request.uri ?: return
    if (
        request.plan.association.handler == PocketFileHandler.ARCHIVE_MANAGER ||
        request.plan.association.handler == PocketFileHandler.ANDROID_PACKAGE_INSTALLER
    ) {
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(uri) {
        mutableStateOf<PocketQuickCompressState>(PocketQuickCompressState.Idle)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Button(
            enabled = state !is PocketQuickCompressState.Compressing,
            onClick = {
                state = PocketQuickCompressState.Compressing
                scope.launch {
                    createPocketZipFileInDownloads(
                        context = context,
                        sourceUriString = uri,
                        sourceName = request.plan.fileName,
                    ).onSuccess { report ->
                        state = PocketQuickCompressState.Done(report)
                    }.onFailure { error ->
                        state = PocketQuickCompressState.Failed(
                            "Falha ao compactar: " +
                                (error.message ?: error.javaClass.simpleName)
                        )
                    }
                }
            },
        ) {
            Text(
                if (state is PocketQuickCompressState.Compressing) {
                    "Compactando..."
                } else {
                    "Compactar em P:\\Downloads"
                }
            )
        }

        when (val current = state) {
            PocketQuickCompressState.Idle -> Unit
            PocketQuickCompressState.Compressing ->
                Text(
                    "Criando ZIP dentro do PocketDrive...",
                    style = MaterialTheme.typography.bodySmall,
                )
            is PocketQuickCompressState.Done ->
                Text(
                    "${current.report.fileName} criado em P:\\Downloads • " +
                        "${current.report.fileCount} arquivo(s).",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            is PocketQuickCompressState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    }
}
