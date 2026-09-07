package dev.pocketpc.core.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.storage.PocketDriveDirectory
import dev.pocketpc.core.storage.PocketDriveMount
import dev.pocketpc.core.storage.StorageEntry
import dev.pocketpc.core.storage.StorageListing
import dev.pocketpc.core.storage.StorageRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesApp(
    repository: StorageRepository,
    rootUri: String?,
    pickerError: String?,
    onChooseStorage: () -> Unit,
    onDisconnectStorage: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var pathStack by remember(rootUri) {
        mutableStateOf(
            rootUri?.let { listOf(it) } ?: emptyList()
        )
    }
    val currentUri = pathStack.lastOrNull()

    var listing by remember(currentUri) {
        mutableStateOf<StorageListing?>(null)
    }
    var loading by remember(currentUri) {
        mutableStateOf(false)
    }
    var error by remember(currentUri) {
        mutableStateOf<String?>(null)
    }
    var driveMount by remember(rootUri) {
        mutableStateOf<PocketDriveMount?>(null)
    }
    var refreshToken by remember { mutableIntStateOf(0) }
    var query by remember(currentUri) { mutableStateOf("") }
    var selectedUri by remember(currentUri) {
        mutableStateOf<String?>(null)
    }
    var createFolderOpen by remember { mutableStateOf(false) }
    var createFolderName by remember { mutableStateOf("") }
    var renameOpen by remember { mutableStateOf(false) }
    var renameName by remember { mutableStateOf("") }
    var deleteOpen by remember { mutableStateOf(false) }

    val selected =
        listing?.entries
            ?.firstOrNull { it.uri == selectedUri }

    LaunchedEffect(rootUri) {
        driveMount = null
        if (rootUri != null) {
            repository.ensurePocketDrive(rootUri)
                .onSuccess { mount ->
                    driveMount = mount
                }
                .onFailure { failure ->
                    error =
                        failure.message
                            ?: "Falha ao preparar o PocketDrive."
                }
        }
    }

    LaunchedEffect(currentUri, refreshToken) {
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
                if (
                    selectedUri != null &&
                    it.entries.none { entry ->
                        entry.uri == selectedUri
                    }
                ) {
                    selectedUri = null
                }
            }
            .onFailure {
                listing = null
                error =
                    it.message
                        ?: "Falha ao listar esta pasta."
            }
        loading = false
    }

    fun refresh() {
        refreshToken++
    }

    fun openEntry(entry: StorageEntry) {
        if (entry.directory) {
            pathStack = pathStack + entry.uri
            selectedUri = null
        } else {
            repository.openFile(entry)
                .onFailure {
                    error =
                        it.message
                            ?: "Não foi possível abrir o arquivo."
                }
        }
    }

    if (rootUri == null) {
        EmptyExplorer(
            pickerError = pickerError,
            onChooseStorage = onChooseStorage,
        )
        return
    }

    val visibleEntries =
        remember(listing, query) {
            val normalized = query.trim()
            val entries = listing?.entries.orEmpty()
            if (normalized.isBlank()) {
                entries
            } else {
                entries.filter {
                    it.name.contains(
                        normalized,
                        ignoreCase = true,
                    )
                }
            }
        }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val showDetails = maxWidth >= 760.dp

        Row(Modifier.fillMaxSize()) {
            ExplorerSidebar(
                rootName =
                    if (pathStack.size == 1) {
                        listing?.directoryName
                    } else {
                        "PocketDrive"
                    } ?: "PocketDrive",
                entryCount = listing?.entries?.size ?: 0,
                mount = driveMount,
                onRoot = {
                    pathStack = listOf(rootUri)
                    selectedUri = null
                },
                onDirectory = { directory ->
                    driveMount
                        ?.uriFor(directory)
                        ?.let { uri ->
                            pathStack =
                                listOf(rootUri, uri)
                            selectedUri = null
                        }
                },
                onChangeRoot = onChooseStorage,
                onDisconnect = onDisconnectStorage,
            )

            VerticalDivider()

            Column(
                modifier = Modifier.weight(1f),
            ) {
                ExplorerToolbar(
                    canGoBack = pathStack.size > 1,
                    query = query,
                    pathLabel =
                        buildString {
                            append("Este PC")
                            append("  >  ")
                            append(
                                listing?.directoryName
                                    ?: "Pasta autorizada"
                            )
                        },
                    onQueryChange = { query = it },
                    onBack = {
                        if (pathStack.size > 1) {
                            pathStack =
                                pathStack.dropLast(1)
                            selectedUri = null
                        }
                    },
                    onRoot = {
                        pathStack = listOf(rootUri)
                        selectedUri = null
                    },
                    onRefresh = ::refresh,
                    onNewFolder = {
                        createFolderName = ""
                        createFolderOpen = true
                    },
                )

                if (loading) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth()
                    )
                }

                pickerError?.let {
                    ExplorerMessage(
                        text = it,
                        error = true,
                    )
                }
                error?.let {
                    ExplorerMessage(
                        text = it,
                        error = true,
                    )
                }

                FileTableHeader()

                if (
                    !loading &&
                    error == null &&
                    visibleEntries.isEmpty()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (query.isBlank()) {
                                "Esta pasta está vazia."
                            } else {
                                "Nenhum item corresponde à pesquisa."
                            },
                            style =
                                MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(
                            items = visibleEntries,
                            key = { it.uri },
                        ) { entry ->
                            FileTableRow(
                                entry = entry,
                                selected =
                                    entry.uri == selectedUri,
                                onSelect = {
                                    selectedUri = entry.uri
                                },
                                onOpen = {
                                    openEntry(entry)
                                },
                            )
                        }
                    }
                }

                Surface(
                    tonalElevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 10.dp,
                                vertical = 5.dp,
                            ),
                        horizontalArrangement =
                            Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${visibleEntries.size} item(ns)",
                            fontSize = 10.sp,
                        )
                        selected?.let {
                            Text(
                                if (it.directory) {
                                    "Pasta selecionada"
                                } else {
                                    formatBytes(it.size)
                                },
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            if (showDetails) {
                VerticalDivider()
                ExplorerDetailsPane(
                    entry = selected,
                    onOpen = {
                        selected?.let(::openEntry)
                    },
                    onRename = {
                        selected?.let {
                            renameName = it.name
                            renameOpen = true
                        }
                    },
                    onDelete = {
                        if (selected != null) {
                            deleteOpen = true
                        }
                    },
                )
            }
        }
    }

    if (createFolderOpen) {
        NameDialog(
            title = "Nova pasta",
            value = createFolderName,
            confirmLabel = "Criar",
            onValueChange = { createFolderName = it },
            onDismiss = { createFolderOpen = false },
            onConfirm = {
                val parent = currentUri
                if (parent != null) {
                    scope.launch {
                        repository.createDirectory(
                            parent,
                            createFolderName,
                        )
                            .onSuccess {
                                createFolderOpen = false
                                refresh()
                            }
                            .onFailure {
                                error =
                                    it.message
                                        ?: "Falha ao criar pasta."
                            }
                    }
                }
            },
        )
    }

    if (renameOpen && selected != null) {
        NameDialog(
            title = "Renomear",
            value = renameName,
            confirmLabel = "Salvar",
            onValueChange = { renameName = it },
            onDismiss = { renameOpen = false },
            onConfirm = {
                val entry = selected
                scope.launch {
                    repository.rename(entry, renameName)
                        .onSuccess {
                            renameOpen = false
                            selectedUri = null
                            refresh()
                        }
                        .onFailure {
                            error =
                                it.message
                                    ?: "Falha ao renomear."
                        }
                }
            },
        )
    }

    if (deleteOpen && selected != null) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Excluir item?") },
            text = {
                Text(
                    if (selected.directory) {
                        "A pasta “${selected.name}” e o conteúdo " +
                            "que o provedor permitir serão removidos."
                    } else {
                        "O arquivo “${selected.name}” será removido."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val entry = selected
                        scope.launch {
                            repository.delete(entry)
                                .onSuccess {
                                    deleteOpen = false
                                    selectedUri = null
                                    refresh()
                                }
                                .onFailure {
                                    error =
                                        it.message
                                            ?: "Falha ao excluir."
                                }
                        }
                    },
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteOpen = false }
                ) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
