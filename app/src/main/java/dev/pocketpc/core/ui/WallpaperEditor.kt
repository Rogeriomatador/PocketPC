package dev.pocketpc.core.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WallpaperEditorDialog(
    uri: String,
    initialTransform: WallpaperTransform,
    onDismiss: () -> Unit,
    onConfirm: (WallpaperTransform) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    var transform by remember(
        uri,
        initialTransform,
    ) {
        mutableStateOf(
            initialTransform.sanitized()
        )
    }

    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = uri,
    ) {
        value =
            withContext(Dispatchers.IO) {
                decodeWallpaperBitmap(
                    context = context,
                    uri = Uri.parse(uri),
                )
            }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 920.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 12.dp,
            shadowElevation = 18.dp,
        ) {
            BoxWithConstraints(
                modifier = Modifier.padding(16.dp),
            ) {
                val screenAspect =
                    (
                        configuration.screenWidthDp
                            .toFloat() /
                            configuration.screenHeightDp
                                .coerceAtLeast(1)
                                .toFloat()
                    ).coerceIn(0.35f, 3f)
                val availablePreviewHeight =
                    maxHeight * 0.48f
                val widthFromHeight =
                    availablePreviewHeight *
                        screenAspect
                val previewWidth =
                    minOf(
                        maxWidth,
                        widthFromHeight,
                    ).coerceAtLeast(180.dp)
                val previewHeight =
                    (previewWidth / screenAspect)
                        .coerceAtLeast(120.dp)

                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Ajustar papel de parede",
                        style =
                            MaterialTheme.typography
                                .titleLarge,
                    )
                    Text(
                        "A prévia usa a proporção atual da tela. " +
                            "Arraste a imagem, ajuste o zoom e escolha " +
                            "como ela deve ocupar o desktop.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )

                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .width(previewWidth)
                                .height(previewHeight)
                                .clip(
                                    RoundedCornerShape(18.dp)
                                )
                                .clipToBounds()
                                .background(
                                    Color.Black.copy(
                                        alpha = 0.42f
                                    )
                                )
                                .pointerInput(
                                    transform.zoom,
                                    transform.fitMode,
                                ) {
                                    detectDragGestures {
                                            change,
                                            dragAmount ->
                                            change.consume()

                                            val extraX =
                                                size.width *
                                                    (
                                                        transform
                                                            .zoom -
                                                            1f
                                                    ) /
                                                    2f
                                            val extraY =
                                                size.height *
                                                    (
                                                        transform
                                                            .zoom -
                                                            1f
                                                    ) /
                                                    2f

                                            transform =
                                                transform.copy(
                                                    offsetX =
                                                        if (
                                                            extraX >
                                                            0.5f
                                                        ) {
                                                            (
                                                                transform
                                                                    .offsetX +
                                                                    dragAmount
                                                                        .x /
                                                                        extraX
                                                                )
                                                                .coerceIn(
                                                                    -1f,
                                                                    1f,
                                                                )
                                                        } else {
                                                            0f
                                                        },
                                                    offsetY =
                                                        if (
                                                            extraY >
                                                            0.5f
                                                        ) {
                                                            (
                                                                transform
                                                                    .offsetY +
                                                                    dragAmount
                                                                        .y /
                                                                        extraY
                                                                )
                                                                .coerceIn(
                                                                    -1f,
                                                                    1f,
                                                                )
                                                        } else {
                                                            0f
                                                        },
                                                )
                                        }
                                },
                        ) {
                            val safe =
                                transform.sanitized()
                            val widthPx =
                                with(density) {
                                    maxWidth.toPx()
                                }
                            val heightPx =
                                with(density) {
                                    maxHeight.toPx()
                                }
                            val extraX =
                                widthPx *
                                    (safe.zoom - 1f) /
                                    2f
                            val extraY =
                                heightPx *
                                    (safe.zoom - 1f) /
                                    2f

                            if (bitmap == null) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier.align(
                                            Alignment.Center
                                        )
                                )
                            } else {
                                Image(
                                    bitmap =
                                        requireNotNull(bitmap),
                                    contentDescription =
                                        "Prévia do papel de parede",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = safe.zoom
                                            scaleY = safe.zoom
                                            translationX =
                                                safe.offsetX *
                                                    extraX
                                            translationY =
                                                safe.offsetY *
                                                    extraY
                                        },
                                    contentScale =
                                        if (
                                            safe.fitMode ==
                                            WallpaperFitMode.FIT
                                        ) {
                                            ContentScale.Fit
                                        } else {
                                            ContentScale.Crop
                                        },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        WallpaperModeButton(
                            label = "Preencher",
                            selected =
                                transform.fitMode ==
                                    WallpaperFitMode.CROP,
                            onClick = {
                                transform =
                                    transform.copy(
                                        fitMode =
                                            WallpaperFitMode
                                                .CROP
                                    )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        WallpaperModeButton(
                            label = "Encaixar",
                            selected =
                                transform.fitMode ==
                                    WallpaperFitMode.FIT,
                            onClick = {
                                transform =
                                    transform.copy(
                                        fitMode =
                                            WallpaperFitMode
                                                .FIT
                                    )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        "Zoom " +
                            String.format(
                                "%.2f×",
                                transform.zoom,
                            ),
                        fontSize = 11.sp,
                    )
                    Slider(
                        value = transform.zoom,
                        onValueChange = {
                            transform =
                                transform.copy(
                                    zoom =
                                        it.coerceIn(
                                            1f,
                                            3f,
                                        )
                                )
                        },
                        valueRange = 1f..3f,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                transform =
                                    transform.copy(
                                        zoom = 1f,
                                        offsetX = 0f,
                                        offsetY = 0f,
                                    )
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Centralizar")
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancelar")
                        }
                        Button(
                            onClick = {
                                onConfirm(
                                    transform.sanitized()
                                )
                            },
                            enabled = bitmap != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Aplicar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
        ) {
            Text(label)
        }
    }
}
