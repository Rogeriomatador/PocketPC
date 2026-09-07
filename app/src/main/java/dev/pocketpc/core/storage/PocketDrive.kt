package dev.pocketpc.core.storage

/**
 * Stable logical layout for the PocketPC user volume.
 *
 * P: is backed by the Storage Access Framework tree selected by the user.
 * C: remains app-private and is reserved for hot/system/runtime data.
 *
 * The logical drive letters are UI/runtime concepts. They do not claim the
 * phone contains physically separate flash devices.
 */
enum class PocketDriveDirectory(
    val folderName: String,
    val displayName: String,
) {
    DESKTOP("Desktop", "Área de Trabalho"),
    DOCUMENTS("Documents", "Documentos"),
    DOWNLOADS("Downloads", "Downloads"),
    APPLICATIONS("Apps", "Aplicativos"),
    GAMES("Games", "Jogos"),
    PROJECTS("Projects", "Projetos"),
    PICTURES("Pictures", "Imagens"),
    VIDEOS("Videos", "Vídeos"),
    MUSIC("Music", "Músicas"),
    SHARED("Shared", "Compartilhado"),
    BACKUPS("Backups", "Backups"),
}

data class PocketDriveMount(
    val rootUri: String,
    val directories: Map<PocketDriveDirectory, String>,
    val schemaVersion: Int = POCKET_DRIVE_SCHEMA_VERSION,
) {
    fun uriFor(directory: PocketDriveDirectory): String? =
        directories[directory]
}

data class PocketSystemVolume(
    val rootPath: String,
    val cachePath: String,
    val runtimePath: String,
    val packagesPath: String,
    val temporaryPath: String,
)

const val POCKET_DRIVE_LETTER = "P:"
const val POCKET_SYSTEM_LETTER = "C:"
const val POCKET_DRIVE_SCHEMA_VERSION = 1

fun pocketPath(
    directory: PocketDriveDirectory,
    child: String? = null,
): String =
    buildString {
        append(POCKET_DRIVE_LETTER)
        append("\\")
        append(directory.folderName)
        if (!child.isNullOrBlank()) {
            append("\\")
            append(child.trimStart('\\', '/'))
        }
    }

internal fun sanitizePocketImportedFileName(
    raw: String,
): String {
    val leaf =
        raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()

    val normalized =
        buildString {
            leaf.forEach { character ->
                val forbidden =
                    character.code < 32 ||
                        character in
                            setOf(
                                '<',
                                '>',
                                ':',
                                '"',
                                '/',
                                '\\',
                                '|',
                                '?',
                                '*',
                            )

                append(
                    if (forbidden) {
                        '_'
                    } else {
                        character
                    }
                )
            }
        }
            .trim()
            .trimEnd('.', ' ')
            .take(180)

    val fallback =
        normalized.ifBlank { "download" }

    val baseName =
        fallback
            .substringBefore('.')
            .uppercase()

    val reserved =
        baseName in
            setOf(
                "CON",
                "PRN",
                "AUX",
                "NUL",
                "COM1",
                "COM2",
                "COM3",
                "COM4",
                "COM5",
                "COM6",
                "COM7",
                "COM8",
                "COM9",
                "LPT1",
                "LPT2",
                "LPT3",
                "LPT4",
                "LPT5",
                "LPT6",
                "LPT7",
                "LPT8",
                "LPT9",
            )

    return validateStorageName(
        if (reserved) {
            "_$fallback"
        } else {
            fallback
        }
    )
}

internal fun validateStorageName(
    raw: String,
): String {
    val value = raw.trim()
    require(value.isNotEmpty()) {
        "O nome não pode ficar vazio."
    }
    require(
        '/' !in value &&
            '\\' !in value &&
            value != "." &&
            value != ".."
    ) {
        "Nome inválido para arquivo ou pasta."
    }
    return value
}

fun classifyPocketFile(name: String): PocketFileClass {
    val extension =
        name.substringAfterLast(
            '.',
            missingDelimiterValue = "",
        ).lowercase()

    return when (extension) {
        "exe", "msi", "msix", "appx", "appxbundle" ->
            PocketFileClass.PC_INSTALLER
        "apk", "apks", "xapk" ->
            PocketFileClass.ANDROID_PACKAGE
        "zip", "7z", "rar", "tar", "gz", "xz" ->
            PocketFileClass.ARCHIVE
        "iso", "img", "vhd", "vhdx" ->
            PocketFileClass.DISK_IMAGE
        else ->
            PocketFileClass.GENERIC
    }
}

enum class PocketFileClass {
    PC_INSTALLER,
    ANDROID_PACKAGE,
    ARCHIVE,
    DISK_IMAGE,
    GENERIC,
}
