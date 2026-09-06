package dev.pocketpc.core.feature.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TerminalEntry(
    val prompt: String,
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
)

@Composable
fun TerminalPane() {
    val context = LocalContext.current
    val shell = remember { SandboxShell(context.applicationContext) }
    val history = remember { mutableStateListOf<TerminalEntry>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var command by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun submit() {
        val submitted = command.trim()
        if (submitted.isEmpty() || running) return

        command = ""

        if (submitted == "clear") {
            history.clear()
            return
        }

        running = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                shell.execute(submitted)
            }
            history += TerminalEntry(
                prompt = "${result.cwd} $ ${result.command}",
                output = result.output,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
            )
            while (history.size > 100) history.removeAt(0)
            running = false
        }
    }

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            listState.animateScrollToItem(history.lastIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Pocket Terminal", style = MaterialTheme.typography.titleMedium)
        Text(
            "Shell real do sandbox Android. Não é Linux rootfs e não possui PTY nesta versão.",
            style = MaterialTheme.typography.bodySmall,
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history) { entry ->
                Column {
                    Text(entry.prompt, fontFamily = FontFamily.Monospace)
                    Text(entry.output, fontFamily = FontFamily.Monospace)
                    if (entry.exitCode != null && entry.exitCode != 0) {
                        Text("exit=${entry.exitCode}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (entry.timedOut) {
                        Text("TIMEOUT", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                enabled = !running,
                singleLine = true,
                label = { Text(if (running) "Executando…" else "Comando") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Button(
                onClick = { submit() },
                enabled = command.isNotBlank() && !running,
            ) {
                Text("Executar")
            }
        }
    }
}
