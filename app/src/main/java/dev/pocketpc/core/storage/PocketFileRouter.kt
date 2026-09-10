package dev.pocketpc.core.storage

/**
 * Central routing policy for files opened from the PocketPC desktop.
 *
 * The desktop must not silently hand files to Android's generic ACTION_VIEW
 * chooser. Routes that leave PocketPC are explicit and narrow (currently APK
 * installation only). Everything else stays in PocketPC and is either handled
 * by a PocketPC app/runtime or reported as unsupported inside the desktop.
 */
enum class PocketFileRoute {
    PC_RUNTIME,
    ANDROID_PACKAGE_INSTALLER,
    POCKET_ARCHIVE,
    POCKET_DISK_IMAGE,
    POCKET_INTERNAL_APP,
    POCKET_UNSUPPORTED,
}

data class PocketFileRoutingDecision(
    val route: PocketFileRoute,
    val reason: String,
)

fun routePocketFile(
    name: String,
    mimeType: String? = null,
): PocketFileRoutingDecision {
    val normalized = name.trim().lowercase()
    val extension = normalized.substringAfterLast('.', "")
    val fileClass = classifyPocketFile(name)

    return when {
        extension == "apk" ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.ANDROID_PACKAGE_INSTALLER,
                reason = "Pacote Android explícito.",
            )

        extension == "apks" || extension == "xapk" ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.POCKET_UNSUPPORTED,
                reason = "Bundle Android ainda não possui instalador interno compatível.",
            )

        fileClass == PocketFileClass.PC_INSTALLER ||
            extension in PC_EXECUTABLE_EXTENSIONS ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.PC_RUNTIME,
                reason = "Aplicativo/instalador de PC deve ser executado pelo runtime do PocketPC.",
            )

        fileClass == PocketFileClass.ARCHIVE ||
            extension in ARCHIVE_EXTENSIONS ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.POCKET_ARCHIVE,
                reason = "Arquivo compactado pertence ao compactador interno do PocketPC.",
            )

        fileClass == PocketFileClass.DISK_IMAGE ||
            extension in DISK_IMAGE_EXTENSIONS ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.POCKET_DISK_IMAGE,
                reason = "Imagem de disco deve ser montada/inspecionada dentro do PocketPC.",
            )

        extension in INTERNAL_DOCUMENT_EXTENSIONS ||
            mimeType?.startsWith("text/") == true ||
            mimeType?.startsWith("image/") == true ||
            mimeType?.startsWith("audio/") == true ||
            mimeType?.startsWith("video/") == true ||
            mimeType == "application/pdf" ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.POCKET_INTERNAL_APP,
                reason = "Tipo reservado para aplicativo interno/associação de arquivos do PocketPC.",
            )

        else ->
            PocketFileRoutingDecision(
                route = PocketFileRoute.POCKET_UNSUPPORTED,
                reason = "Nenhum aplicativo PocketPC está associado a este tipo de arquivo.",
            )
    }
}

private val PC_EXECUTABLE_EXTENSIONS =
    setOf("exe", "msi", "bat", "cmd", "com", "scr")

private val ARCHIVE_EXTENSIONS =
    setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "cab")

private val DISK_IMAGE_EXTENSIONS =
    setOf("iso", "img", "vhd", "vhdx")

private val INTERNAL_DOCUMENT_EXTENSIONS =
    setOf(
        "txt", "md", "log", "json", "xml", "csv",
        "pdf", "png", "jpg", "jpeg", "gif", "webp", "svg",
        "mp3", "wav", "ogg", "flac", "mp4", "mkv", "webm", "avi",
    )