private fun EmptyExplorer(
    pickerError: String?,
    onChooseStorage: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Explorador do PocketPC",
                    style =
                        MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Escolha uma pasta do Android para ela aparecer " +
                        "como armazenamento do PocketPC."
                )
                Text(
                    "O acesso usa o seletor seguro do Android. " +
                        "O PocketPC não pede acesso irrestrito ao aparelho.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onChooseStorage,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Conectar armazenamento")
                }
                pickerError?.let {
                    Text(
                        it,
                        color =
                            MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExplorerSidebar(
    rootName: String,
    entryCount: Int,
    mount: PocketDriveMount?,
    onRoot: () -> Unit,
    onDirectory: (PocketDriveDirectory) -> Unit,
    onChangeRoot: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val quickDirectories =
        listOf(
            PocketDriveDirectory.DESKTOP,
            PocketDriveDirectory.DOCUMENTS,
            PocketDriveDirectory.DOWNLOADS,
            PocketDriveDirectory.APPLICATIONS,
            PocketDriveDirectory.GAMES,
            PocketDriveDirectory.PROJECTS,
        )

    Surface(
        modifier = Modifier
            .width(174.dp)
            .fillMaxHeight(),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(9.dp),
            verticalArrangement =
                Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "Explorador",
                style =
                    MaterialTheme.typography.titleMedium,
            )
            Text(
                "P:  PocketDrive",
                fontSize = 10.sp,
                color =
                    MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()

            TextButton(
                onClick = onRoot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                contentPadding =
                    PaddingValues(horizontal = 5.dp),
            ) {
                Text(
                    "▣  Este PC",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 10.sp,
                )
            }

            quickDirectories.forEach { directory ->
                TextButton(
                    onClick = {
                        onDirectory(directory)
                    },
                    enabled =
                        mount?.uriFor(directory) != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp),
                    contentPadding =
                        PaddingValues(horizontal = 5.dp),
                ) {
                    Text(
                        "  ${directory.displayName}",
                        modifier = Modifier.fillMaxWidth(),
                        fontSize = 9.sp,
                        maxLines = 1,
                    )
                }
            }

            HorizontalDivider()

            Text(
                rootName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 9.sp,
            )
            Text(
                "$entryCount item(ns)",
                fontSize = 8.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick = onChangeRoot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                contentPadding =
                    PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    "Trocar PocketDrive",
                    fontSize = 8.sp,
                )
            }
            TextButton(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                contentPadding =
                    PaddingValues(horizontal = 4.dp),
            ) {
                Text(
                    "Desconectar P:",
                    fontSize = 8.sp,
                )
            }
        }
    }
}

