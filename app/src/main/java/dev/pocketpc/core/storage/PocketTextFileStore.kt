package dev.pocketpc.core.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

const val MAX_EDITABLE_TEXT_BYTES = 512 * 1024

data class PocketTextDocument(
    val text: String,
    val editable: Boolean,
    val truncated: Boolean,
    val bytesRead: Int,
)

suspend fun loadPocketTextDocument(
    context: Context,
    uriString: String,
): Result<PocketTextDocument> =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val output = ByteArrayOutputStream()
            var truncated = false
            var total = 0

            context.contentResolver
                .openInputStream(uri)
                ?.buffered()
                ?.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue

                        val remaining = MAX_EDITABLE_TEXT_BYTES - total
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }

                        val accepted = count.coerceAtMost(remaining)
                        output.write(buffer, 0, accepted)
                        total += accepted

                        if (accepted < count) {
                            truncated = true
                            break
                        }
                    }
                }
                ?: error("Não foi possível abrir o arquivo de texto.")

            PocketTextDocument(
                text =
                    output.toByteArray()
                        .toString(Charsets.UTF_8)
                        .removePrefix("\uFEFF"),
                editable = !truncated,
                truncated = truncated,
                bytesRead = total,
            )
        }
    }

suspend fun savePocketTextDocument(
    context: Context,
    uriString: String,
    text: String,
): Result<Int> =
    withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val newBytes = text.toByteArray(Charsets.UTF_8)
            require(newBytes.size <= MAX_EDITABLE_TEXT_BYTES) {
                "O texto ultrapassa o limite editável de 512 KiB do PocketPC."
            }

            val original =
                readOriginalEditableBytes(
                    context = context,
                    uri = uri,
                )

            try {
                writeTextBytes(
                    context = context,
                    uri = uri,
                    bytes = newBytes,
                )
            } catch (writeError: Throwable) {
                runCatching {
                    writeTextBytes(
                        context = context,
                        uri = uri,
                        bytes = original,
                    )
                }
                throw writeError
            }

            newBytes.size
        }
    }

private fun readOriginalEditableBytes(
    context: Context,
    uri: Uri,
): ByteArray {
    val output = ByteArrayOutputStream()
    context.contentResolver
        .openInputStream(uri)
        ?.buffered()
        ?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_EDITABLE_TEXT_BYTES) {
                    "Arquivos acima de 512 KiB são somente leitura para evitar truncamento acidental."
                }
                output.write(buffer, 0, count)
            }
        }
        ?: error("Não foi possível ler o conteúdo original antes de salvar.")
    return output.toByteArray()
}

private fun writeTextBytes(
    context: Context,
    uri: Uri,
    bytes: ByteArray,
) {
    val output =
        runCatching {
            context.contentResolver.openOutputStream(uri, "rwt")
        }.getOrNull()
            ?: error(
                "O provedor do PocketDrive não oferece sobrescrita truncada segura para este arquivo."
            )

    output.buffered().use { stream ->
        stream.write(bytes)
        stream.flush()
    }
}
