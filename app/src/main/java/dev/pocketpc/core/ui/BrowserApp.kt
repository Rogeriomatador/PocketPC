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
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    isActive: Boolean = true,
    windowActions: BrowserWindowActions? = null,
) {
    val context = LocalContext.current
    val compactWindowControls = LocalDesktopLayout.current.compact
    val integratedWindowControls = windowActions != null &&
        (compactWindowControls || windowActions.maximized)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember(session.activeTabId) { mutableStateOf(session.activeTab.url) }
    var editingAddress by remember { mutableStateOf(false) }
    var progress by remember(session.activeTabId) { mutableFloatStateOf(0f) }
    var canGoBack by remember(session.activeTabId) { mutableStateOf(false) }
    var canGoForward by remember(session.activeTabId) { mutableStateOf(false) }
    var loadError by remember(session.activeTabId) { mutableStateOf<String?>(null) }
    var mobileUserAgent by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val tabsScroll = rememberLazyListState()

    fun navigate(raw: String) {
        val target = browserTarget(raw)
        focusManager.clearFocus()
        keyboard?.hide()
        editingAddress = false
        loadError = null
        address = target
        session.updateActive(target)
        webView?.loadUrl(target)
    }
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
    fun openExternal() {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView?.url ?: address)))
        }.onFailure { Toast.makeText(context, "Nenhum aplicativo disponível para abrir esta página.", Toast.LENGTH_SHORT).show() }
    }
    BackHandler(enabled = isActive && canGoBack) { webView?.goBack() }
    LaunchedEffect(session.activeTabId) {
        editingAddress = false
        focusManager.clearFocus()
        tabsScroll.animateScrollToItem(session.tabs.indexOfFirst { it.id == session.activeTabId }.coerceAtLeast(0))
    }
    DisposableEffect(webView, lifecycleOwner) {
        val view = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view?.onResume()
                Lifecycle.Event.ON_STOP -> view?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    state = tabsScroll,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(session.tabs, key = { it.id }) { tab ->
                        val active = tab.id == session.activeTabId
                        Surface(
                            modifier = Modifier.width(180.dp).height(48.dp)
                                .semantics { selected = active }
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { session.selectTab(tab.id) },
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                            color = if (active) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.title.ifBlank { "Nova aba" }, Modifier.weight(1f),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                BrowserNavButton("×", description = "Fechar aba ${tab.title}") { session.closeTab(tab.id) }
                            }
                        }
                    }
                }
                BrowserNavButton("+", description = "Nova aba", enabled = session.canAddTab) { session.newTab() }
                if (integratedWindowControls) {
                    WindowControlButton("—") { windowActions.minimized.invoke() }
                    // Compact windows already fill the workspace; maximize would have no visible effect.
                    if (!compactWindowControls) {
                        WindowControlButton(if (windowActions.maximized) "▣" else "□") {
                            windowActions.toggleMaximize.invoke()
                        }
                    }
                    WindowControlButton("×", danger = true) { windowActions.close.invoke() }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            BoxWithConstraints {
                val compactToolbar = maxWidth < 840.dp
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    BrowserNavButton("←", description = "Voltar", enabled = canGoBack) { webView?.goBack() }
                    if (!compactToolbar) {
                        BrowserNavButton("→", description = "Avançar", enabled = canGoForward) { webView?.goForward() }
                        BrowserNavButton("↻", description = "Recarregar página") { webView?.reload() }
                        BrowserNavButton("⌂", description = "Página inicial") { navigate(POCKETPC_HOME) }
                    }
                    BrowserAddressField(
                        value = address,
                        onValueChange = { address = it },
                        onGo = { navigate(address) },
                        onFocusChange = { editingAddress = it },
                        modifier = Modifier.weight(1f),
                    )
                    if (!compactToolbar) {
                        BrowserNavButton("Ir") { navigate(address) }
                        BrowserNavButton(if (session.desktopMode) "PC✓" else "PC",
                            description = "Alternar versão para computador", onClick = ::toggleDesktopMode)
                        BrowserNavButton("↓", description = "Downloads do PocketPC", onClick = onOpenDownloads)
                    }
                    Box {
                        BrowserNavButton("⋮", description = "Opções do navegador") { menuOpen = true }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Avançar") }, enabled = canGoForward,
                                onClick = { menuOpen = false; webView?.goForward() })
                            DropdownMenuItem(text = { Text("Recarregar página") },
                                onClick = { menuOpen = false; webView?.reload() })
                            DropdownMenuItem(text = { Text("Página inicial") },
                                onClick = { menuOpen = false; navigate(POCKETPC_HOME) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (session.desktopMode) "Usar versão para celular" else "Usar versão para computador") },
                                onClick = { menuOpen = false; toggleDesktopMode() })
                            DropdownMenuItem(text = { Text("Downloads do PocketPC") },
                                onClick = { menuOpen = false; onOpenDownloads() })
                            DropdownMenuItem(text = { Text("Abrir em outro navegador") },
                                onClick = { menuOpen = false; openExternal() })
                        }
                    }
                }
            }
        }
        // Reserve the progress strip so page content doesn't jump on every navigation.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 0.001f..0.999f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize())
        }
        loadError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { loadError = null; webView?.reload() }) { Text("Tentar novamente") }
                }
            }
        }
        key(session.activeTabId) {
            val tabId = session.activeTabId
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { activityContext ->
                    WebView(activityContext).apply {
                        webView = this
                        mobileUserAgent = settings.userAgentString
                        if (session.desktopMode) settings.userAgentString = desktopUserAgent(settings.userAgentString.orEmpty())
                        settings.useWideViewPort = session.desktopMode
                        settings.loadWithOverviewMode = session.desktopMode
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
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
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                if (session.activeTabId == tabId) loadError = null
                            }
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (session.activeTabId == tabId) {
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    if (!editingAddress && !url.isNullOrBlank()) address = url
                                }
                                if (!url.isNullOrBlank()) session.updateTab(tabId, url, view?.title)
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (session.activeTabId == tabId) {
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                    if (!editingAddress && !url.isNullOrBlank()) address = url
                                }
                                if (!url.isNullOrBlank()) session.updateTab(tabId, url, view?.title)
                            }
                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                if (request?.isForMainFrame == true && session.activeTabId == tabId) {
                                    loadError = "Não foi possível carregar a página. Verifique a conexão ou o endereço."
                                    progress = 0f
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (session.activeTabId == tabId) progress = newProgress.coerceIn(0, 100) / 100f
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                val tab = session.tabs.firstOrNull { it.id == tabId } ?: return
                                if (!title.isNullOrBlank()) session.updateTab(tabId, view?.url ?: tab.url, title)
                            }
                        }
                        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            enqueueDownload(activityContext, storage, url, userAgent, contentDisposition, mimeType)
                        })
                        val restored = session.webViewState(tabId)?.let(::restoreState)
                        if (restored == null) loadUrl(session.activeTab.url)
                        canGoBack = this.canGoBack()
                        canGoForward = this.canGoForward()
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
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor =
        MaterialTheme.colorScheme.onSurface
    val cursorColor =
        MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        color =
            MaterialTheme.colorScheme
                .surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize()
                .onFocusChanged { onFocusChange(it.isFocused) }
                .semantics { contentDescription = "Endereço ou pesquisa" },
            singleLine = true,
            textStyle =
                LocalTextStyle.current.copy(
                    color = textColor,
                    fontSize = 14.sp,
                ),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
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
                            fontSize = 14.sp,
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
private fun BrowserNavButton(
    label: String,
    description: String = label,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 16.sp, maxLines = 1)
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
