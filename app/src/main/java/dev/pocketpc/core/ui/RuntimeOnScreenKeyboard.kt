package dev.pocketpc.core.ui

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RuntimeOnScreenKeyboard(
    enabled: Boolean,
    onKey: (Int, Boolean, Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var numbers by remember { mutableStateOf(false) }

    fun press(code: Int) = onKey(code, shift, control, alt)
    Surface(modifier, shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp), shadowElevation = 12.dp) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (enabled) "Teclado PC" else "Selecione uma janela Windows", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onClose, contentPadding = PaddingValues(4.dp)) { Text("Fechar") }
            }
            val rows = if (numbers) listOf("1234567890", "-=[];'/.,", "\\`") else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { character ->
                        val code = when (character) {
                            in 'a'..'z' -> KeyEvent.KEYCODE_A + (character - 'a')
                            in '1'..'9' -> KeyEvent.KEYCODE_1 + (character - '1')
                            '0' -> KeyEvent.KEYCODE_0
                            '-' -> KeyEvent.KEYCODE_MINUS
                            '=' -> KeyEvent.KEYCODE_EQUALS
                            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                            ';' -> KeyEvent.KEYCODE_SEMICOLON
                            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
                            '/' -> KeyEvent.KEYCODE_SLASH
                            '.' -> KeyEvent.KEYCODE_PERIOD
                            ',' -> KeyEvent.KEYCODE_COMMA
                            '\\' -> KeyEvent.KEYCODE_BACKSLASH
                            else -> KeyEvent.KEYCODE_GRAVE
                        }
                        val shifted = "!@#\$%^&*()_+{}:\"?><|~"
                        val plain = "1234567890-=[];'/.,\\`"
                        val label = if (!shift) character.toString() else if (character.isLetter()) character.uppercase()
                            else plain.indexOf(character).takeIf { it >= 0 }?.let { shifted[it].toString() } ?: character.toString()
                        RuntimeTouchKey(label, enabled,
                            Modifier.weight(1f)) { press(code) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                RuntimeTouchKey(if (numbers) "ABC" else "123", enabled, Modifier.weight(1f)) { numbers = !numbers }
                RuntimeTouchKey("Shift", enabled, Modifier.weight(1f), shift) { shift = !shift }
                RuntimeTouchKey("Ctrl", enabled, Modifier.weight(1f), control) { control = !control }
                RuntimeTouchKey("Alt", enabled, Modifier.weight(1f), alt) { alt = !alt }
                RuntimeTouchKey("⌫", enabled, Modifier.weight(1f)) { press(KeyEvent.KEYCODE_DEL) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                RuntimeTouchKey("Esc", enabled, Modifier.weight(1f)) { press(KeyEvent.KEYCODE_ESCAPE) }
                RuntimeTouchKey("Tab", enabled, Modifier.weight(1f)) { press(KeyEvent.KEYCODE_TAB) }
                RuntimeTouchKey("Espaço", enabled, Modifier.weight(2f)) { press(KeyEvent.KEYCODE_SPACE) }
                RuntimeTouchKey("Enter", enabled, Modifier.weight(1f)) { press(KeyEvent.KEYCODE_ENTER) }
            }
        }
    }
}

@Composable
private fun RuntimeTouchKey(label: String, enabled: Boolean, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    Surface(modifier.height(36.dp), shape = RoundedCornerShape(4.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box(Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
