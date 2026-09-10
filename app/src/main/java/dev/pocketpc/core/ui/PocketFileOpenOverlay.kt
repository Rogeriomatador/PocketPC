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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileHandler
import dev.pocketpc.core.storage.PocketFileHandlerReadiness
import dev.pocketpc.core.storage.PocketFileOpenCapability
import dev.pocketpc.core.storage.PocketFileOpenCoordinator

private val POCKETPC_VIDEO_EXTENSIONS =
    setOf("mp4", "mkv", "webm", "avi", "mov")

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
    val extension =
        current.fileName
            .substringAfterLast('.', "")
            .lowercase()

    var editorDirty by remember(currentRequest.uri) {
        mutableStateOf(false)
    }
    var discardRequested by remember(currentRequest.uri) {
        mutableStateOf(false)
    }

    val implemented =
        current.association.readiness ==
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL &&
            currentRequest.uri != null

    val isTextEditor =
        current.association.handler == PocketFileHandler.TEXT_EDITOR && implemented
    val isSvgViewer =
        current.association.handler == PocketFileHandler.IMAGE_VIEWER &&
            implemented &&
            extension == "svg"
    val isImageViewer =
        current.association.handler == PocketFileHandler.IMAGE_VIEWER &&
            implemented &&
            !isSvgViewer
    val isZipViewer =
        current.association.handler == PocketFileHandler.ARCHIVE_MANAGER && implemented
    val isPdfViewer =
        current.association.handler == PocketFileHandler.PDF_VIEWER && implemented
    val isOfficePreview =
        current.association.handler == PocketFileHandler.OFFICE_VIEWER && implemented
    val isWebDocument =
        current.association.handler == PocketFileHandler.WEB_DOCUMENT && implemented
    val isVideoPlayer =
        current.association.handler == PocketFileHandler.MEDIA_PLAYER &&
            implemented &&
            (
                extension in POCKETPC_VIDEO_EXTENSIONS ||
                    currentRequest.mimeType
                        ?.startsWith("video/", ignoreCase = true) == true
            )
    val isAudioPlayer =
        current.association.handler == PocketFileHandler.MEDIA_PLAYER &&
            implemented &&
            !isVideoPlayer

    fun requestClose() {
        if (isTextEditor && editorDirty) {
            discardRequested = true
        } else {
            PocketFileOpenCoordinator.dismiss()
        }
    }

    AlertDialog(
        onDismissRequest = ::requestClose,
        title = {
            Text(
                when {
                    isTextEditor ->
                        if (editorDirty) {
                            "Editor de Texto do PocketPC • Modificado"
                        } else {
                            "Editor de Texto do PocketPC"
                        }
                    isSvgViewer || isImageViewer -> "Fotos do PocketPC"
                    isZipViewer -> "Compactador do PocketPC"
                    isPdfViewer -> "Leitor de PDF do PocketPC"
                    isOfficePreview -> "Documentos do PocketPC"
                    isWebDocument -> "Documento Web do PocketPC"
                    isVideoPlayer || isAudioPlayer -> "Mídia do PocketPC"
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
                        PocketTextEditorPane(
                            request = currentRequest,
                            onDirtyChange = { dirty ->
                                editorDirty = dirty
                                if (!dirty) {
                                    discardRequested = false
                                }
                            },
                        )

                    isSvgViewer ->
                        PocketWebDocumentPane(
                            request = currentRequest,
                            forceSvg = true,
                        )

                    isImageViewer ->
                        PocketImageViewerPane(request = currentRequest)

                    isZipViewer ->
                        PocketZipArchivePane(request = currentRequest)

                    isPdfViewer ->
                        PocketPdfViewerPane(request = currentRequest)

                    isOfficePreview ->
                        PocketOfficePreviewPane(request = currentRequest)

                    isWebDocument ->
                        PocketWebDocumentPane(request = currentRequest)

                    isVideoPlayer ->
                        PocketVideoPlayerPane(request = currentRequest)

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
                                                if (implemented) {
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

                if (!editorDirty) {
                    PocketFileQuickActions(
                        request = currentRequest,
                    )
                } else {
                    Text(
                        "Salve ou reverta as alterações antes de executar outras ações neste arquivo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::requestClose) {
                Text(
                    if (
                        isTextEditor ||
                        isSvgViewer ||
                        isImageViewer ||
                        isZipViewer ||
                        isPdfViewer ||
                        isOfficePreview ||
                        isWebDocument ||
                        isVideoPlayer ||
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

    if (discardRequested) {
        AlertDialog(
            onDismissRequest = {
                discardRequested = false
            },
            title = {
                Text("Descartar alterações?")
            },
            text = {
                Text(
                    "${current.fileName} possui alterações não salvas. " +
                        "Fechar agora descarta apenas essas alterações em memória."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editorDirty = false
                        discardRequested = false
                        PocketFileOpenCoordinator.dismiss()
                    },
                ) {
                    Text("Descartar")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        discardRequested = false
                    },
                ) {
                    Text("Continuar editando")
                }
            },
        )
    }
}
