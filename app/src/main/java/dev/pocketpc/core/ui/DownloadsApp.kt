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
fun DownloadsApp() {
    val context = LocalContext.current
    val manager =
        remember {
            context.getSystemService(
                Context.DOWNLOAD_SERVICE
            ) as DownloadManager
        }

    var downloads by remember {
        mutableStateOf<List<PocketDownload>>(
            emptyList()
        )
    }
    var statusMessage by remember {
        mutableStateOf<String?>(null)
    }
    var refreshToken by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(refreshToken) {
        while (true) {
            downloads =
                withContext(Dispatchers.IO) {
                    queryDownloads(manager)
                }
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Arquivos baixados pelo PocketPC",
                    style = MaterialTheme.typography.bodySmall,
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
                Text("Gerenciador Android")
            }
        }

        HorizontalDivider()

        statusMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nenhum download do PocketPC encontrado.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(
                    items = downloads,
                    key = { it.id },
                ) { item ->
                    DownloadRow(
                        item = item,
                        onOpen = {
                            val uri =
                                manager.getUriForDownloadedFile(
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
                                                    ?: error.javaClass.simpleName
                                            )
                                }
                            }
                        },
                        onRemove = {
                            val removed =
                                manager.remove(item.id)
                            statusMessage =
                                if (removed > 0) {
                                    "Download removido da lista."
                                } else {
                                    "Não foi possível remover o download."
                                }
                            refreshToken++
                        },
                    )
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "↓",
                fontSize = 22.sp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    item.title.ifBlank { "Download" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    downloadStatusLabel(item.status),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (
                    item.status == DownloadManager.STATUS_RUNNING &&
                    progress != null
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    when {
                        item.totalBytes > 0L ->
                            "${formatBytes(item.bytesDownloaded)} / " +
                                formatBytes(item.totalBytes)
                        item.bytesDownloaded > 0L ->
                            formatBytes(item.bytesDownloaded)
                        else -> "Tamanho desconhecido"
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
    val result = mutableListOf<PocketDownload>()
    val cursor = manager.query(DownloadManager.Query())

    cursor?.use {
        val idIndex =
            it.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleIndex =
            it.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusIndex =
            it.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val downloadedIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
            )
        val totalIndex =
            it.getColumnIndex(
                DownloadManager.COLUMN_TOTAL_SIZE_BYTES
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

            result += PocketDownload(
                id = it.getLong(idIndex),
                title =
                    if (titleIndex >= 0) {
                        it.getString(titleIndex).orEmpty()
                    } else {
                        "Download"
                    },
                status = it.getInt(statusIndex),
                bytesDownloaded =
                    if (downloadedIndex >= 0) {
                        it.getLong(downloadedIndex)
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
            "Concluído"
        DownloadManager.STATUS_FAILED ->
            "Falhou"
        else ->
            "Status desconhecido"
    }
