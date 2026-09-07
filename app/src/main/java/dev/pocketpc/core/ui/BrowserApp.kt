package dev.pocketpc.core.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val POCKETPC_HOME = "https://www.google.com/"

data class BrowserTabState(
    val id: Long,
    val title: String,
    val url: String,
)

class BrowserSessionState {
    private var nextTabId = 2L
    private val webViewStates =
        mutableMapOf<Long, Bundle>()

    val tabs = mutableStateListOf(
        BrowserTabState(
            id = 1L,
            title = "Google",
            url = POCKETPC_HOME,
        )
    )

    var activeTabId by mutableStateOf(1L)
    var desktopMode by mutableStateOf(false)

    val activeTab: BrowserTabState
        get() =
            tabs.firstOrNull { it.id == activeTabId }
                ?: tabs.first()

    fun newTab(url: String = POCKETPC_HOME): BrowserTabState {
        val tab = BrowserTabState(
            id = nextTabId++,
            title = "Nova aba",
            url = url,
        )
        tabs += tab
        activeTabId = tab.id
        return tab
    }

    fun selectTab(id: Long) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
        }
    }

    fun updateActive(url: String, title: String? = null) {
        val index = tabs.indexOfFirst { it.id == activeTabId }
        if (index < 0) return
        val current = tabs[index]
        tabs[index] = current.copy(
            url = url,
            title =
                title
                    ?.takeIf { it.isNotBlank() }
                    ?.take(60)
                    ?: current.title,
        )
    }

    fun saveWebViewState(
        tabId: Long,
        bundle: Bundle,
    ) {
        webViewStates[tabId] = Bundle(bundle)
    }

    fun webViewState(tabId: Long): Bundle? =
        webViewStates[tabId]?.let(::Bundle)

    fun clearWebViewState(tabId: Long) {
        webViewStates.remove(tabId)
    }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return

        if (tabs.size == 1) {
            clearWebViewState(id)
            tabs[0] = BrowserTabState(
                id = tabs[0].id,
                title = "Google",
                url = POCKETPC_HOME,
            )
            activeTabId = tabs[0].id
            return
        }

        val wasActive = activeTabId == id
        clearWebViewState(id)
        tabs.removeAt(index)
        if (wasActive) {
            activeTabId =
                tabs[index.coerceAtMost(tabs.lastIndex)].id
        }
    }
}