@Composable
private fun ExplorerToolbar(
    canGoBack: Boolean,
    query: String,
    pathLabel: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onRoot: () -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 8.dp,
                vertical = 6.dp,
            ),
            verticalArrangement =
                Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(5.dp),
            ) {
                SmallExplorerButton(
                    "←",
                    enabled = canGoBack,
                    onClick = onBack,
                )
                SmallExplorerButton(
                    "⌂",
                    onClick = onRoot,
                )
                SmallExplorerButton(
                    "↻",
                    onClick = onRefresh,
                )
                SmallExplorerButton(
                    "+ Pasta",
                    wide = true,
                    onClick = onNewFolder,
                )

                Text(
                    pathLabel,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .widthIn(min = 150.dp, max = 250.dp)
                        .heightIn(max = 46.dp),
                    placeholder = {
                        Text("Pesquisar")
                    },
                    singleLine = true,
                    textStyle =
                        LocalTextStyle.current.copy(
                            fontSize = 11.sp
                        ),
                )
            }
        }
    }
}

@Composable
private fun SmallExplorerButton(
    label: String,
    enabled: Boolean = true,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = if (wide) 66.dp else 38.dp),
        contentPadding = PaddingValues(
            horizontal = if (wide) 7.dp else 3.dp,
        ),
    ) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun FileTableHeader() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 10.dp,
                    vertical = 6.dp,
                ),
        ) {
            Text(
                "Nome",
                modifier = Modifier.weight(1f),
                fontSize = 10.sp,
            )
            Text(
                "Tipo",
                modifier = Modifier.width(110.dp),
                fontSize = 10.sp,
            )
            Text(
                "Tamanho",
                modifier = Modifier.width(82.dp),
                fontSize = 10.sp,
            )
            Text(
                "Modificado",
                modifier = Modifier.width(120.dp),
                fontSize = 10.sp,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTableRow(
    entry: StorageEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme
                        .secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .combinedClickable(
                onClick = onSelect,
                onDoubleClick = onOpen,
            )
            .padding(
                horizontal = 10.dp,
                vertical = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (entry.directory) "▣" else "•",
                modifier = Modifier.width(24.dp),
            )
            Text(
                entry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
            )
        }
        Text(
            if (entry.directory) {
                "Pasta"
            } else {
                entry.mimeType
                    ?.substringAfterLast('/')
                    ?.ifBlank { "Arquivo" }
                    ?: "Arquivo"
            },
            modifier = Modifier.width(110.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp,
        )
        Text(
            if (entry.directory) "—"
            else formatBytes(entry.size),
            modifier = Modifier.width(82.dp),
            fontSize = 10.sp,
        )
        Text(
            formatModified(entry.lastModified),
            modifier = Modifier.width(120.dp),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ExplorerDetailsPane(
    entry: StorageEntry?,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight(),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Detalhes",
                style =
                    MaterialTheme.typography.titleMedium,
            )
            HorizontalDivider()

            if (entry == null) {
                Text(
                    "Selecione um item para ver informações.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            Text(
                if (entry.directory) "▣" else "●",
                fontSize = 34.sp,
            )
            Text(
                entry.name,
                style =
                    MaterialTheme.typography.titleSmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            ValueRow(
                "Tipo",
                if (entry.directory) {
                    "Pasta"
                } else {
                    entry.mimeType ?: "Arquivo"
                },
            )
            if (!entry.directory) {
                ValueRow(
                    "Tamanho",
                    formatBytes(entry.size),
                )
            }
            ValueRow(
                "Modificado",
                formatModified(entry.lastModified),
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (entry.directory) {
                        "Abrir pasta"
                    } else {
                        "Abrir arquivo"
                    }
                )
            }
            OutlinedButton(
                onClick = onRename,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Renomear")
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Excluir")
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    confirmLabel: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Nome") },
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = value.trim().isNotEmpty(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun ExplorerMessage(
    text: String,
    error: Boolean,
) {
    Text(
        text,
        modifier = Modifier.padding(
            horizontal = 10.dp,
            vertical = 4.dp,
        ),
        color =
            if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        fontSize = 10.sp,
    )
}

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1_073_741_824L ->
            String.format(
                Locale.ROOT,
                "%.1f GB",
                bytes / 1_073_741_824.0,
            )
        bytes >= 1_048_576L ->
            String.format(
                Locale.ROOT,
                "%.1f MB",
                bytes / 1_048_576.0,
            )
        bytes >= 1_024L ->
            String.format(
                Locale.ROOT,
                "%.1f KB",
                bytes / 1_024.0,
            )
        else -> "$bytes B"
    }

private fun formatModified(epochMillis: Long): String {
    if (epochMillis <= 0L) return "—"
    return runCatching {
        DateTimeFormatter
            .ofPattern("dd/MM/yy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    }.getOrDefault("—")
}
