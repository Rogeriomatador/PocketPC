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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_OFFICE_CONTAINER_BYTES = 64L * 1024L * 1024L
private const val MAX_OFFICE_XML_BYTES = 4L * 1024L * 1024L
private const val MAX_OFFICE_TOTAL_XML_BYTES = 16L * 1024L * 1024L
private const val MAX_OFFICE_ENTRY_COUNT = 5_000
private const val MAX_OFFICE_TEXT_CHARS = 1_000_000
private const val MAX_PPTX_SLIDES = 200
private const val MAX_XLSX_SHARED_STRINGS = 100_000

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
                    "Prévia textual segura. Layout avançado, fontes, imagens, macros, fórmulas editáveis e edição ainda não são declarados como suportados.",
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
    if (
        extension !in
            setOf("docx", "odt", "xlsx", "ods", "pptx", "odp")
    ) {
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
        copyOfficeContainer(
            context = context,
            uriString = uriString,
            target = cacheFile,
        )

        ZipFile(cacheFile).use { zip ->
            check(zip.size() <= MAX_OFFICE_ENTRY_COUNT) {
                "O documento contém entradas demais para a prévia segura."
            }

            val extracted =
                when (extension) {
                    "docx" ->
                        extractDocxText(
                            readZipEntryLimited(
                                zip,
                                requireOfficeEntry(zip, "word/document.xml"),
                            )
                        )
                    "odt", "ods", "odp" ->
                        extractOdfText(
                            readZipEntryLimited(
                                zip,
                                requireOfficeEntry(zip, "content.xml"),
                            )
                        )
                    "xlsx" -> extractXlsxText(zip)
                    "pptx" -> extractPptxText(zip)
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

private fun copyOfficeContainer(
    context: android.content.Context,
    uriString: String,
    target: File,
) {
    var copied = 0L
    context.contentResolver
        .openInputStream(Uri.parse(uriString))
        ?.buffered()
        ?.use { input ->
            target.outputStream().buffered().use { output ->
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
}

private fun requireOfficeEntry(
    zip: ZipFile,
    name: String,
): ZipEntry =
    zip.getEntry(name)
        ?: error("Estrutura do documento não reconhecida: $name ausente.")

private fun readZipEntryLimited(
    zip: ZipFile,
    entry: ZipEntry,
): ByteArray {
    check(entry.size < 0L || entry.size <= MAX_OFFICE_XML_BYTES) {
        "A entrada ${entry.name} ultrapassa 4 MiB."
    }

    return zip.getInputStream(entry).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            check(total <= MAX_OFFICE_XML_BYTES) {
                "A entrada ${entry.name} expandida ultrapassa 4 MiB."
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private fun newSafeOfficeParser(xmlBytes: ByteArray): XmlPullParser {
    val parser = Xml.newPullParser()
    runCatching {
        parser.setFeature(
            XmlPullParser.FEATURE_PROCESS_DOCDECL,
            false,
        )
    }
    runCatching {
        parser.setFeature(
            XmlPullParser.FEATURE_PROCESS_NAMESPACES,
            true,
        )
    }
    parser.setInput(xmlBytes.inputStream(), "UTF-8")
    return parser
}

private fun rejectOfficeDocDecl(parser: XmlPullParser) {
    check(parser.eventType != XmlPullParser.DOCDECL) {
        "Declarações DTD não são permitidas em documentos do PocketPC."
    }
}

private fun extractDocxText(xmlBytes: ByteArray): Pair<String, Boolean> {
    val parser = newSafeOfficeParser(xmlBytes)
    val output = StringBuilder()
    var insideText = false
    var truncated = false

    loop@ while (true) {
        rejectOfficeDocDecl(parser)
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "t" -> insideText = true
                    "tab" -> if (!appendOfficeText(output, "\t")) truncated = true
                    "br" -> if (!appendOfficeText(output, "\n")) truncated = true
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
                    "p" -> if (!appendOfficeText(output, "\n")) truncated = true
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return normalizeOfficeText(output.toString()) to truncated
}

private fun extractOdfText(xmlBytes: ByteArray): Pair<String, Boolean> {
    val parser = newSafeOfficeParser(xmlBytes)
    val output = StringBuilder()
    var truncated = false

    loop@ while (true) {
        rejectOfficeDocDecl(parser)
        when (parser.eventType) {
            XmlPullParser.TEXT -> {
                if (!appendOfficeText(output, parser.text)) {
                    truncated = true
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "p", "h", "table-row" ->
                        if (!appendOfficeText(output, "\n")) truncated = true
                    "table-cell" ->
                        if (!appendOfficeText(output, "\t")) truncated = true
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return normalizeOfficeText(output.toString()) to truncated
}

private fun extractXlsxText(zip: ZipFile): Pair<String, Boolean> {
    val sharedStrings =
        zip.getEntry("xl/sharedStrings.xml")
            ?.let { entry ->
                parseXlsxSharedStrings(
                    readZipEntryLimited(zip, entry)
                )
            }
            ?: emptyList()

    val sheetEntry =
        zip.getEntry("xl/worksheets/sheet1.xml")
            ?: firstZipEntryMatching(
                zip = zip,
                regex = Regex("^xl/worksheets/sheet[0-9]+[.]xml$")
            )
            ?: error("Nenhuma planilha XML foi encontrada no XLSX.")

    val parser =
        newSafeOfficeParser(
            readZipEntryLimited(zip, sheetEntry)
        )
    val output = StringBuilder()
    var truncated = false
    var cellType: String? = null
    var inValue = false
    var inInlineText = false
    var valueBuffer = StringBuilder()
    var inlineBuffer = StringBuilder()
    var resolvedValue = ""

    loop@ while (true) {
        rejectOfficeDocDecl(parser)
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        resolvedValue = ""
                    }
                    "v" -> {
                        inValue = true
                        valueBuffer = StringBuilder()
                    }
                    "t" -> {
                        if (cellType == "inlineStr") {
                            inInlineText = true
                            inlineBuffer = StringBuilder()
                        }
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (inValue) valueBuffer.append(parser.text)
                if (inInlineText) inlineBuffer.append(parser.text)
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "v" -> {
                        inValue = false
                        resolvedValue =
                            resolveXlsxCellValue(
                                type = cellType,
                                raw = valueBuffer.toString(),
                                sharedStrings = sharedStrings,
                            )
                    }
                    "t" -> {
                        if (inInlineText) {
                            inInlineText = false
                            resolvedValue = inlineBuffer.toString()
                        }
                    }
                    "c" -> {
                        if (!appendOfficeText(output, resolvedValue)) truncated = true
                        if (!appendOfficeText(output, "\t")) truncated = true
                        cellType = null
                    }
                    "row" -> {
                        if (!appendOfficeText(output, "\n")) truncated = true
                    }
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return normalizeOfficeText(output.toString()) to truncated
}

private fun parseXlsxSharedStrings(xmlBytes: ByteArray): List<String> {
    val parser = newSafeOfficeParser(xmlBytes)
    val values = mutableListOf<String>()
    var inSharedItem = false
    var inText = false
    var current = StringBuilder()

    while (true) {
        rejectOfficeDocDecl(parser)
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "si" -> {
                        inSharedItem = true
                        current = StringBuilder()
                    }
                    "t" -> if (inSharedItem) inText = true
                }
            }
            XmlPullParser.TEXT -> {
                if (inSharedItem && inText) {
                    current.append(parser.text)
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "t" -> inText = false
                    "si" -> {
                        if (values.size >= MAX_XLSX_SHARED_STRINGS) {
                            error("O XLSX possui strings compartilhadas demais para a prévia.")
                        }
                        values += current.toString()
                        inSharedItem = false
                    }
                }
            }
            XmlPullParser.END_DOCUMENT -> return values
        }
        parser.next()
    }
}

private fun resolveXlsxCellValue(
    type: String?,
    raw: String,
    sharedStrings: List<String>,
): String =
    when (type) {
        "s" ->
            raw.toIntOrNull()
                ?.let { index -> sharedStrings.getOrNull(index) }
                ?: ""
        "b" -> if (raw == "1") "TRUE" else "FALSE"
        else -> raw
    }

private fun extractPptxText(zip: ZipFile): Pair<String, Boolean> {
    val slideEntries = mutableListOf<ZipEntry>()
    val enumeration = zip.entries()
    val regex = Regex("^ppt/slides/slide([0-9]+)[.]xml$")
    while (enumeration.hasMoreElements()) {
        val entry = enumeration.nextElement()
        if (regex.matches(entry.name)) {
            slideEntries += entry
        }
    }
    slideEntries.sortBy { entry ->
        regex.matchEntire(entry.name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Int.MAX_VALUE
    }
    check(slideEntries.isNotEmpty()) {
        "Nenhum slide XML foi encontrado no PPTX."
    }

    val output = StringBuilder()
    var truncated = false
    var totalXml = 0L

    for ((index, entry) in slideEntries.withIndex()) {
        if (index >= MAX_PPTX_SLIDES) {
            truncated = true
            break
        }
        val xml = readZipEntryLimited(zip, entry)
        totalXml += xml.size.toLong()
        check(totalXml <= MAX_OFFICE_TOTAL_XML_BYTES) {
            "Os slides expandidos ultrapassam 16 MiB."
        }
        if (!appendOfficeText(output, "Slide ${index + 1}\n")) {
            truncated = true
            break
        }
        val slide = extractPptxSlideText(xml)
        if (!appendOfficeText(output, slide.first)) {
            truncated = true
            break
        }
        if (!appendOfficeText(output, "\n\n")) {
            truncated = true
            break
        }
        if (slide.second) {
            truncated = true
            break
        }
    }

    return normalizeOfficeText(output.toString()) to truncated
}

private fun extractPptxSlideText(xmlBytes: ByteArray): Pair<String, Boolean> {
    val parser = newSafeOfficeParser(xmlBytes)
    val output = StringBuilder()
    var inText = false
    var truncated = false

    loop@ while (true) {
        rejectOfficeDocDecl(parser)
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name.substringAfter(':') == "t") {
                    inText = true
                }
            }
            XmlPullParser.TEXT -> {
                if (inText && !appendOfficeText(output, parser.text)) {
                    truncated = true
                }
            }
            XmlPullParser.END_TAG -> {
                when (parser.name.substringAfter(':')) {
                    "t" -> inText = false
                    "p" -> if (!appendOfficeText(output, "\n")) truncated = true
                }
            }
            XmlPullParser.END_DOCUMENT -> break@loop
        }
        if (truncated) break@loop
        parser.next()
    }

    return output.toString() to truncated
}

private fun firstZipEntryMatching(
    zip: ZipFile,
    regex: Regex,
): ZipEntry? {
    val enumeration = zip.entries()
    while (enumeration.hasMoreElements()) {
        val entry = enumeration.nextElement()
        if (regex.matches(entry.name)) return entry
    }
    return null
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

private fun normalizeOfficeText(value: String): String =
    value
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\t{2,}"), "\t")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