@Composable
fun BrowserApp(session: BrowserSessionState) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember(session.activeTabId) {
        mutableStateOf(session.activeTab.url)
    }
    var progress by remember { mutableFloatStateOf(0f) }
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }

    fun navigate(raw: String) {
        val target = browserTarget(raw)
        address = target
        session.updateActive(target)
        webView?.loadUrl(target)
    }

    fun saveActiveWebViewState() {
        val view = webView ?: return
        val bundle = Bundle()
        view.saveState(bundle)
        session.saveWebViewState(
            session.activeTabId,
            bundle,
        )
    }

    fun restoreOrLoadTab(
        view: WebView,
        tab: BrowserTabState,
    ) {
        val restored =
            session.webViewState(tab.id)
                ?.let(view::restoreState)
        if (restored == null) {
            view.clearHistory()
            view.loadUrl(tab.url)
        }
    }

    fun loadTab(tab: BrowserTabState) {
        saveActiveWebViewState()
        session.selectTab(tab.id)
        address = tab.url
        webView?.let { view ->
            restoreOrLoadTab(view, tab)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        BrowserTabStrip(
            session = session,
            onSelect = ::loadTab,
            onNewTab = {
                saveActiveWebViewState()
                val tab = session.newTab()
                address = tab.url
                webView?.let { view ->
                    restoreOrLoadTab(view, tab)
                }
            },
            onClose = { tab ->
                val closingActive = session.activeTabId == tab.id
                session.closeTab(tab.id)
                if (closingActive) {
                    address = session.activeTab.url
                    webView?.let { view ->
                        restoreOrLoadTab(
                            view,
                            session.activeTab,
                        )
                    }
                }
            },
        )

        Surface(
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BrowserNavButton("←") {
                    webView?.takeIf { it.canGoBack() }?.goBack()
                }
                BrowserNavButton("→") {
                    webView?.takeIf { it.canGoForward() }?.goForward()
                }
                BrowserNavButton("↻") { webView?.reload() }
                BrowserNavButton("⌂") { navigate(POCKETPC_HOME) }

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp, max = 48.dp),
                    singleLine = true,
                    placeholder = { Text("Endereço ou pesquisa") },
                    shape = RoundedCornerShape(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    keyboardActions =
                        androidx.compose.foundation.text.KeyboardActions(
                            onDone = { navigate(address) }
                        ),
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction =
                                androidx.compose.ui.text.input.ImeAction.Go
                        ),
                )

                BrowserNavButton("Ir", wide = true) {
                    navigate(address)
                }
                BrowserNavButton(
                    if (session.desktopMode) "PC✓" else "PC",
                    wide = true,
                ) {
                    val next = !session.desktopMode
                    session.desktopMode = next
                    webView?.let { view ->
                        val base =
                            mobileUserAgent
                                ?: view.settings.userAgentString.orEmpty()
                        view.settings.userAgentString =
                            if (next) desktopUserAgent(base) else base
                        view.settings.useWideViewPort = next
                        view.settings.loadWithOverviewMode = next
                        view.reload()
                    }
                }
                BrowserNavButton("↓") {
                    runCatching {
                        context.startActivity(
                            Intent(
                                DownloadManager.ACTION_VIEW_DOWNLOADS
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
                BrowserNavButton("↗") {
                    val url = webView?.url ?: address
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url),
                            ).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                }
            }
        }

        if (progress in 0.001f..0.999f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { activityContext ->
                WebView(activityContext).apply {
                    webView = this
                    mobileUserAgent = settings.userAgentString

                    if (session.desktopMode) {
                        settings.userAgentString =
                            desktopUserAgent(
                                settings.userAgentString.orEmpty()
                            )
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
                    settings.setSupportMultipleWindows(false)

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance()
                        .setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val scheme =
                                uri.scheme.orEmpty().lowercase()
                            if (
                                scheme == "http" ||
                                scheme == "https"
                            ) {
                                return false
                            }

                            return runCatching {
                                activityContext.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri)
                                )
                                true
                            }.getOrDefault(true)
                        }

                        override fun onPageFinished(
                            view: WebView?,
                            url: String?,
                        ) {
                            super.onPageFinished(view, url)
                            if (!url.isNullOrBlank()) {
                                address = url
                                session.updateActive(
                                    url = url,
                                    title = view?.title,
                                )
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(
                            view: WebView?,
                            newProgress: Int,
                        ) {
                            progress =
                                newProgress.coerceIn(0, 100) / 100f
                        }

                        override fun onReceivedTitle(
                            view: WebView?,
                            title: String?,
                        ) {
                            if (!title.isNullOrBlank()) {
                                val currentUrl =
                                    view?.url
                                        ?: session.activeTab.url
                                session.updateActive(
                                    url = currentUrl,
                                    title = title,
                                )
                            }
                        }
                    }

                    setDownloadListener(
                        DownloadListener {
                                url,
                                userAgent,
                                contentDisposition,
                                mimeType,
                                _,
                            ->
                            enqueueDownload(
                                context = activityContext,
                                url = url,
                                userAgent = userAgent,
                                contentDisposition =
                                    contentDisposition,
                                mimeType = mimeType,
                            )
                        }
                    )

                    val restored =
                        session
                            .webViewState(
                                session.activeTabId
                            )
                            ?.let { bundle ->
                                restoreState(bundle)
                            }
                    if (restored == null) {
                        loadUrl(
                            session.activeTab.url
                        )
                    }
                }
            },
            update = { view ->
                webView = view
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { view ->
                val bundle = Bundle()
                view.saveState(bundle)
                session.saveWebViewState(
                    session.activeTabId,
                    bundle,
                )
            }
            webView?.apply {
                stopLoading()
                setDownloadListener(null)
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun BrowserTabStrip(
    session: BrowserSessionState,
    onSelect: (BrowserTabState) -> Unit,
    onNewTab: () -> Unit,
    onClose: (BrowserTabState) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            session.tabs.forEach { tab ->
                val active = tab.id == session.activeTabId
                Surface(
                    modifier = Modifier
                        .widthIn(min = 110.dp, max = 220.dp)
                        .height(30.dp)
                        .clickable { onSelect(tab) },
                    shape = RoundedCornerShape(
                        topStart = 9.dp,
                        topEnd = 9.dp,
                    ),
                    color =
                        if (active) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = 9.dp,
                            end = 2.dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tab.title.ifBlank { "Nova aba" },
                            modifier = Modifier.weight(1f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { onClose(tab) },
                            modifier = Modifier.size(28.dp),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("×", fontSize = 12.sp)
                        }
                    }
                }
            }

            TextButton(
                onClick = onNewTab,
                modifier = Modifier.size(30.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun BrowserNavButton(
    label: String,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .height(40.dp)
            .widthIn(min = if (wide) 48.dp else 40.dp),
        contentPadding = PaddingValues(
            horizontal = if (wide) 8.dp else 4.dp,
            vertical = 0.dp,
        ),
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1)
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

    val query =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name(),
        )
    return "https://www.google.com/search?q=$query"
}

internal fun desktopUserAgent(base: String): String {
    val chromeVersion =
        Regex("""Chrome/([0-9.]+)""")
            .find(base)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: "150.0.0.0"

    return (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeVersion Safari/537.36"
        )
}

private fun enqueueDownload(
    context: Context,
    url: String?,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    if (url.isNullOrBlank()) {
        Toast.makeText(
            context,
            "Download sem URL.",
            Toast.LENGTH_SHORT,
        ).show()
        return
    }

    runCatching {
        val fileName =
            URLUtil.guessFileName(
                url,
                contentDisposition,
                mimeType,
            )
        val request =
            DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Download pelo PocketPC")
                .setNotificationVisibility(
                    DownloadManager.Request
                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

        if (!mimeType.isNullOrBlank()) {
            request.setMimeType(mimeType)
        }
        if (!userAgent.isNullOrBlank()) {
            request.addRequestHeader(
                "User-Agent",
                userAgent,
            )
        }
        CookieManager.getInstance()
            .getCookie(url)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                request.addRequestHeader(
                    "Cookie",
                    it,
                )
            }

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

        val manager =
            context.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(
            context,
            "Download iniciado: $fileName",
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure { error ->
        Toast.makeText(
            context,
            "Falha no download: " +
                (error.message
                    ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG,
        ).show()
    }
}
