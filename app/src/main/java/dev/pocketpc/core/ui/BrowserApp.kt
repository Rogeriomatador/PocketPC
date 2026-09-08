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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketpc.core.storage.PocketDownloadRegistry
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.storage.sanitizePocketImportedFileName
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal const val POCKETPC_HOME = "https://www.google.com/"

data class BrowserTabState(val id: Long, val title: String, val url: String)

class BrowserSessionState {
    private var nextTabId = 2L
    private val webViewStates = mutableMapOf<Long, Bundle>()
    val tabs = mutableStateListOf(BrowserTabState(1L, "Google", POCKETPC_HOME))
    var activeTabId by mutableStateOf(1L)
    var desktopMode by mutableStateOf(true)
    val canAddTab: Boolean get() = tabs.size < 32

    val activeTab: BrowserTabState
        get() = tabs.firstOrNull { it.id == activeTabId } ?: tabs.first()

    fun newTab(url: String = POCKETPC_HOME): BrowserTabState {
        if (!canAddTab) return activeTab
        val tab = BrowserTabState(nextTabId++, "Nova aba", url)
        tabs += tab
        activeTabId = tab.id
        return tab
    }

    fun selectTab(id: Long) { if (tabs.any { it.id == id }) activeTabId = id }

    fun updateActive(url: String, title: String? = null) = updateTab(activeTabId, url, title)

    fun updateTab(tabId: Long, url: String, title: String? = null) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val current = tabs[index]
        tabs[index] = current.copy(url = url, title = title?.takeIf { it.isNotBlank() }?.take(60) ?: current.title)
    }

    fun saveWebViewState(tabId: Long, bundle: Bundle) {
        if (tabs.any { it.id == tabId }) webViewStates[tabId] = Bundle(bundle)
    }
    fun webViewState(tabId: Long): Bundle? = webViewStates[tabId]?.let(::Bundle)
    fun clearWebViewState(tabId: Long) { webViewStates.remove(tabId) }

    fun closeTab(id: Long) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        if (tabs.size == 1) {
            clearWebViewState(id)
            tabs[0] = BrowserTabState(nextTabId++, "Google", POCKETPC_HOME)
            activeTabId = tabs[0].id
            return
        }
        val wasActive = activeTabId == id
        clearWebViewState(id)
        tabs.removeAt(index)
        if (wasActive) activeTabId = tabs[index.coerceAtMost(tabs.lastIndex)].id
    }
    companion object {
        // Keep WebView history in memory; only lightweight tab metadata goes into saved state.
        val Saver = listSaver<BrowserSessionState, Any>(
            save = { session ->
                listOf(session.activeTabId, session.desktopMode) + session.tabs.flatMap {
                    listOf(it.id, it.title, it.url.takeIf { url -> url.length <= 4096 } ?: POCKETPC_HOME)
                }
            },
            restore = { saved -> BrowserSessionState().apply {
                val restored = saved.drop(2).chunked(3).mapNotNull { values ->
                    if (values.size != 3) return@mapNotNull null
                    val id = values[0] as? Long ?: return@mapNotNull null
                    val title = values[1] as? String ?: return@mapNotNull null
                    val url = values[2] as? String ?: return@mapNotNull null
                    if (id <= 0 || id == Long.MAX_VALUE) return@mapNotNull null
                    BrowserTabState(id, title.take(60), url)
                }.distinctBy { it.id }.take(32)
                if (restored.isNotEmpty()) { tabs.clear(); tabs.addAll(restored) }
                nextTabId = tabs.maxOf { it.id } + 1
                activeTabId = (saved.firstOrNull() as? Long)
                    ?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
                desktopMode = saved.getOrNull(1) as? Boolean ?: true
            } },
        )
    }

}

data class BrowserWindowActions(
    val minimized: () -> Unit,
    val toggleMaximize: () -> Unit,
    val close: () -> Unit,
    val maximized: Boolean,
)

