package dev.pocketpc.core.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PocketClipboardOperation {
    COPY,
    CUT,
}

data class PocketFileClipboardItem(
    val operation: PocketClipboardOperation,
    val entry: StorageEntry,
)

/**
 * Process-local file clipboard owned by the PocketPC desktop.
 *
 * It deliberately stores PocketDrive metadata rather than using Android's
 * system clipboard. Copy/cut/paste therefore stays inside PocketPC and cannot
 * silently leak file URIs to other Android applications.
 */
object PocketFileClipboard {
    private val mutableItem = MutableStateFlow<PocketFileClipboardItem?>(null)

    val item: StateFlow<PocketFileClipboardItem?> = mutableItem.asStateFlow()

    fun copy(entry: StorageEntry): PocketFileClipboardItem {
        val next = PocketFileClipboardItem(
            operation = PocketClipboardOperation.COPY,
            entry = entry,
        )
        mutableItem.value = next
        return next
    }

    fun cut(entry: StorageEntry): PocketFileClipboardItem {
        val next = PocketFileClipboardItem(
            operation = PocketClipboardOperation.CUT,
            entry = entry,
        )
        mutableItem.value = next
        return next
    }

    fun clear() {
        mutableItem.value = null
    }

    fun clearIfSame(item: PocketFileClipboardItem) {
        if (mutableItem.value == item) {
            mutableItem.value = null
        }
    }
}
