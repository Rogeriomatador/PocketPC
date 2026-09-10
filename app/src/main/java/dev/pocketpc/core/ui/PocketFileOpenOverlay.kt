package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileHandler
import dev.pocketpc.core.storage.PocketFileHandlerReadiness
import dev.pocketpc.core.storage.PocketFileOpenCapability
import dev.pocketpc.core.storage.PocketFileOpenCoordinator

/**
 * Desktop-owned replacement for Android's generic "Abrir com" chooser.
 *
 * Implemented internal handlers are rendered directly here. Route-only file
 * associations remain inside PocketPC and are never delegated to a generic
 * Android ACTION_VIEW fallback.
 */
@Composable
fun PocketFileOpenOverlay() {
    val request by
        PocketFileOpenCoordinator
            .request
            .collectAsState()
    val currentRequest = request ?: return
    val current = currentRequest.plan

    val isTextEditor =
        current.association.handler == PocketFileHandler.TEXT_EDITOR &&
            current.association.readiness == PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null
    val isImageViewer =
        current.association.handler == PocketFileHandler.IMAGE_VIEWER &&
            current.association.readiness == PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null
    val isZipViewer =
        current.association.handler == PocketFileHandler.ARCHIVE_MANAGER &&
            current.association.readiness == PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null
    val isPdfViewer =
        current.association.handler == PocketFileHandler.PDF_VIEWER &&
            current.association.readiness == PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null
    val isAudioPlayer =
        current.association.handler == PocketFileHandler.MEDIA_PLAYER &&
            current.association.readiness == PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null

    AlertDialog(
        onDismissRequest = PocketFileOpenCoordinator::dismiss,
        title = {
            Text(
                when {
                    isTextEditor -> "Editor de Texto do PocketPC"
                    isImageViewer -> "Fotos do PocketPC"
                    isZipViewer -> "Compactador do PocketPC"
                    isPdfViewer -> "Leitor de PDF do PocketPC"
                    isAudioPlayer -> "Mídia do PocketPC"
                    else -> current.title
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    current.fileName,
                    style = MaterialTheme.typography.titleSmall,
                )

                when {
                    isTextEditor ->
                        PocketTextEditorPane(request = currentRequest)

                    isImageViewer ->
                        PocketImageViewerPane(request = currentRequest)

                    isZipViewer ->
                        PocketZipArchivePane(request = currentRequest)

                    isPdfViewer ->
                        PocketPdfViewerPane(request = currentRequest)

                    isAudioPlayer ->
                        PocketAudioPlayerPane(request = currentRequest)

                    else -> {
                        Text(current.description)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text(current.association.displayName) },
                            )
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        when (current.capability) {
                                            PocketFileOpenCapability.WINDOWS_RUNTIME_REQUIRED ->
                                                "Runtime necessário"
                                            PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED ->
                                                "Android necessário"
                                            PocketFileOpenCapability.INTERNAL_HANDLER_PENDING ->
                                                if (
                                                    current.association.readiness ==
                                                        PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL
                                                ) {
                                                    "Disponível no PocketPC"
                                                } else {
                                                    "Em preparação"
                                                }
                                            PocketFileOpenCapability.UNSUPPORTED ->
                                                "Sem associação"
                                        }
                                    )
                                },
                            )
                        }

                        if (
                            current.association.readiness ==
                                PocketFileHandlerReadiness.ROUTE_ONLY
                        ) {
                            Text(
                                "O arquivo continua dentro do PocketPC. " +
                                    "Esta associação não é evidência de que " +
                                    "o aplicativo já esteja funcional.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }

                        if (current.leavesPocketPc) {
                            Text(
                                "Esta ação cruza uma fronteira do Android " +
                                    "e só deve ocorrer após uma ação explícita.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                PocketFileQuickActions(
                    request = currentRequest,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = PocketFileOpenCoordinator::dismiss) {
                Text(
                    if (
                        isTextEditor ||
                        isImageViewer ||
                        isZipViewer ||
                        isPdfViewer ||
                        isAudioPlayer
                    ) {
                        "Fechar"
                    } else {
                        "Entendi"
                    }
                )
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}