@Composable
fun BrowserApp(
    session: BrowserSessionState,
    storage: StorageRepository,
    onOpenDownloads: () -> Unit,
    windowActions: BrowserWindowActions? = null,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compactWindowControls =
        configuration.screenWidthDp < 700 ||
            configuration.screenHeightDp < 500
    val integratedWindowControls =
        windowActions != null &&
            (
                compactWindowControls ||
                    windowActions.maximized
            )
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember(session.activeTabId) { mutableStateOf(session.activeTab.url) }
    var progress by remember { mutableFloatStateOf(0f) }
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }

    fun navigate(raw: String) {
        val target = browserTarget(raw)
        address = target
        session.updateActive(target)
        webView?.loadUrl(target)
    }
    var menuOpen by remember { mutableStateOf(false) }
    fun toggleDesktopMode() {
        session.desktopMode = !session.desktopMode
        webView?.let { view ->
            val base = mobileUserAgent ?: view.settings.userAgentString.orEmpty()
            view.settings.userAgentString = if (session.desktopMode) desktopUserAgent(base) else base
            view.settings.useWideViewPort = session.desktopMode
            view.settings.loadWithOverviewMode = session.desktopMode
            view.reload()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(
                            rememberScrollState()
                        )
                        .padding(horizontal = 4.dp),
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(3.dp),
                ) {
                    session.tabs.forEach { tab ->
                    val active = tab.id == session.activeTabId
                    Surface(
                        Modifier.widthIn(min = 104.dp, max = 210.dp).height(28.dp).clickable { session.selectTab(tab.id) },
                        shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp),
                        color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(Modifier.padding(start = 9.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(tab.title.ifBlank { "Nova aba" }, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                session.closeTab(tab.id)
                            }, modifier = Modifier.size(24.dp), contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 12.sp) }
                        }
                    }
                }
                    TextButton(
                        onClick = {
                            session.newTab()
                        },
                        enabled = session.canAddTab,
                        modifier = Modifier.size(26.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("+", fontSize = 15.sp)
                    }
                }

                if (integratedWindowControls) {
                    BrowserWindowControl("—") {
                        windowActions?.minimized?.invoke()
                    }
                    BrowserWindowControl(
                        if (windowActions?.maximized == true) {
                            "▣"
                        } else {
                            "□"
                        }
                    ) {
                        windowActions?.toggleMaximize?.invoke()
                    }
                    BrowserWindowControl(
                        label = "×",
                        danger = true,
                    ) {
                        windowActions?.close?.invoke()
                    }
                }
            }
        }

        Surface(tonalElevation = 2.dp) {
            BoxWithConstraints {
                val compactToolbar = maxWidth < 700.dp

                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 5.dp,
                            vertical = 2.dp,
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.spacedBy(4.dp),
                    ) {
                        BrowserNavButton("←") {
                            webView
                                ?.takeIf { it.canGoBack() }
                                ?.goBack()
                        }
                        BrowserNavButton("→") {
                            webView
                                ?.takeIf { it.canGoForward() }
                                ?.goForward()
                        }
                        BrowserNavButton("↻") {
                            webView?.reload()
                        }
                        if (!compactToolbar) {
                            BrowserNavButton("⌂") {
                                navigate(POCKETPC_HOME)
                            }
                        }

                        BrowserAddressField(
                            value = address,
                            onValueChange = {
                                address = it
                            },
                            onGo = {
                                navigate(address)
                            },
                            modifier =
                                Modifier.weight(1f),
                        )
                        BrowserNavButton("Ir", true) {
                            navigate(address)
                        }

                        if (!compactToolbar) {
                            BrowserNavButton(
                                if (session.desktopMode) {
                                    "PC✓"
                                } else {
                                    "PC"
                                },
                                true,
                            ) {
                                session.desktopMode =
                                    !session.desktopMode
                                webView?.let { view ->
                                    val base =
                                        mobileUserAgent
                                            ?: view.settings
                                                .userAgentString
                                                .orEmpty()
                                    view.settings
                                        .userAgentString =
                                        if (
                                            session.desktopMode
                                        ) {
                                            desktopUserAgent(base)
                                        } else {
                                            base
                                        }
                                    view.settings
                                        .useWideViewPort =
                                        session.desktopMode
                                    view.settings
                                        .loadWithOverviewMode =
                                        session.desktopMode
                                    view.reload()
                                }
                            }
                            BrowserNavButton("↓", onClick = onOpenDownloads)
                            BrowserNavButton("↗") {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(
                                                webView?.url
                                                    ?: address
                                            ),
                                        ).addFlags(
                                            Intent
                                                .FLAG_ACTIVITY_NEW_TASK
                                        )
                                    )
                                }
                            }
                        }
                        if (compactToolbar) {
                            Box {
                                BrowserNavButton("⋮") { menuOpen = true }
                                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Página inicial") },
                                        onClick = { menuOpen = false; navigate(POCKETPC_HOME) })
                                    DropdownMenuItem(text = { Text(if (session.desktopMode) "Usar versão para celular" else "Usar versão para computador") },
                                        onClick = { menuOpen = false; toggleDesktopMode() })
                                    DropdownMenuItem(text = { Text("Downloads do PocketPC") },
                                        onClick = { menuOpen = false; onOpenDownloads() })
                                    DropdownMenuItem(text = { Text("Abrir em outro navegador") }, onClick = {
                                        menuOpen = false
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: address))) }
                                    })
                                }
                            }
                        }

                    }


                }
            }
        }

        if (progress in 0.001f..0.999f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))

        key(session.activeTabId) {
        val tabId = session.activeTabId
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
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
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            if (uri.scheme.orEmpty().lowercase() in setOf("http", "https")) return false
                            return runCatching { activityContext.startActivity(Intent(Intent.ACTION_VIEW, uri)); true }.getOrDefault(true)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (!url.isNullOrBlank()) { if (session.activeTabId == tabId) address = url; session.updateTab(tabId, url, view?.title) }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress.coerceIn(0, 100) / 100f }
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrBlank()) session.updateTab(tabId, view?.url ?: session.activeTab.url, title)
                        }
                    }
                    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        enqueueDownload(activityContext, storage, url, userAgent, contentDisposition, mimeType)
                    })
                    val restored = session.webViewState(tabId)?.let(::restoreState)
                    if (restored == null) loadUrl(session.activeTab.url)
                }
            },
            onRelease = { view ->
                runCatching { session.saveWebViewState(tabId, Bundle().also(view::saveState)) }
                view.stopLoading()
                view.setDownloadListener(null)
                view.webChromeClient = WebChromeClient()
                view.webViewClient = WebViewClient()
                view.destroy()
                if (webView === view) webView = null
            },
            update = { webView = it },
        )
    }

}
}

