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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import java.io.ByteArrayOutputStream

private const val MAX_TEXT_PREVIEW_BYTES = 512 * 1024
private const val MAX_IMAGE_PREVIEW_SIDE = 2048

private sealed interface PocketTextPreviewState {
    data object Loading : PocketTextPreviewState
    data class Ready(
        val text: String,
        val truncated: Boolean,
        val bytesRead: Int,
    ) : PocketTextPreviewState
    data class Failed(val message: String) : PocketTextPreviewState
}

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

    var textPreview by remember(
        currentRequest.uri,
        current.association.handler,
    ) {
        mutableStateOf<PocketTextPreviewState?>(null)
    }
    var imagePreview by remember(
        currentRequest.uri,
        current.association.handler,
    ) {
        mutableStateOf<PocketImagePreviewState?>(null)
    }

    val isTextViewer =
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

    LaunchedEffect(
        currentRequest.uri,
        current.association.handler,
        current.association.readiness,
    ) {
        if (!isTextViewer) {
            textPreview = null
        } else {
            textPreview = PocketTextPreviewState.Loading
            textPreview =
                withContext(Dispatchers.IO) {
                    loadPocketTextPreview(
                        context = context,
                        request = currentRequest,
                    )
                }
        }

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
                    isTextViewer -> "Editor de Texto do PocketPC"
                    isImageViewer -> "Fotos do PocketPC"
                    isZipViewer -> "Compactador do PocketPC"
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
                    isTextViewer ->
                        PocketTextPreview(state = textPreview)

                    isImageViewer ->
                        PocketImagePreview(state = imagePreview)

                    isZipViewer ->
                        PocketZipArchivePane(request = currentRequest)

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
                    if (isTextViewer || isImageViewer || isZipViewer) {
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
private fun PocketTextPreview(
    state: PocketTextPreviewState?,
) {
    when (state) {
        null,
        PocketTextPreviewState.Loading ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Abrindo dentro do PocketPC...")
            }

        is PocketTextPreviewState.Failed ->
            Text(
                state.message,
                color = MaterialTheme.colorScheme.error,
            )

        is PocketTextPreviewState.Ready -> {
            if (state.truncated) {
                Text(
                    "Visualização limitada aos primeiros " +
                        formatTextPreviewBytes(state.bytesRead) +
                        " para manter o desktop responsivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SelectionContainer {
                Text(
                    text = state.text.ifEmpty { "(arquivo vazio)" },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 480.dp)
                            .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
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

private fun loadPocketTextPreview(
    context: android.content.Context,
    request: PocketFileOpenRequest,
): PocketTextPreviewState {
    val uriString =
        request.uri
            ?: return PocketTextPreviewState.Failed(
                "O PocketPC não recebeu acesso ao arquivo."
            )

    return runCatching {
        val output = ByteArrayOutputStream()
        var truncated = false
        var total = 0

        context.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?.buffered()
            ?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue

                    val remaining = MAX_TEXT_PREVIEW_BYTES - total
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }

                    val accepted = count.coerceAtMost(remaining)
                    output.write(buffer, 0, accepted)
                    total += accepted

                    if (accepted < count) {
                        truncated = true
                        break
                    }
                }
            }
            ?: error("Não foi possível abrir o conteúdo do arquivo.")

        PocketTextPreviewState.Ready(
            text =
                output.toByteArray()
                    .toString(Charsets.UTF_8)
                    .removePrefix("\uFEFF"),
            truncated = truncated || request.sizeBytes > MAX_TEXT_PREVIEW_BYTES,
            bytesRead = total,
        )
    }.getOrElse { error ->
        PocketTextPreviewState.Failed(
            "Não foi possível ler o arquivo dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
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

private fun formatTextPreviewBytes(
    bytes: Int,
): String =
    when {
        bytes >= 1024 * 1024 ->
            String.format(
                "%.1f MiB",
                bytes / (1024.0 * 1024.0),
            )

        bytes >= 1024 ->
            String.format(
                "%.1f KiB",
                bytes / 1024.0,
            )

        else -> "$bytes B"
    }
