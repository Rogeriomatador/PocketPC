package dev.pocketpc.core.desktop

data class DesktopPointerAnchor(
    val x: Int,
    val y: Int,
)

object DesktopPointerCommandBridge {
    private val lock = Any()
    private var pending:
        DesktopPointerAnchor? =
        null

    fun record(
        x: Int,
        y: Int,
    ) {
        synchronized(lock) {
            pending =
                DesktopPointerAnchor(
                    x = x.coerceAtLeast(0),
                    y = y.coerceAtLeast(0),
                )
        }
    }

    fun consume():
        DesktopPointerAnchor? =
        synchronized(lock) {
            val value = pending
            pending = null
            value
        }

    fun clear() {
        synchronized(lock) {
            pending = null
        }
    }
}
