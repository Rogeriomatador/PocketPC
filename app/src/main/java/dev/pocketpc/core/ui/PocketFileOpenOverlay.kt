package dev.pocketpc.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileHandler
import dev.pocketpc.core.storage.PocketFileHandlerReadiness
import dev.pocketpc.core.storage.PocketFileOpenCapability
import dev.pocketpc.core.storage.PocketFileOpenCoordinator
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_PREVIEW_SIDE = 2048

private sealed interface PocketImagePreviewState {
    data object Loading : PocketImagePreviewState
    data class Ready(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) : PocketImagePreviewState
    data class Failed(val message: String) : PocketImagePreviewState
}

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
    val context = LocalContext.current

    var imagePreview by remember(
        currentRequest.uri,
        current.association.handler,
    ) {
        mutableStateOf<PocketImagePreviewState?>(null)
    }

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

    LaunchedEffect(
        currentRequest.uri,
        current.association.handler,
        current.association.readiness,
    ) {
        if (!isImageViewer) {
            imagePreview = null
        } else {
            imagePreview = PocketImagePreviewState.Loading
            imagePreview =
                withContext(Dispatchers.IO) {
                    loadPocketImagePreview(
                        context = context,
                        request = currentRequest,
                    )
                }
        }
    }

    AlertDialog(
        onDismissRequest = PocketFileOpenCoordinator::dismiss,
        title = {
            Text(
                when {
                    isTextEditor -> "Editor de Texto do PocketPC"
                    isImageViewer -> "Fotos do PocketPC"
                    isZipViewer -> "Compactador do PocketPC"
                    isPdfViewer -> "Leitor de PDF do PocketPC"
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
                        PocketImagePreview(state = imagePreview)

                    isZipViewer ->
                        PocketZipArchivePane(request = currentRequest)

                    isPdfViewer ->
                        PocketPdfViewerPane(request = currentRequest)

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
            }
        },
        confirmButton = {
            TextButton(onClick = PocketFileOpenCoordinator::dismiss) {
                Text(
                    if (
                        isTextEditor ||
                        isImageViewer ||
                        isZipViewer ||
                        isPdfViewer
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

@Composable
private fun PocketImagePreview(
    state: PocketImagePreviewState?,
) {
    when (state) {
        null,
        PocketImagePreviewState.Loading ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Carregando imagem dentro do PocketPC...")
            }

        is PocketImagePreviewState.Failed ->
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
            )

        is PocketImagePreviewState.Ready -> {
            Text(
                "${state.sourceWidth} × ${state.sourceHeight}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = "Imagem aberta no PocketPC",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 520.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun loadPocketImagePreview(
    context: android.content.Context,
    request: PocketFileOpenRequest,
): PocketImagePreviewState {
    val uriString =
        request.uri
            ?: return PocketImagePreviewState.Failed(
                "O PocketPC não recebeu acesso à imagem."
            )
    val uri = Uri.parse(uriString)

    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver
            .openInputStream(uri)
            ?.use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            ?: error("Não foi possível ler a imagem.")

        check(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Formato de imagem não reconhecido pelo visualizador interno."
        }

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_IMAGE_PREVIEW_SIDE ||
            bounds.outHeight / sampleSize > MAX_IMAGE_PREVIEW_SIDE
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap =
            context.contentResolver
                .openInputStream(uri)
                ?.use { input -> BitmapFactory.decodeStream(input, null, options) }
                ?: error("Não foi possível decodificar a imagem.")

        PocketImagePreviewState.Ready(
            bitmap = bitmap,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
        )
    }.getOrElse { error ->
        PocketImagePreviewState.Failed(
            "Não foi possível abrir a imagem dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    }
}
