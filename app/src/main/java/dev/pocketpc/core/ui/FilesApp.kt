package dev.pocketpc.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.StorageEntry
import dev.pocketpc.core.storage.StorageListing
import dev.pocketpc.core.storage.StorageRepository
import java.util.Locale

@Composable
fun FilesApp(
    repository: StorageRepository,
    rootUri: String?,
    pickerError: String?,
    onChooseStorage: () -> Unit,
    onDisconnectStorage: () -> Unit,
) {
    var pathStack by remember(rootUri) {
        mutableStateOf(rootUri?.let { listOf(it) } ?: emptyList())
    }
    val currentUri = pathStack.lastOrNull()
    var listing by remember(currentUri) { mutableStateOf<StorageListing?>(null) }
    var loading by remember(currentUri) { mutableStateOf(false) }
    var error by remember(currentUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(currentUri) {
        if (currentUri == null) {
            listing = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        repository.list(currentUri)
            .onSuccess {
                listing = it
                error = null
            }
            .onFailure {
                listing = null
                error = it.message ?: "Falha ao listar esta pasta."
            }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Explorador", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (pathStack.size > 1) {
                OutlinedButton(onClick = { pathStack = pathStack.dropLast(1) }) { Text("Voltar") }
            }
            Button(onClick = onChooseStorage) {
                Text(if (rootUri == null) "Escolher pasta" else "Trocar raiz")
            }
        }

        if (rootUri == null) {
            Text("Escolha uma pasta do Android para dar acesso persistente ao PocketPC.")
            Text(
                "O acesso usa o Storage Access Framework; o PocketPC não solicita acesso irrestrito ao armazenamento.",
                style = MaterialTheme.typography.bodySmall,
            )
            pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listing?.directoryName ?: "Pasta autorizada",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnectStorage) { Text("Desconectar") }
        }

        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val entries = listing?.entries.orEmpty()
            if (!loading && error == null && entries.isEmpty()) {
                item { Text("Pasta vazia.", style = MaterialTheme.typography.bodySmall) }
            }
            items(entries, key = { it.uri }) { entry ->
                FileRow(entry) {
                    if (entry.directory) {
                        pathStack = pathStack + entry.uri
                    } else {
                        repository.openFile(entry).onFailure {
                            error = it.message ?: "Não foi possível abrir o arquivo."
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: StorageEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (entry.directory) "▣" else "•", modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1)
            if (!entry.directory) {
                Text(
                    listOfNotNull(entry.mimeType, formatBytes(entry.size)).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
