package dev.pocketpc.core.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val ISO_SECTOR_BYTES = 2048
private const val ISO_DESCRIPTOR_START_SECTOR = 16
private const val ISO_DESCRIPTOR_SCAN_LIMIT = 64

private data class PocketIsoInfo(
    val volumeId: String,
    val logicalBlockSize: Int,
    val volumeBlocks: Long,
    val declaredBytes: Long,
    val sourceBytes: Long,
)

private sealed interface PocketIsoState {
    data object Loading : PocketIsoState
    data class Ready(val info: PocketIsoInfo) : PocketIsoState
    data class Failed(val message: String) : PocketIsoState
}

@Composable
fun PocketIsoInspectorPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val uri = request.uri
    var state by remember(uri) {
        mutableStateOf<PocketIsoState>(PocketIsoState.Loading)
    }

    LaunchedEffect(uri) {
        if (uri == null) {
            state = PocketIsoState.Failed("O PocketPC não recebeu acesso à imagem ISO.")
            return@LaunchedEffect
        }

        state = PocketIsoState.Loading
        state =
            withContext(Dispatchers.IO) {
                inspectIso9660(
                    context = context,
                    uriString = uri,
                    sourceBytes = request.sizeBytes,
                )
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val current = state) {
            PocketIsoState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Inspecionando ISO dentro do PocketPC...")
                }

            is PocketIsoState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketIsoState.Ready -> {
                Text(
                    "ISO 9660",
                    style = MaterialTheme.typography.titleSmall,
                )
                IsoValue("Volume", current.info.volumeId.ifBlank { "(sem rótulo)" })
                IsoValue("Bloco lógico", "${current.info.logicalBlockSize} bytes")
                IsoValue("Blocos do volume", current.info.volumeBlocks.toString())
                IsoValue("Tamanho declarado", formatIsoBytes(current.info.declaredBytes))
                if (current.info.sourceBytes > 0L) {
                    IsoValue("Tamanho do arquivo", formatIsoBytes(current.info.sourceBytes))
                }

                Text(
                    "O PocketPC está apenas inspecionando a estrutura ISO9660. Montagem e execução de conteúdo da imagem ainda não são declaradas como implementadas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IsoValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun inspectIso9660(
    context: android.content.Context,
    uriString: String,
    sourceBytes: Long,
): PocketIsoState =
    runCatching {
        context.contentResolver
            .openFileDescriptor(Uri.parse(uriString), "r")
            ?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    val channel = stream.channel
                    val sector = ByteArray(ISO_SECTOR_BYTES)
                    var primary: ByteArray? = null

                    for (index in ISO_DESCRIPTOR_START_SECTOR..ISO_DESCRIPTOR_SCAN_LIMIT) {
                        channel.position(index.toLong() * ISO_SECTOR_BYTES)
                        val buffer = ByteBuffer.wrap(sector)
                        var readTotal = 0
                        while (buffer.hasRemaining()) {
                            val count = channel.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            readTotal += count
                        }
                        check(readTotal == ISO_SECTOR_BYTES) {
                            "Imagem ISO truncada antes dos descritores de volume."
                        }

                        val type = sector[0].toInt() and 0xFF
                        val signature =
                            sector.copyOfRange(1, 6).toString(Charsets.US_ASCII)
                        check(signature == "CD001") {
                            "Assinatura ISO9660 inválida no descritor $index."
                        }

                        if (type == 1 && primary == null) {
                            primary = sector.copyOf()
                        }
                        if (type == 255) break
                    }

                    val pvd = primary
                        ?: error("Primary Volume Descriptor ISO9660 não encontrado.")
                    val volumeId =
                        pvd.copyOfRange(40, 72)
                            .toString(Charsets.US_ASCII)
                            .trim('\u0000', ' ')
                    val volumeBlocks =
                        ByteBuffer.wrap(pvd, 80, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                            .toLong() and 0xFFFF_FFFFL
                    val logicalBlockSize =
                        ByteBuffer.wrap(pvd, 128, 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .short
                            .toInt() and 0xFFFF
                    check(logicalBlockSize > 0) {
                        "ISO9660 declarou tamanho de bloco inválido."
                    }
                    check(volumeBlocks <= Long.MAX_VALUE / logicalBlockSize.toLong()) {
                        "ISO9660 declarou tamanho de volume fora do intervalo suportado."
                    }
                    val declaredBytes = volumeBlocks * logicalBlockSize.toLong()

                    PocketIsoState.Ready(
                        PocketIsoInfo(
                            volumeId = volumeId,
                            logicalBlockSize = logicalBlockSize,
                            volumeBlocks = volumeBlocks,
                            declaredBytes = declaredBytes,
                            sourceBytes = sourceBytes.coerceAtLeast(0L),
                        )
                    )
                }
            }
            ?: error("Não foi possível abrir o descritor da imagem ISO.")
    }.getOrElse { error ->
        PocketIsoState.Failed(
            "Não foi possível inspecionar a ISO dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    }

private fun formatIsoBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format("%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format("%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format("%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
