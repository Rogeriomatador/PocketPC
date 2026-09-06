package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.terminal.LocalShellEngine
import dev.pocketpc.core.terminal.TerminalRecord
import kotlinx.coroutines.launch

@Composable
fun TerminalApp(engine: LocalShellEngine) {
    val scope = rememberCoroutineScope()
    val records = remember { mutableStateListOf<TerminalRecord>() }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }

    fun submit() {
        val command = input.trim()
        if (command.isEmpty() || running) return
        input = ""

        if (command == "clear") {
            records.clear()
            return
        }

        val prompt = promptFor(engine.workingDirectory.path)
        running = true
        scope.launch {
            val result = engine.execute(command)
            records += TerminalRecord(
                prompt = prompt,
                command = result.command,
                output = result.output,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
            )
            running = false
            if (records.isNotEmpty()) listState.animateScrollToItem(records.lastIndex)
            focusRequester.requestFocus()
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            Column(Modifier.weight(1f)) {
                Text("Pocket Terminal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Shell local Android • app UID • sem root • timeout 8 s",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { records.clear() }) { Text("Limpar") }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Digite help para ver os comandos internos.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            items(records.size) { index ->
                val record = records[index]
                Column {
                    Text(
                        "${record.prompt} ${record.command}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (record.output.isNotBlank()) {
                        Text(
                            record.output,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (record.timedOut || (record.exitCode != null && record.exitCode != 0)) {
                        Text(
                            if (record.timedOut) "[timeout]" else "[exit ${record.exitCode}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !running,
            singleLine = true,
            label = { Text(if (running) "Executando…" else promptFor(engine.workingDirectory.path)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                TextButton(onClick = { submit() }, enabled = !running && input.isNotBlank()) {
                    Text("Executar")
                }
            },
        )
    }
}

private fun promptFor(path: String): String {
    val tail = path.substringAfterLast('/').ifBlank { "/" }
    return "pocket:$tail$"
}
