package dev.pocketpc.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-PocketPC remote workspace for web gateways such as noVNC and Guacamole.
 * The remote PC runs Roblox Studio; the Android device only receives its desktop.
 */
@Composable
fun RemoteDesktopApp() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            "pocketpc-remote-desktop", Context.MODE_PRIVATE,
        )
    }
    var address by rememberSaveable {
        mutableStateOf(preferences.getString("gateway_url", "").orEmpty())
    }
    var sessionUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var reloadVersion by rememberSaveable { mutableIntStateOf(0) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PC remoto", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Acesse um desktop Windows pelo navegador remoto (noVNC/Guacamole).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sessionUrl != null) {
                TextButton(onClick = { sessionUrl = null; status = "Sessão encerrada neste aparelho." }) {
                    Text("Desconectar")
                }
            }
        }

        if (sessionUrl == null) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Endereço HTTPS do desktop remoto") },
                placeholder = { Text("https://pc.exemplo.com/vnc.html") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Button(
                onClick = {
                    val endpoint = RemoteDesktopEndpoint.fromUserInput(address)
                    if (endpoint == null) {
                        status = "Informe uma URL HTTPS válida, sem senha ou token no endereço. " +
                            "HTTP é aceito somente em localhost."
                    } else {
                        preferences.edit().putString("gateway_url", endpoint.url).apply()
                        address = endpoint.url
                        status = "Carregando o acesso remoto..."
                        sessionUrl = endpoint.url
                    }
                },
                enabled = address.isNotBlank(),
            ) {
                Text("Abrir desktop remoto")
            }
            OutlinedButton(
                onClick = {
                    preferences.edit().remove("gateway_url").apply()
                    address = ""
                    status = null
                },
                enabled = address.isNotBlank(),
            ) {
                Text("Esquecer endereço")
            }
            Text(
                "O PC deve estar ligado, com Roblox Studio instalado e um gateway web remoto " +
                    "configurado. O PocketPC não instala nem executa o Studio no Android. " +
                    "Use HTTPS com certificado confiável; não exponha o servidor VNC diretamente à internet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { reloadVersion++ }) { Text("Recarregar") }
                Text(
                    "Teclado/mouse Bluetooth ou USB são recomendados.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val target = requireNotNull(sessionUrl)
            key(target, reloadVersion) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { webContext ->
                        WebView(webContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    if (request?.isForMainFrame != true) return false
                                    val allowed = RemoteDesktopEndpoint.isSafeNavigation(
                                        request.url.toString(),
                                    )
                                    if (!allowed) status = "Navegação insegura bloqueada."
                                    return !allowed
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    status = "Carregando interface remota..."
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    status = "Página carregada. A conexão com o Windows depende do servidor remoto."
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        status = "Falha ao carregar o gateway remoto. Verifique endereço, rede e servidor."
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    handler?.cancel()
                                    status = "Certificado HTTPS inválido. Conexão cancelada."
                                }
                            }
                            loadUrl(target)
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.webViewClient = WebViewClient()
                        webView.webChromeClient = WebChromeClient()
                        webView.destroy()
                    },
                )
            }
        }

        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
