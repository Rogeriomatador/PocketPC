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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMAGE_PREVIEW_SIDE = 2048
private const val MIN_IMAGE_ZOOM = 0.5f
private const val MAX_IMAGE_ZOOM = 4.0f

private sealed interface PocketImageViewerState {
    data object Loading : PocketImageViewerState
    data class Ready(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) : PocketImageViewerState
    data class Failed(val message: String) : PocketImageViewerState
}

@Composable
fun PocketImageViewerPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val uri = request.uri
    var state by remember(uri) {
        mutableStateOf<PocketImageViewerState>(PocketImageViewerState.Loading)
    }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var rotation by remember(uri) { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        zoom = 1f
        rotation = 0f
        if (uri == null) {
            state = PocketImageViewerState.Failed(
                "O PocketPC não recebeu acesso à imagem."
            )
            return@LaunchedEffect
        }

        state = PocketImageViewerState.Loading
        state =
            withContext(Dispatchers.IO) {
                loadPocketImageViewerBitmap(
                    context = context,
                    uriString = uri,
                )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val current = state) {
            PocketImageViewerState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Carregando imagem dentro do PocketPC...")
                }

            is PocketImageViewerState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketImageViewerState.Ready -> {
                Text(
                    "${current.sourceWidth} × ${current.sourceHeight} • " +
                        "zoom ${(zoom * 100).toInt()}% • rotação ${rotation.toInt()}°",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = "Imagem aberta no PocketPC",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp, max = 520.dp)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                rotationZ = rotation
                            },
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = zoom > MIN_IMAGE_ZOOM,
                        onClick = {
                            zoom = (zoom - 0.25f).coerceAtLeast(MIN_IMAGE_ZOOM)
                        },
                    ) {
                        Text("− Zoom")
                    }
                    Button(
                        enabled = zoom < MAX_IMAGE_ZOOM,
                        onClick = {
                            zoom = (zoom + 0.25f).coerceAtMost(MAX_IMAGE_ZOOM)
                        },
                    ) {
                        Text("+ Zoom")
                    }
                    Button(
                        onClick = {
                            rotation = (rotation + 90f) % 360f
                        },
                    ) {
                        Text("↻ 90°")
                    }
                    Button(
                        enabled = zoom != 1f || rotation != 0f,
                        onClick = {
                            zoom = 1f
                            rotation = 0f
                        },
                    ) {
                        Text("Resetar")
                    }
                }

                Text(
                    "A prévia é limitada a aproximadamente 2048 px por lado para proteger a memória do desktop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun loadPocketImageViewerBitmap(
    context: android.content.Context,
    uriString: String,
): PocketImageViewerState =
    runCatching {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver
            .openInputStream(uri)
            ?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
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
                ?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
                ?: error("Não foi possível decodificar a imagem.")

        PocketImageViewerState.Ready(
            bitmap = bitmap,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
        )
    }.getOrElse { error ->
        PocketImageViewerState.Failed(
            "Não foi possível abrir a imagem dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    }
