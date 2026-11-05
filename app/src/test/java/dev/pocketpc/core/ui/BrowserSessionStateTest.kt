package dev.pocketpc.core.ui

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class BrowserSessionStateTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    @Test fun oldPageCallbacksCannotOverwriteANewTab() {
        val session = BrowserSessionState()
        val oldId = session.activeTabId
        session.updateActive("https://example.com/old", "Old")
        session.newTab()
        session.updateTab(oldId, "https://example.com/redirect", "Redirect")
        assertEquals(POCKETPC_HOME, session.activeTab.url)
        assertEquals("Nova aba", session.activeTab.title)
    }

    @Test fun closingLastTabCreatesANewIdentity() {
        val session = BrowserSessionState()
        val oldId = session.activeTabId
        session.closeTab(oldId)
        session.updateTab(oldId, "https://example.com/late", "Late")
        assertNotEquals(oldId, session.activeTabId)
        assertEquals(POCKETPC_HOME, session.activeTab.url)
    }

    @Test fun recreationRestoresSelectionModeAndUniqueIds() {
        val session = BrowserSessionState()
        val first = session.activeTabId
        val second = session.newTab("https://example.com/")
        session.selectTab(first)
        session.desktopMode = false
        val saved = with(BrowserSessionState.Saver) { scope.save(session) }
        val restored = requireNotNull(BrowserSessionState.Saver.restore(requireNotNull(saved)))
        assertEquals(session.tabs.toList(), restored.tabs.toList())
        assertEquals(first, restored.activeTabId)
        assertFalse(restored.desktopMode)
        assertTrue(restored.newTab().id > second.id)
    }

    @Test fun tabLimitPreservesTheActivePage() {
        val session = BrowserSessionState()
        repeat(100) { session.newTab() }
        assertEquals(32, session.tabs.size)
        assertFalse(session.canAddTab)
        val active = session.activeTab
        assertEquals(active, session.newTab())
        session.closeTab(active.id)
        assertTrue(session.canAddTab)
    }
}
