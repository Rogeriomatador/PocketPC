package dev.pocketpc.core.ui

import android.view.KeyEvent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun RuntimeTouchGameControls(
    windowId: Long?,
    onKey: (Long, Int, Boolean, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnKey by rememberUpdatedState(onKey)
    Surface(modifier, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), shadowElevation = 12.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (windowId != null) "Segure para mover • combine os botões" else "Selecione uma janela Windows",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onClose) { Text("Fechar") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeHeldTouchKey("W", KeyEvent.KEYCODE_W, windowId, latestOnKey)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RuntimeHeldTouchKey("A", KeyEvent.KEYCODE_A, windowId, latestOnKey)
                        RuntimeHeldTouchKey("S", KeyEvent.KEYCODE_S, windowId, latestOnKey)
                        RuntimeHeldTouchKey("D", KeyEvent.KEYCODE_D, windowId, latestOnKey)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeHeldTouchKey("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, windowId, latestOnKey, Modifier.width(96.dp))
                    RuntimeHeldTouchKey("Espaço", KeyEvent.KEYCODE_SPACE, windowId, latestOnKey, Modifier.width(96.dp))
                }
            }
        }
    }
}

@Composable
private fun RuntimeHeldTouchKey(label: String, code: Int, windowId: Long?, onKey: (Long, Int, Boolean, String) -> Unit,
                                modifier: Modifier = Modifier.width(48.dp)) {
    var pressed by remember(windowId) { mutableStateOf(false) }
    val latestOnKey by rememberUpdatedState(onKey)
    Surface(modifier.height(48.dp), shape = RoundedCornerShape(8.dp),
        color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = "Segurar $label" }
            .pointerInput(windowId, code) {
                val targetId = windowId ?: return@pointerInput
                detectTapGestures(onPress = {
                    val owner = "touch:" + java.util.UUID.randomUUID().toString()
                    pressed = true
                    try {
                        latestOnKey(targetId, code, true, owner)
                        tryAwaitRelease()
                    } finally {
                        pressed = false
                        latestOnKey(targetId, code, false, owner)
                    }
                })
            }, contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
