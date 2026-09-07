package dev.pocketpc.core

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import dev.pocketpc.core.desktop.DesktopCommand
import dev.pocketpc.core.ui.PocketPcApp
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val desktopCommands =
        MutableSharedFlow<DesktopCommand>(extraBufferCapacity = 32)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        applyDesktopImmersiveMode()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PocketPcApp(commandFlow = desktopCommands)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyDesktopImmersiveMode()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !event.isLongPress) {
            val command = when {
                event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_D ->
                    DesktopCommand.SHOW_DESKTOP

                event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_E ->
                    DesktopCommand.OPEN_FILES

                event.isMetaPressed && event.keyCode == KeyEvent.KEYCODE_B ->
                    DesktopCommand.OPEN_BROWSER

                event.isCtrlPressed &&
                    event.isAltPressed &&
                    event.keyCode == KeyEvent.KEYCODE_T ->
                    DesktopCommand.OPEN_TERMINAL

                event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_TAB ->
                    DesktopCommand.CYCLE_WINDOWS

                event.isAltPressed && event.keyCode == KeyEvent.KEYCODE_F4 ->
                    DesktopCommand.CLOSE_ACTIVE

                event.keyCode == KeyEvent.KEYCODE_META_LEFT ||
                    event.keyCode == KeyEvent.KEYCODE_META_RIGHT ->
                    DesktopCommand.TOGGLE_START

                event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_SPACE ->
                    DesktopCommand.TOGGLE_START

                else -> null
            }

            if (command != null && desktopCommands.tryEmit(command)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 &&
            desktopCommands.tryEmit(DesktopCommand.OPEN_DESKTOP_CONTEXT)
        ) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun applyDesktopImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(
                    WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                )
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
