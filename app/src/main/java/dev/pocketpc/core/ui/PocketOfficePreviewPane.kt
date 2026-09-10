package dev.pocketpc.core.ui

import android.net.Uri
import android.util.Xml
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

private const val MAX_OFFICE_CONTAINER_BYTES = 64L * 1024L * 1024L
private const val MAX_OFFICE_XML_BYTES = 4L * 1024L * 1024L
private const val MAX_OFFICE_ENTRY_COUNT = 5_000
private const val MAX_OFFICE_TEXT_CHARS = 1_000_000

private sealed interface PocketOfficePreviewState {
    data object Loading : PocketOfficePreviewState
    data class Ready(
        val text: String,
        val format: String,
        val truncated: Boolean,
    ) : PocketOfficePreviewState
    data class Failed(val message: String) : PocketOfficePreviewState
}

@Composable
fun PocketOfficePreviewPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val uri = request.uri
    val extension =
        request.plan.fileName
            .substringAfterLast('.', "")
            .lowercase()
    var state by remember(uri, extension) {
        mutableStateOf<PocketOfficePreviewState>(PocketOfficePreviewState.Loading)
    }

    LaunchedEffect(uri, extension) {
        if (uri == null) {
            state = PocketOfficePreviewState.Failed(
                "O PocketPC não recebeu acesso ao documento."
            )
            return@LaunchedEffect
        }

        state = PocketOfficePreviewState.Loading
        state =
            withContext(Dispatchers.IO) {
                loadPocketOfficePreview(
                    context = context,
                    uriString = uri,
                    extension = extension,
                )
            }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (val current = state) {
            PocketOfficePreviewState.Loading ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Lendo documento dentro do PocketPC...")
                }

            is PocketOfficePreviewState.Failed ->
                Text(
                    current.message,
                    color = MaterialTheme.colorScheme.error,
                )

            is PocketOfficePreviewState.Ready -> {
                Text(
                    current.format +
                        if (current.truncated) {
                            " • prévia truncada"
                        } else {
                            " • leitura interna"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SelectionContainer {
                    Text(
                        text = current.text.ifBlank { "(documento sem texto detectável)" },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 220.dp, max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    "Esta é uma prévia textual segura; layout avançado, fontes, imagens, macros e edição ainda não são declarados como suportados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun loadPocketOfficePreview(
    context: android.content.Context,
    uriString: String,
    extension: String,
): PocketOfficePreviewState {
    val targetEntry =
        when (extension) {
            "docx" -> "word/document.xml"
            "odt" -> "content.xml"
            else ->
                return PocketOfficePreviewState.Failed(
                    "Este formato de documento ainda não possui prévia interna."
                )
        }

    val cacheFile =
        File.createTempFile(
            "pocketpc-office-",
            ".$extension",
            context.cacheDir,
        )

    return try {
        var copied = 0L
        context.contentResolver
            .openInputStream(Uri.parse(uriString))
            ?.buffered()
            ?.use { input ->
                cacheFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count.toLong()
                        check(copied <= MAX_OFFICE_CONTAINER_BYTES) {
                            "O documento ultrapassa o limite interno de 64 MiB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            }
            ?: error("Não foi possível ler o documento.")

        ZipFile(cacheFile).use { zip ->
            check(zip.size() <= MAX_OFFICE_ENTRY_COUNT) {
                "O documento contém entradas demais para a prévia segura."
            }
            val entry =
                zip.getEntry(targetEntry)
                    ?: error("Estrutura $extension não reconhecida: $targetEntry ausente.")
            check(entry.size < 0L || entry.size <= MAX_OFFICE_XML_BYTES) {
                "O conteúdo textual do documento ultrapassa 4 MiB."
            }

            val xmlBytes =
                zip.getInputStream(entry).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count.toLong()
                        check(total <= MAX_OFFICE_XML_BYTES) {
                            "O conteúdo textual expandido ultrapassa 4 MiB."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }

            val extracted =
                when (extension) {
                    "docx" -> extractDocxText(xmlBytes)
                    "odt" -> extractOdtText(xmlBytes)
                    else -> error("Formato inesperado.")
                }

            PocketOfficePreviewState.Ready(
                text = extracted.first,
                format = extension.uppercase(),
                truncated = extracted.second,
            )
        }
    } catch (error: Throwable) {
        PocketOfficePreviewState.Failed(
            "Não foi possível abrir o documento dentro do PocketPC: " +
                (error.message ?: error.javaClass.simpleName)
        )
    } finally {
        runCatching { cacheFile.delete() }
    }
}

private fun extractDocxText(xmlBytes: ByteArray): Pair<String, Boolean> {
    val parser = Xml.newPullParser()
    parser.setInput(xmlBytes.inputStream(), "UTF-8")
    val output = StringBuilder()
    var insideText = false
    var truncated = false

    loop@ while (true) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "t" -> insideText = true
                    "tab" -> appendOfficeText(output, "\t").also {
                        if (!it) truncated = true
                    }
                    "br" -> appendOfficeText(output, "\n").also {
                        if (!it) truncated = true
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (insideText && !appendOfficeText(output, parser.text)) {
                    truncated = true
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "t" -> insideText = false
                    "p" -> {
                        if (!appendOfficeText(output, "\n")) truncated = true
                    }
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return output.toString().trim() to truncated
}

private fun extractOdtText(xmlBytes: ByteArray): Pair<String, Boolean> {
    val parser = Xml.newPullParser()
    parser.setInput(xmlBytes.inputStream(), "UTF-8")
    val output = StringBuilder()
    var truncated = false

    loop@ while (true) {
        when (parser.eventType) {
            XmlPullParser.TEXT -> {
                if (!appendOfficeText(output, parser.text)) {
                    truncated = true
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "p", "h", "table-row" -> {
                        if (!appendOfficeText(output, "\n")) truncated = true
                    }
                    "table-cell" -> {
                        if (!appendOfficeText(output, "\t")) truncated = true
                    }
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return output.toString()
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim() to truncated
}

private fun appendOfficeText(
    output: StringBuilder,
    value: String,
): Boolean {
    if (value.isEmpty()) return true
    val remaining = MAX_OFFICE_TEXT_CHARS - output.length
    if (remaining <= 0) return false
    if (value.length <= remaining) {
        output.append(value)
        return true
    }
    output.append(value, 0, remaining)
    return false
}
