package dev.pocketpc.core

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.view.ViewCompat
import dev.pocketpc.core.desktop.DesktopCommand
import dev.pocketpc.core.ui.PocketPcApp
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val desktopCommands =
        MutableSharedFlow<DesktopCommand>(extraBufferCapacity = 32)

    private val unhandledKeyListener =
        ViewCompat.OnUnhandledKeyEventListenerCompat { _, event ->
            handleDesktopKeyEvent(event)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge()
        applyDesktopImmersiveMode()

        ViewCompat.addOnUnhandledKeyEventListener(
            window.decorView,
            unhandledKeyListener,
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PocketPcApp(commandFlow = desktopCommands)
            }
        }
    }

    override fun onDestroy() {
        ViewCompat.removeOnUnhandledKeyEventListener(
            window.decorView,
            unhandledKeyListener,
        )
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyDesktopImmersiveMode()
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (handleDesktopKeyEvent(event)) return true
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleDesktopKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        data += KeyboardShortcutGroup(
            "PocketPC Desktop",
            listOf(
                KeyboardShortcutInfo(
                    "Arquivos",
                    KeyEvent.KEYCODE_E,
                    KeyEvent.META_META_ON,
                ),
                KeyboardShortcutInfo(
                    "Navegador",
                    KeyEvent.KEYCODE_B,
                    KeyEvent.META_META_ON,
                ),
                KeyboardShortcutInfo(
                    "Mostrar area de trabalho",
                    KeyEvent.KEYCODE_D,
                    KeyEvent.META_META_ON,
                ),
                KeyboardShortcutInfo(
                    "Terminal",
                    KeyEvent.KEYCODE_T,
                    KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON,
                ),
                KeyboardShortcutInfo(
                    "Alternar janelas",
                    KeyEvent.KEYCODE_TAB,
                    KeyEvent.META_ALT_ON,
                ),
                KeyboardShortcutInfo(
                    "Fechar janela ativa",
                    KeyEvent.KEYCODE_F4,
                    KeyEvent.META_ALT_ON,
                ),
            ),
        )
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.buttonState.and(MotionEvent.BUTTON_SECONDARY) != 0 &&
            desktopCommands.tryEmit(DesktopCommand.OPEN_DESKTOP_CONTEXT)
        ) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handleDesktopKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.isLongPress) {
            return false
        }

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

            event.keyCode == KeyEvent.KEYCODE_ESCAPE ->
                DesktopCommand.DISMISS_OVERLAYS

            else -> null
        }

        return command != null && desktopCommands.tryEmit(command)
    }

    @Suppress("DEPRECATION")
    private fun applyDesktopImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(
                    WindowInsets.Type.statusBars().or(
                        WindowInsets.Type.navigationBars()
                    )
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
