package dev.pocketpc.core.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.storage.PocketDriveDirectory
import dev.pocketpc.core.storage.PocketFileClass
import dev.pocketpc.core.storage.PocketPcPackageRecord
import dev.pocketpc.core.storage.PocketPcPackageRegistry
import dev.pocketpc.core.storage.PocketPcPackageState
import dev.pocketpc.core.storage.StorageEntry
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.storage.classifyPocketFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PocketDownload(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val mimeType: String?,
)

@Composable
fun DownloadsApp(
    repository: StorageRepository,
    rootUri: String?,
) {
    val context = LocalContext.current
    val manager =
        remember {
            context.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager
        }

    var activeDownloads by remember {
        mutableStateOf<List<PocketDownload>>(
            emptyList()
        )
    }
    var pocketFiles by remember {
        mutableStateOf<List<StorageEntry>>(
            emptyList()
        )
    }
    var pcPackages by remember {
        mutableStateOf<List<PocketPcPackageRecord>>(
            emptyList()
        )
    }
    var statusMessage by remember {
        mutableStateOf<String?>(null)
    }
    var refreshToken by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(
        refreshToken,
        rootUri,
    ) {
        while (true) {
            activeDownloads =
                withContext(Dispatchers.IO) {
                    queryDownloads(manager)
                }

            pocketFiles =
                if (rootUri != null) {
                    repository
                        .pocketDirectoryUri(
                            PocketDriveDirectory.DOWNLOADS
                        )
                        .getOrNull()
                        ?.let { uri ->
                            repository
                                .list(uri)
                                .getOrNull()
                                ?.entries
                                .orEmpty()
                        }
                        .orEmpty()
                } else {
                    emptyList()
                }

            pcPackages =
                withContext(Dispatchers.IO) {
                    PocketPcPackageRegistry(
                        context
                    ).records()
                }

            delay(2_000)
        }
    }

    val regularPocketFiles =
        pocketFiles.filterNot { entry ->
            !entry.directory &&
                classifyPocketFile(entry.name) ==
                    PocketFileClass.PC_INSTALLER
        }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Downloads",
                    style =
                        MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (rootUri != null) {
                        "Destino padrão: P:\\Downloads"
                    } else {
                        "PocketDrive ainda não conectado"
                    },
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = { refreshToken++ },
            ) {
                Text("Atualizar")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                DownloadManager.ACTION_VIEW_DOWNLOADS
                            ).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                )
                            }
                        )
                    }
                },
            ) {
                Text("Fila Android")
            }
        }

        statusMessage?.let {
            Text(
                it,
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        if (
            activeDownloads.isEmpty() &&
            regularPocketFiles.isEmpty() &&
            pcPackages.isEmpty()
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (rootUri != null) {
                        "P:\\Downloads está vazio."
                    } else {
                        "Conecte um PocketDrive para usar " +
                            "o armazenamento do PC."
                    },
                    style =
                        MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement =
                    Arrangement.spacedBy(5.dp),
            ) {
                if (activeDownloads.isNotEmpty()) {
                    item {
                        DownloadSectionHeader(
                            title = "Em andamento",
                            subtitle =
                                "DownloadManager → PocketDrive",
                        )
                    }

                    items(
                        items = activeDownloads,
                        key = { "active-${it.id}" },
                    ) { item ->
                        DownloadRow(
                            item = item,
                            onOpen = {
                                val uri =
                                    manager
                                        .getUriForDownloadedFile(
                                            item.id
                                        )
                                if (uri == null) {
                                    statusMessage =
                                        "O arquivo ainda não está disponível."
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW
                                            ).apply {
                                                setDataAndType(
                                                    uri,
                                                    manager
                                                        .getMimeTypeForDownloadedFile(
                                                            item.id
                                                        )
                                                        ?: item.mimeType
                                                        ?: "*/*",
                                                )
                                                addFlags(
                                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                )
                                                addFlags(
                                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                                )
                                            }
                                        )
                                    }.onFailure { error ->
                                        statusMessage =
                                            "Falha ao abrir: " +
                                                (
                                                    error.message
                                                        ?: error
                                                            .javaClass
                                                            .simpleName
                                                    )
                                    }
                                }
                            },
                            onRemove = {
                                val removed =
                                    manager.remove(item.id)
                                statusMessage =
                                    if (removed > 0) {
                                        "Download cancelado/removido."
                                    } else {
                                        "Não foi possível remover o download."
                                    }
                                refreshToken++
                            },
                        )
                    }
                }

                if (pcPackages.isNotEmpty()) {
                    item {
                        DownloadSectionHeader(
                            title = "Programas de PC detectados",
                            subtitle =
                                "${pcPackages.size} pacote(s)",
                        )
                    }

                    items(
                        items = pcPackages,
                        key = {
                            "pc-package-${it.uri}"
                        },
                    ) { record ->
                        PcPackageRow(record)
                    }
                }

                if (regularPocketFiles.isNotEmpty()) {
                    item {
                        DownloadSectionHeader(
                            title = "P:\\Downloads",
                            subtitle =
                                "${pocketFiles.size} item(ns) persistentes",
                        )
                    }

                    items(
                        items = regularPocketFiles,
                        key = { "drive-${it.uri}" },
                    ) { entry ->
                        PocketDriveDownloadRow(
                            entry = entry,
                            onOpen = {
                                if (!entry.directory) {
                                    repository
                                        .openFile(entry)
                                        .onFailure { error ->
                                            statusMessage =
                                                error.message
                                                    ?: "Falha ao abrir."
                                        }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadSectionHeader(
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 6.dp,
                bottom = 2.dp,
            ),
        horizontalArrangement =
            Arrangement.SpaceBetween,
        verticalAlignment =
            Alignment.CenterVertically,
    ) {
        Text(
            title,
            style =
                MaterialTheme.typography.titleSmall,
        )
        Text(
            subtitle,
            fontSize = 8.sp,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
        )
    }
}

@Composable
private fun PcPackageRow(
    record: PocketPcPackageRecord,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color =
                    MaterialTheme.colorScheme
                        .primaryContainer,
            ) {
                Box(
                    contentAlignment =
                        Alignment.Center,
                ) {
                    Text(
                        "PC",
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement =
                    Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    record.name,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
                Text(
                    "P:\\Downloads • " +
                        formatBytes(record.size),
                    fontSize = 9.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
                Text(
                    when (record.state) {
                        PocketPcPackageState.STORED ->
                            "Armazenado"
                        PocketPcPackageState.RUNTIME_REQUIRED ->
                            "Aguardando runtime Windows"
                    },
                    fontSize = 9.sp,
                    color =
                        MaterialTheme.colorScheme
                            .primary,
                )
            }

            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "Runtime necessário",
                        fontSize = 8.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun PocketDriveDownloadRow(
    entry: StorageEntry,
    onOpen: () -> Unit,
) {
    val fileClass =
        if (entry.directory) {
            PocketFileClass.GENERIC
        } else {
            classifyPocketFile(entry.name)
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (fileClass) {
                    PocketFileClass.PC_INSTALLER -> "PC"
                    PocketFileClass.ANDROID_PACKAGE -> "APK"
                    PocketFileClass.ARCHIVE -> "ZIP"
                    PocketFileClass.DISK_IMAGE -> "IMG"
                    PocketFileClass.GENERIC -> "↓"
                },
                fontSize = 11.sp,
            )

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    entry.name,
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
                Text(
                    pocketFileClassLabel(
                        fileClass
                    ) +
                        if (!entry.directory) {
                            " • " +
                                formatBytes(entry.size)
                        } else {
                            ""
                        },
                    fontSize = 9.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            if (!entry.directory) {
                Button(onClick = onOpen) {
                    Text("Abrir")
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: PocketDownload,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val progress =
        if (item.totalBytes > 0L) {
            (
                item.bytesDownloaded.toFloat() /
                    item.totalBytes.toFloat()
            ).coerceIn(0f, 1f)
        } else {
            null
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            Text("↓", fontSize = 22.sp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement =
                    Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    item.title.ifBlank {
                        "Download"
                    },
                    maxLines = 1,
                    overflow =
                        TextOverflow.Ellipsis,
                )
                Text(
                    downloadStatusLabel(item.status),
                    fontSize = 10.sp,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )

                if (
                    item.status ==
                        DownloadManager.STATUS_RUNNING &&
                    progress != null
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier =
                            Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    when {
                        item.totalBytes > 0L ->
                            formatBytes(
                                item.bytesDownloaded
                            ) +
                                " / " +
                                formatBytes(
                                    item.totalBytes
                                )
                        item.bytesDownloaded > 0L ->
                            formatBytes(
                                item.bytesDownloaded
                            )
                        else ->
                            "Tamanho desconhecido"
                    },
                    fontSize = 9.sp,
                )
            }

            if (
                item.status ==
                DownloadManager.STATUS_SUCCESSFUL
            ) {
                Button(onClick = onOpen) {
                    Text("Abrir")
                }
            }

            TextButton(onClick = onRemove) {
                Text("Remover")
            }
        }
    }
}

private fun queryDownloads(
    manager: DownloadManager,
): List<PocketDownload> {
    val result =
        mutableListOf<PocketDownload>()
    val cursor =
        manager.query(DownloadManager.Query())

    cursor?.use {
        val idIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_ID
            )
        val titleIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_TITLE
            )
        val statusIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_STATUS
            )
        val downloadedIndex =
            it.getColumnIndex(
                DownloadManager
                    .COLUMN_BYTES_DOWNLOADED_SO_FAR
            )
        val totalIndex =
            it.getColumnIndex(
                DownloadManager
                    .COLUMN_TOTAL_SIZE_BYTES
            )
        val mimeIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_MEDIA_TYPE
            )

        while (it.moveToNext()) {
            if (
                idIndex < 0 ||
                statusIndex < 0
            ) {
                continue
            }

            result +=
                PocketDownload(
                    id = it.getLong(idIndex),
                    title =
                        if (titleIndex >= 0) {
                            it.getString(titleIndex)
                                .orEmpty()
                        } else {
                            "Download"
                        },
                    status =
                        it.getInt(statusIndex),
                    bytesDownloaded =
                        if (
                            downloadedIndex >= 0
                        ) {
                            it.getLong(
                                downloadedIndex
                            )
                        } else {
                            0L
                        },
                    totalBytes =
                        if (totalIndex >= 0) {
                            it.getLong(totalIndex)
                        } else {
                            -1L
                        },
                    mimeType =
                        if (mimeIndex >= 0) {
                            it.getString(mimeIndex)
                        } else {
                            null
                        },
                )
        }
    }

    return result.sortedByDescending { it.id }
}

private fun downloadStatusLabel(
    status: Int,
): String =
    when (status) {
        DownloadManager.STATUS_PENDING ->
            "Aguardando"
        DownloadManager.STATUS_RUNNING ->
            "Baixando"
        DownloadManager.STATUS_PAUSED ->
            "Pausado"
        DownloadManager.STATUS_SUCCESSFUL ->
            "Concluído / importando"
        DownloadManager.STATUS_FAILED ->
            "Falhou"
        else ->
            "Status desconhecido"
    }

private fun pocketFileClassLabel(
    fileClass: PocketFileClass,
): String =
    when (fileClass) {
        PocketFileClass.PC_INSTALLER ->
            "Pacote de PC"
        PocketFileClass.ANDROID_PACKAGE ->
            "Pacote Android"
        PocketFileClass.ARCHIVE ->
            "Arquivo compactado"
        PocketFileClass.DISK_IMAGE ->
            "Imagem de disco"
        PocketFileClass.GENERIC ->
            "Arquivo"
    }
