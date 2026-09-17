package dev.pocketpc.core.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_WEB_DOCUMENT_BYTES = 2 * 1024 * 1024

private sealed interface PocketWebDocumentState {
    data object Loading : PocketWebDocumentState
    data class Ready(
        val source: String,
        val mimeType: String,
    ) : PocketWebDocumentState
    data class Failed(val message: String) : PocketWebDocumentState
}

@Composable
fun PocketWebDocumentPane(
    request: PocketFileOpenRequest,
    forceSvg: Boolean = false,
) {
    val context = LocalContext.current
    val uri = request.uri
    var state by remember(uri, forceSvg) {
        mutableStateOf<PocketWebDocumentState>(PocketWebDocumentState.Loading)
    }
    var navigationBlocked by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri, forceSvg) {
        navigationBlocked = false
        if (uri == null) {
            state = PocketWebDocumentState.Failed(
                "O PocketPC não recebeu acesso ao documento."
            )
            return@LaunchedEffect
        }

        state = PocketWebDocumentState.Loading
        state =
            withContext(Dispatchers.IO) {
                loadPocketWebDocument(
                    context = context,
                    uriString = uri,
                    mimeType =
                        if (forceSvg) {
                            "image/svg+xml"
                        } else {
                            request.mimeType
                                ?.takeIf { it.isNotBlank() }
                                ?: "text/html"
                        },
                )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val current = state) {
            PocketWebDocumentState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Renderizando dentro do PocketPC...")
                }

            is PocketWebDocumentState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketWebDocumentState.Ready -> {
                AndroidView(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 560.dp),
                    factory = { webContext ->
                        WebView(webContext).apply {
                            settings.javaScriptEnabled = false
                            settings.domStorageEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            settings.loadsImagesAutomatically = true
                            settings.setSupportMultipleWindows(false)
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        navigationBlocked = true
                                        return true
                                    }
                                }
                            loadDataWithBaseURL(
                                null,
                                current.source,
                                current.mimeType,
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    update = { webView ->
                        if (webView.url == null) {
                            webView.loadDataWithBaseURL(
                                null,
                                current.source,
                                current.mimeType,
                                "UTF-8",
                                null,
                            )
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.webViewClient = WebViewClient()
                        webView.destroy()
                    },
                )

                if (navigationBlocked) {
                    Text(
                        "Um link do documento foi bloqueado. Documentos locais não podem abrir destinos externos automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "Modo isolado: JavaScript, rede, acesso a arquivos e navegação externa estão bloqueados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun loadPocketWebDocument(
    context: android.content.Context,
    uriString: String,
    mimeType: String,
): PocketWebDocumentState =
    runCatching {
        val output = ByteArrayOutputStream()
        context.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?.buffered()
            ?.use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    check(total <= MAX_WEB_DOCUMENT_BYTES) {
                        "O documento ultrapassa o limite interno de 2 MiB."
                    }
                    output.write(buffer, 0, count)
                }
            }
            ?: error("Não foi possível ler o documento.")

        PocketWebDocumentState.Ready(
            source =
                output.toByteArray()
                    .toString(Charsets.UTF_8)
                    .removePrefix("\uFEFF"),
            mimeType = mimeType,
        )
    }.getOrElse { error ->
        PocketWebDocumentState.Failed(
            "Não foi possível abrir o documento dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    }
