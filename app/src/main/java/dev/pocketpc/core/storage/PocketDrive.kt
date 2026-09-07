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