@Composable
private fun BrowserAddressField(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor =
        MaterialTheme.colorScheme.onSurface
    val cursorColor =
        MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(12.dp),
        color =
            MaterialTheme.colorScheme
                .surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize(),
            singleLine = true,
            textStyle =
                LocalTextStyle.current.copy(
                    color = textColor,
                    fontSize = 12.sp,
                ),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Go
                ),
            keyboardActions =
                KeyboardActions(
                    onGo = {
                        onGo()
                    }
                ),
            cursorBrush =
                SolidColor(cursorColor),
            decorationBox = { innerField ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentAlignment =
                        Alignment.CenterStart,
                ) {
                    if (value.isBlank()) {
                        Text(
                            "Pesquisar ou digitar endereço",
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                    innerField()
                }
            },
        )
    }
}

@Composable
private fun BrowserWindowControl(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(30.dp)
            .widthIn(min = 34.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color =
                if (danger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
                },
        )
    }
}

@Composable
private fun BrowserNavButton(
    label: String,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .height(34.dp)
            .widthIn(
                min =
                    if (wide) {
                        42.dp
                    } else {
                        32.dp
                    }
            ),
        contentPadding = PaddingValues(
            horizontal =
                if (wide) {
                    6.dp
                } else {
                    2.dp
                },
            vertical = 0.dp,
        ),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

internal fun browserTarget(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return POCKETPC_HOME
    if (value.startsWith("https://", true) || value.startsWith("http://", true)) return value
    if (!value.contains(' ') && value.contains('.')) return "https://$value"
    return "https://www.google.com/search?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

internal fun desktopUserAgent(base: String): String {
    val chromeVersion = Regex("""Chrome/([0-9.]+)""").find(base)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "150.0.0.0"
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
}

internal fun contentDispositionFileName(
    header: String?,
): String? {
    if (header.isNullOrBlank()) {
        return null
    }

    val parameters =
        header
            .split(';')
            .drop(1)
            .map { it.trim() }
            .filter { '=' in it }

    fun valueFor(
        names: Set<String>,
    ): String? =
        parameters
            .firstOrNull { parameter ->
                parameter
                    .substringBefore('=')
                    .trim()
                    .lowercase() in names
            }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

    val encoded =
        valueFor(
            setOf(
                "filename*",
                "filename_",
            )
        )

    val regular =
        valueFor(
            setOf(
                "filename",
            )
        )

    val raw =
        encoded
            ?: regular
            ?: return null

    val payload =
        raw
            .substringAfter(
                "''",
                raw,
            )
            .trim()
            .trim('"')

    return runCatching {
        URLDecoder.decode(
            payload,
            StandardCharsets.UTF_8.name(),
        )
    }
        .getOrDefault(payload)
        .takeIf { it.isNotBlank() }
}

internal fun resolvePocketDownloadFileName(
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): String {
    val raw =
        contentDispositionFileName(
            contentDisposition
        )
            ?: URLUtil.guessFileName(
                url,
                null,
                mimeType,
            )

    return sanitizePocketImportedFileName(
        raw
    )
}

private fun enqueueDownload(context: Context, storage: StorageRepository, url: String?, userAgent: String?, contentDisposition: String?, mimeType: String?) {
    if (url.isNullOrBlank()) { Toast.makeText(context, "Download sem URL.", Toast.LENGTH_SHORT).show(); return }
    runCatching {
        val fileName =
            resolvePocketDownloadFileName(
                url = url,
                contentDisposition =
                    contentDisposition,
                mimeType = mimeType,
            )
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription("Download pelo PocketPC")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { request.addRequestHeader("Cookie", it) }
        val pocketDriveConfigured = storage.rootUriString != null
        if (pocketDriveConfigured) {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "pocketpc-${System.currentTimeMillis()}-$fileName")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        PocketDownloadRegistry(context).register(downloadId)
        Toast.makeText(
            context,
            if (pocketDriveConfigured) {
                "Baixando $fileName • será importado para P:\\Downloads quando concluir"
            } else {
                "Baixando $fileName • conecte um PocketDrive para importar depois"
            },
            Toast.LENGTH_LONG,
        ).show()
    }.onFailure { error ->
        Toast.makeText(context, "Falha no download: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }
}
