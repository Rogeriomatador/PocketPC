package dev.pocketpc.core.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private const val MAX_PDF_RENDER_SIDE = 1600

private sealed interface PocketPdfPageState {
    data object Loading : PocketPdfPageState
    data class Ready(
        val bitmap: Bitmap,
        val pageIndex: Int,
        val pageCount: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    ) : PocketPdfPageState
    data class Failed(val message: String) : PocketPdfPageState
}

@Composable
fun PocketPdfViewerPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val uri = request.uri
    var requestedPage by remember(uri) {
        mutableIntStateOf(0)
    }
    var pageState by remember(uri) {
        mutableStateOf<PocketPdfPageState>(PocketPdfPageState.Loading)
    }

    LaunchedEffect(uri, requestedPage) {
        if (uri == null) {
            pageState =
                PocketPdfPageState.Failed(
                    "O PocketPC não recebeu acesso ao PDF."
                )
            return@LaunchedEffect
        }

        pageState = PocketPdfPageState.Loading
        pageState =
            withContext(Dispatchers.IO) {
                renderPocketPdfPage(
                    context = context,
                    uriString = uri,
                    requestedPage = requestedPage,
                )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val state = pageState) {
            PocketPdfPageState.Loading ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Renderizando PDF dentro do PocketPC...")
                }

            is PocketPdfPageState.Failed ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketPdfPageState.Ready -> {
                Text(
                    "Página ${state.pageIndex + 1} de ${state.pageCount} • " +
                        "${state.sourceWidth} × ${state.sourceHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription =
                        "Página ${state.pageIndex + 1} do PDF aberta no PocketPC",
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 560.dp),
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        enabled = state.pageIndex > 0,
                        onClick = {
                            requestedPage =
                                (state.pageIndex - 1).coerceAtLeast(0)
                        },
                    ) {
                        Text("← Anterior")
                    }

                    Button(
                        enabled = state.pageIndex + 1 < state.pageCount,
                        onClick = {
                            requestedPage =
                                (state.pageIndex + 1)
                                    .coerceAtMost(state.pageCount - 1)
                        },
                    ) {
                        Text("Próxima →")
                    }
                }

                Text(
                    "O documento é renderizado pelo leitor interno do PocketPC; nenhum aplicativo Android externo é aberto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun renderPocketPdfPage(
    context: android.content.Context,
    uriString: String,
    requestedPage: Int,
): PocketPdfPageState =
    runCatching {
        context.contentResolver
            .openFileDescriptor(Uri.parse(uriString), "r")
            ?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    check(renderer.pageCount > 0) {
                        "O PDF não possui páginas renderizáveis."
                    }
                    val pageIndex =
                        requestedPage.coerceIn(
                            0,
                            renderer.pageCount - 1,
                        )

                    renderer.openPage(pageIndex).use { page ->
                        val sourceWidth = max(page.width, 1)
                        val sourceHeight = max(page.height, 1)
                        val scale =
                            min(
                                1.0,
                                MAX_PDF_RENDER_SIDE.toDouble() /
                                    max(sourceWidth, sourceHeight).toDouble(),
                            )
                        val renderWidth =
                            max(1, (sourceWidth * scale).toInt())
                        val renderHeight =
                            max(1, (sourceHeight * scale).toInt())
                        val bitmap =
                            Bitmap.createBitmap(
                                renderWidth,
                                renderHeight,
                                Bitmap.Config.ARGB_8888,
                            )

                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )

                        PocketPdfPageState.Ready(
                            bitmap = bitmap,
                            pageIndex = pageIndex,
                            pageCount = renderer.pageCount,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                        )
                    }
                }
            }
            ?: error("Não foi possível abrir o descritor do PDF.")
    }.getOrElse { error ->
        PocketPdfPageState.Failed(
            "Não foi possível abrir o PDF dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    }
