package dev.pocketpc.core.feature.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FilesPane() {
    val context = LocalContext.current
    val repository = remember { SafFileRepository(context.applicationContext) }

    var treeUriText by remember { mutableStateOf(repository.loadTreeUri()?.toString()) }
    var currentDocumentUriText by remember { mutableStateOf<String?>(null) }
    val backStack = remember { mutableStateListOf<String?>() }
    var entries by remember { mutableStateOf<List<SafEntry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var refreshGeneration by remember { mutableIntStateOf(0) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val persisted = repository.persistTree(uri)
            if (persisted.isSuccess) {
                treeUriText = uri.toString()
                currentDocumentUriText = null
                backStack.clear()
                status = "Pasta conectada."
                refreshGeneration++
            } else {
                status = "Não foi possível manter acesso: ${persisted.exceptionOrNull()?.message}"
            }
        }
    }

    LaunchedEffect(treeUriText, currentDocumentUriText, refreshGeneration) {
        val treeText = treeUriText
        if (treeText == null) {
            entries = emptyList()
            return@LaunchedEffect
        }

        status = "Lendo…"
        val result = withContext(Dispatchers.IO) {
            runCatching {
                repository.listChildren(
                    treeUri = Uri.parse(treeText),
                    documentUri = currentDocumentUriText?.let(Uri::parse),
                )
            }
        }

        result.onSuccess {
            entries = it
            status = "${it.size} item(ns)"
        }.onFailure {
            entries = emptyList()
            status = "Falha ao ler: ${it.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Explorador PocketPC", style = MaterialTheme.typography.titleMedium)
        Text(
            "Acesso real via Storage Access Framework: você escolhe quais pastas o PocketPC pode acessar.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { folderPicker.launch(null) }) {
                Text(if (treeUriText == null) "Escolher pasta" else "Trocar pasta")
            }

            OutlinedButton(
                enabled = backStack.isNotEmpty(),
                onClick = {
                    if (backStack.isNotEmpty()) {
                        currentDocumentUriText = backStack.removeAt(backStack.lastIndex)
                    }
                },
            ) {
                Text("Voltar")
            }

            OutlinedButton(
                enabled = treeUriText != null,
                onClick = { refreshGeneration++ },
            ) {
                Text("Atualizar")
            }
        }

        if (treeUriText != null) {
            Text(
                currentDocumentUriText ?: "Raiz concedida pelo usuário",
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }

        HorizontalDivider()

        if (treeUriText == null) {
            Text(
                "Nenhuma pasta conectada. Toque em “Escolher pasta”. O Android continuará controlando os limites de acesso.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(entries, key = { it.documentId }) { entry ->
                    FileRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                backStack += currentDocumentUriText
                                currentDocumentUriText = entry.uri
                            } else {
                                repository.openExternal(entry)
                                    .onFailure { status = "Sem app para abrir: ${it.message}" }
                            }
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    repository.forgetTree()
                    treeUriText = null
                    currentDocumentUriText = null
                    backStack.clear()
                    entries = emptyList()
                    status = "Acesso persistente removido."
                },
            ) {
                Text("Desconectar pasta")
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: SafEntry,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (entry.isDirectory) "📁" else "📄")
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1)
            if (!entry.isDirectory) {
                Text(
                    formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0L) return "tamanho desconhecido"
    if (bytes < 1024L) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.1f %s".format(value, units[index])
}
