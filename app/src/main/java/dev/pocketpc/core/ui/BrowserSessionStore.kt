package dev.pocketpc.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import org.json.JSONArray
import org.json.JSONObject

private const val BROWSER_SESSION_PREFS =
    "pocketpc-browser-session"
private const val BROWSER_SESSION_KEY =
    "durable-session-v1"
private const val BROWSER_SESSION_SCHEMA = 1
private const val MAX_PERSISTED_TABS = 32
private const val MAX_PERSISTED_URL_LENGTH = 4096
private const val MAX_PERSISTED_TITLE_LENGTH = 60

internal data class DurableBrowserTab(
    val title: String,
    val url: String,
)

internal data class DurableBrowserSession(
    val tabs: List<DurableBrowserTab>,
    val activeIndex: Int,
    val desktopMode: Boolean,
)

/**
 * Durable browser *navigation metadata* store.
 *
 * Authentication state is deliberately NOT copied here. WebView owns cookies,
 * WebStorage/IndexedDB and its profile under the package data directory. Those
 * survive an in-place same-signature package update and are flushed by
 * PocketPcDataContinuity before replacement. This store only makes the visible
 * browser workspace (tabs/current tab/desktop mode) survive process death and
 * package replacement as well.
 */
internal class BrowserSessionStore(
    context: Context,
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            BROWSER_SESSION_PREFS,
            Context.MODE_PRIVATE,
        )

    fun load(): BrowserSessionState? =
        prefs.getString(BROWSER_SESSION_KEY, null)
            ?.let(::decode)
            ?.let(::restore)

    fun save(
        snapshot: DurableBrowserSession,
    ) {
        prefs.edit()
            .putString(
                BROWSER_SESSION_KEY,
                encode(snapshot),
            )
            .apply()
    }

    private fun encode(
        snapshot: DurableBrowserSession,
    ): String {
        val safeTabs =
            snapshot.tabs
                .take(MAX_PERSISTED_TABS)
                .map { tab ->
                    DurableBrowserTab(
                        title =
                            tab.title
                                .take(MAX_PERSISTED_TITLE_LENGTH),
                        url = persistentUrl(tab.url),
                    )
                }

        val tabsJson = JSONArray()
        safeTabs.forEach { tab ->
            tabsJson.put(
                JSONObject()
                    .put("title", tab.title)
                    .put("url", tab.url)
            )
        }

        return JSONObject()
            .put("schema", BROWSER_SESSION_SCHEMA)
            .put("desktopMode", snapshot.desktopMode)
            .put(
                "activeIndex",
                snapshot.activeIndex.coerceIn(
                    0,
                    (safeTabs.size - 1).coerceAtLeast(0),
                ),
            )
            .put("tabs", tabsJson)
            .toString()
    }

    private fun decode(
        raw: String,
    ): DurableBrowserSession? =
        runCatching {
            val root = JSONObject(raw)
            require(
                root.optInt("schema", -1) ==
                    BROWSER_SESSION_SCHEMA
            )

            val tabsJson = root.optJSONArray("tabs")
                ?: return@runCatching null
            val count =
                tabsJson.length()
                    .coerceAtMost(MAX_PERSISTED_TABS)
            val tabs =
                buildList {
                    repeat(count) { index ->
                        val tab =
                            tabsJson.optJSONObject(index)
                                ?: return@repeat
                        val title =
                            tab.optString(
                                "title",
                                "Nova aba",
                            )
                                .take(MAX_PERSISTED_TITLE_LENGTH)
                        val url =
                            persistentUrl(
                                tab.optString(
                                    "url",
                                    POCKETPC_HOME,
                                )
                            )
                        add(
                            DurableBrowserTab(
                                title = title,
                                url = url,
                            )
                        )
                    }
                }

            if (tabs.isEmpty()) {
                return@runCatching null
            }

            DurableBrowserSession(
                tabs = tabs,
                activeIndex =
                    root.optInt("activeIndex", 0)
                        .coerceIn(0, tabs.lastIndex),
                desktopMode =
                    root.optBoolean(
                        "desktopMode",
                        true,
                    ),
            )
        }.getOrNull()

    private fun restore(
        snapshot: DurableBrowserSession,
    ): BrowserSessionState {
        val state = BrowserSessionState()
        val tabs =
            snapshot.tabs
                .take(MAX_PERSISTED_TABS)
                .ifEmpty {
                    listOf(
                        DurableBrowserTab(
                            title = "Google",
                            url = POCKETPC_HOME,
                        )
                    )
                }

        val first = tabs.first()
        state.updateTab(
            tabId = state.tabs.first().id,
            url = persistentUrl(first.url),
            title = first.title,
        )

        tabs.drop(1).forEach { saved ->
            val created =
                state.newTab(
                    persistentUrl(saved.url)
                )
            state.updateTab(
                tabId = created.id,
                url = persistentUrl(saved.url),
                title = saved.title,
            )
        }

        state.desktopMode = snapshot.desktopMode
        state.selectTab(
            state.tabs[
                snapshot.activeIndex.coerceIn(
                    0,
                    state.tabs.lastIndex,
                )
            ].id
        )
        return state
    }

    companion object {
        internal fun persistentUrl(
            raw: String,
        ): String {
            val candidate =
                raw.trim()
                    .take(MAX_PERSISTED_URL_LENGTH)
            val lower = candidate.lowercase()
            return when {
                lower.startsWith("https://") -> candidate
                lower.startsWith("http://") -> candidate
                lower == "about:blank" -> candidate
                else -> POCKETPC_HOME
            }
        }
    }
}

@Composable
internal fun rememberPersistentBrowserSession(
    context: Context,
): BrowserSessionState {
    val appContext = context.applicationContext
    val store = remember(appContext) {
        BrowserSessionStore(appContext)
    }
    val session =
        rememberSaveable(
            saver = BrowserSessionState.Saver,
        ) {
            store.load() ?: BrowserSessionState()
        }

    LaunchedEffect(session, store) {
        snapshotFlow {
            val activeIndex =
                session.tabs.indexOfFirst {
                    it.id == session.activeTabId
                }.coerceAtLeast(0)
            DurableBrowserSession(
                tabs =
                    session.tabs.map { tab ->
                        DurableBrowserTab(
                            title = tab.title,
                            url = tab.url,
                        )
                    },
                activeIndex = activeIndex,
                desktopMode = session.desktopMode,
            )
        }.collect(store::save)
    }

    return session
}
