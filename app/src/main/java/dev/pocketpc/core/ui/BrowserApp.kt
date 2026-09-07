package dev.pocketpc.core.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val POCKETPC_HOME = "https://www.google.com/"

class BrowserSessionState {
    var url by mutableStateOf(POCKETPC_HOME)
    var desktopMode by mutableStateOf(false)
}

@Composable
fun BrowserApp(session: BrowserSessionState) {
    val context = LocalContext.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(session.url) }
    var pageTitle by remember { mutableStateOf("Google") }
    var progress by remember { mutableFloatStateOf(0f) }
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }

    fun navigate(raw: String) {
        val target = browserTarget(raw)
        address = target
        session.url = target
        webView?.loadUrl(target)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = { webView?.takeIf { it.canGoBack() }?.goBack() },
            ) {
                Text("←")
            }
            OutlinedButton(
                onClick = { webView?.takeIf { it.canGoForward() }?.goForward() },
            ) {
                Text("→")
            }
            OutlinedButton(onClick = { webView?.reload() }) {
                Text("↻")
            }
            OutlinedButton(onClick = { navigate(POCKETPC_HOME) }) {
                Text("⌂")
            }
            OutlinedButton(
                onClick = {
                    val next = !session.desktopMode
                    session.desktopMode = next
                    webView?.let { view ->
                        val base = mobileUserAgent ?: view.settings.userAgentString.orEmpty()
                        view.settings.userAgentString =
                            if (next) desktopUserAgent(base) else base
                        view.settings.useWideViewPort = next
                        view.settings.loadWithOverviewMode = next
                        view.reload()
                    }
                },
            ) {
                Text(if (session.desktopMode) "Desktop ✓" else "Desktop")
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
            ) {
                Text("Downloads")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Endereço ou pesquisa") },
            )
            Button(onClick = { navigate(address) }) {
                Text("Ir")
            }
            OutlinedButton(
                onClick = {
                    val url = webView?.url ?: address
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                },
            ) {
                Text("↗")
            }
        }

        Text(
            text = pageTitle.ifBlank { "PocketPC Browser" },
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (progress in 0.001f..0.999f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 220.dp),
            factory = { activityContext ->
                WebView(activityContext).apply {
                    webView = this
                    mobileUserAgent = settings.userAgentString
                    if (session.desktopMode) {
                        settings.userAgentString = desktopUserAgent(settings.userAgentString.orEmpty())
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.safeBrowsingEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme.orEmpty().lowercase()
                            if (scheme == "http" || scheme == "https") {
                                return false
                            }

                            return runCatching {
                                activityContext.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri)
                                )
                                true
                            }.getOrDefault(true)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!url.isNullOrBlank()) {
                                address = url
                                session.url = url
                            }
                            pageTitle = view?.title.orEmpty()
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100) / 100f
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) {
                                pageTitle = title
                            }
                        }
                    }

                    setDownloadListener(
                        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            enqueueDownload(
                                context = activityContext,
                                url = url,
                                userAgent = userAgent,
                                contentDisposition = contentDisposition,
                                mimeType = mimeType,
                            )
                        }
                    )

                    loadUrl(session.url)
                }
            },
            update = { view ->
                webView = view
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = null
                destroy()
            }
            webView = null
        }
    }
}

internal fun browserTarget(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return POCKETPC_HOME

    if (
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true)
    ) {
        return value
    }

    if (!value.contains(' ') && value.contains('.')) {
        return "https://$value"
    }

    val query = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    return "https://www.google.com/search?q=$query"
}

internal fun desktopUserAgent(base: String): String =
    base
        .replace("; wv", "")
        .replace(" Mobile ", " ")
        .replace(" Mobile Safari", " Safari")

private fun enqueueDownload(
    context: Context,
    url: String?,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, "Download sem URL.", Toast.LENGTH_SHORT).show()
        return
    }

    runCatching {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Download pelo PocketPC")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        if (!mimeType.isNullOrBlank()) {
            request.setMimeType(mimeType)
        }
        if (!userAgent.isNullOrBlank()) {
            request.addRequestHeader("User-Agent", userAgent)
        }
        CookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?.let { request.addRequestHeader("Cookie", it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        } else {
            request.setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName,
            )
        }

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(
            context,
            "Download iniciado: $fileName",
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure { error ->
        Toast.makeText(
            context,
            "Falha no download: " + (error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}
