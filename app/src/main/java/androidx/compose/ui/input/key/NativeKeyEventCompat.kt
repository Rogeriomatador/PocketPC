package androidx.compose.ui.input.key

import android.view.KeyEvent as AndroidKeyEvent

/** Android compatibility shim for Compose versions where KeyEvent is the native Android event. */
val KeyEvent.nativeKeyEvent: AndroidKeyEvent
    get() = this as AndroidKeyEvent
