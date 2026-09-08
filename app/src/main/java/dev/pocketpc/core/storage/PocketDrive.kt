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
    val metadata: PocketDriveMetadata,
) {
    val schemaVersion: Int
        get() = metadata.schemaVersion

    val volumeId: String
        get() = metadata.volumeId

    val label: String
        get() = metadata.label

    fun uriFor(directory: PocketDriveDirectory): String? =
        directories[directory]
}

data class PocketDriveMetadata(
    val schemaVersion: Int,
    val volumeId: String,
    val label: String,
) {
    fun encode(): String =
        listOf(
            "schemaVersion=$schemaVersion",
            "volumeId=$volumeId",
            "label=$label",
        ).joinToString("\n") + "\n"

    companion object {
        fun decode(raw: String): PocketDriveMetadata? {
            val values =
                raw.lineSequence()
                    .mapNotNull { line ->
                        val index = line.indexOf('=')
                        if (index <= 0) {
                            null
                        } else {
                            line.substring(0, index) to
                                line.substring(index + 1)
                        }
                    }
                    .toMap()

            val schema =
                values["schemaVersion"]
                    ?.toIntOrNull()
                    ?: return null
            val volumeId =
                values["volumeId"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null
            val label =
                values["label"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null

            return PocketDriveMetadata(
                schemaVersion = schema,
                volumeId = volumeId,
                label = label,
            )
        }
    }
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
const val POCKET_DRIVE_METADATA_FILE = "PocketDrive.meta"
const val POCKET_DRIVE_DEFAULT_LABEL = "PocketDrive"

internal fun newPocketDriveMetadata(
    volumeId: String = java.util.UUID.randomUUID().toString(),
    label: String = POCKET_DRIVE_DEFAULT_LABEL,
): PocketDriveMetadata =
    validatePocketDriveMetadata(
        PocketDriveMetadata(
            schemaVersion = POCKET_DRIVE_SCHEMA_VERSION,
            volumeId = volumeId,
            label = label,
        )
    )

internal fun validatePocketDriveMetadata(
    metadata: PocketDriveMetadata,
): PocketDriveMetadata {
    require(
        metadata.schemaVersion == POCKET_DRIVE_SCHEMA_VERSION
    ) {
        "Versão do PocketDrive incompatível: " +
            metadata.schemaVersion +
            ". Esperado: " +
            POCKET_DRIVE_SCHEMA_VERSION +
            "."
    }

    val canonicalVolumeId =
        runCatching {
            java.util.UUID
                .fromString(metadata.volumeId)
                .toString()
        }.getOrElse {
            throw IllegalArgumentException(
                "PocketDrive.meta possui volumeId inválido.",
                it,
            )
        }

    require(canonicalVolumeId == metadata.volumeId.lowercase()) {
        "PocketDrive.meta possui volumeId não canônico."
    }

    val cleanLabel = metadata.label.trim()
    require(
        cleanLabel.isNotEmpty() &&
            cleanLabel.length <= 64 &&
            cleanLabel.none { it == '\n' || it == '\r' || it.code < 32 }
    ) {
        "PocketDrive.meta possui label inválido."
    }

    return metadata.copy(
        volumeId = canonicalVolumeId,
        label = cleanLabel,
    )
}

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
