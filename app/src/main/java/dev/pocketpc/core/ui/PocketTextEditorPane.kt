package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.MAX_EDITABLE_TEXT_BYTES
import dev.pocketpc.core.storage.PocketFileOpenRequest
import dev.pocketpc.core.storage.PocketTextDocument
import dev.pocketpc.core.storage.loadPocketTextDocument
import dev.pocketpc.core.storage.savePocketTextDocument
import kotlinx.coroutines.launch

private sealed interface PocketTextEditorState {
    data object Loading : PocketTextEditorState
    data class Ready(val document: PocketTextDocument) : PocketTextEditorState
    data class Failed(val message: String) : PocketTextEditorState
}

private sealed interface PocketTextSaveState {
    data object Idle : PocketTextSaveState
    data object Saving : PocketTextSaveState
    data class Saved(val bytes: Int) : PocketTextSaveState
    data class Failed(val message: String) : PocketTextSaveState
}

@Composable
fun PocketTextEditorPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = request.uri

    var editorState by remember(uri) {
        mutableStateOf<PocketTextEditorState>(PocketTextEditorState.Loading)
    }
    var text by remember(uri) { mutableStateOf("") }
    var originalText by remember(uri) { mutableStateOf("") }
    var saveState by remember(uri) {
        mutableStateOf<PocketTextSaveState>(PocketTextSaveState.Idle)
    }

    LaunchedEffect(uri) {
        saveState = PocketTextSaveState.Idle
        if (uri == null) {
            editorState = PocketTextEditorState.Failed(
                "O PocketPC não recebeu acesso ao arquivo."
            )
            return@LaunchedEffect
        }

        editorState = PocketTextEditorState.Loading
        loadPocketTextDocument(context, uri)
            .onSuccess { document ->
                text = document.text
                originalText = document.text
                editorState = PocketTextEditorState.Ready(document)
            }
            .onFailure { error ->
                editorState = PocketTextEditorState.Failed(
                    "Não foi possível ler o arquivo dentro do PocketPC: " +
                        (error.message ?: error.javaClass.simpleName)
                )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val state = editorState) {
            PocketTextEditorState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Abrindo no Editor de Texto do PocketPC...")
                }

            is PocketTextEditorState.Failed ->
                Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketTextEditorState.Ready -> {
                val document = state.document
                if (document.truncated) {
                    Text(
                        "Arquivo acima de 512 KiB: prévia em somente leitura para impedir truncamento acidental.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        if (
                            document.editable &&
                            value.toByteArray(Charsets.UTF_8).size <= MAX_EDITABLE_TEXT_BYTES
                        ) {
                            text = value
                            saveState = PocketTextSaveState.Idle
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp),
                    readOnly = !document.editable,
                    label = {
                        Text(
                            if (document.editable) {
                                "Conteúdo"
                            } else {
                                "Prévia somente leitura"
                            }
                        )
                    },
                    supportingText = {
                        Text(
                            if (document.editable) {
                                "${text.toByteArray(Charsets.UTF_8).size} / $MAX_EDITABLE_TEXT_BYTES bytes"
                            } else {
                                "Primeiros ${document.bytesRead} bytes exibidos"
                            }
                        )
                    },
                )

                when (val save = saveState) {
                    PocketTextSaveState.Idle -> Unit
                    PocketTextSaveState.Saving ->
                        Text(
                            "Salvando dentro do PocketDrive...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    is PocketTextSaveState.Saved ->
                        Text(
                            "Salvo no PocketDrive (${save.bytes} bytes).",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    is PocketTextSaveState.Failed ->
                        Text(
                            save.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                }

                if (document.editable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled =
                                text != originalText &&
                                    saveState !is PocketTextSaveState.Saving,
                            onClick = {
                                val targetUri = uri ?: return@Button
                                saveState = PocketTextSaveState.Saving
                                scope.launch {
                                    savePocketTextDocument(
                                        context = context,
                                        uriString = targetUri,
                                        text = text,
                                    ).onSuccess { bytes ->
                                        originalText = text
                                        saveState = PocketTextSaveState.Saved(bytes)
                                    }.onFailure { error ->
                                        saveState = PocketTextSaveState.Failed(
                                            "Falha ao salvar: " +
                                                (error.message ?: error.javaClass.simpleName)
                                        )
                                    }
                                }
                            },
                        ) {
                            Text("Salvar")
                        }
                    }
                }
            }
        }
    }
}
