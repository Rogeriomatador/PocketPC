package dev.pocketpc.core.storage

/**
 * Windows-like association layer for files opened inside PocketPC.
 *
 * Association is intentionally separate from execution readiness. Resolving a
 * file to ARCHIVE_MANAGER or WINDOWS_RUNTIME does not claim that the backing
 * application/runtime has passed a physical test; it only defines ownership of
 * that file type inside the PocketPC desktop.
 */
enum class PocketFileHandler {
    WINDOWS_RUNTIME,
    ANDROID_PACKAGE_INSTALLER,
    ARCHIVE_MANAGER,
    DISK_IMAGE_MANAGER,
    TEXT_EDITOR,
    IMAGE_VIEWER,
    PDF_VIEWER,
    MEDIA_PLAYER,
    OFFICE_VIEWER,
    WEB_DOCUMENT,
    GENERIC_INTERNAL,
    NONE,
}

enum class PocketFileHandlerReadiness {
    /** Handler ownership exists, but its app/runtime still needs implementation or validation. */
    ROUTE_ONLY,

    /** A PocketPC-owned handler exists in code. This is not physical-test evidence. */
    IMPLEMENTED_INTERNAL,

    /** The handler deliberately delegates to an Android system component. */
    ANDROID_SYSTEM_REQUIRED,
}

data class PocketFileAssociation(
    val handler: PocketFileHandler,
    val displayName: String,
    val route: PocketFileRoute,
    val readiness: PocketFileHandlerReadiness,
)

fun resolvePocketFileAssociation(
    name: String,
    mimeType: String? = null,
): PocketFileAssociation {
    val route = routePocketFile(name, mimeType)
    val extension = pocketFileExtension(name)
    val normalizedMime = mimeType?.trim()?.lowercase().orEmpty()

    return when (route.route) {
        PocketFileRoute.PC_RUNTIME ->
            association(
                handler = PocketFileHandler.WINDOWS_RUNTIME,
                displayName = "Runtime Windows do PocketPC",
                route = route.route,
            )

        PocketFileRoute.ANDROID_PACKAGE_INSTALLER ->
            PocketFileAssociation(
                handler = PocketFileHandler.ANDROID_PACKAGE_INSTALLER,
                displayName = "Instalador de pacotes Android",
                route = route.route,
                readiness = PocketFileHandlerReadiness.ANDROID_SYSTEM_REQUIRED,
            )

        PocketFileRoute.POCKET_ARCHIVE ->
            association(
                handler = PocketFileHandler.ARCHIVE_MANAGER,
                displayName = "Compactador do PocketPC",
                route = route.route,
            )

        PocketFileRoute.POCKET_DISK_IMAGE ->
            association(
                handler = PocketFileHandler.DISK_IMAGE_MANAGER,
                displayName = "Gerenciador de imagens de disco",
                route = route.route,
            )

        PocketFileRoute.POCKET_INTERNAL_APP ->
            when {
                extension == "pdf" ||
                    normalizedMime == "application/pdf" ->
                    association(
                        handler = PocketFileHandler.PDF_VIEWER,
                        displayName = "Leitor de PDF do PocketPC",
                        route = route.route,
                    )

                extension in IMAGE_EXTENSIONS ||
                    normalizedMime.startsWith("image/") ->
                    association(
                        handler = PocketFileHandler.IMAGE_VIEWER,
                        displayName = "Fotos do PocketPC",
                        route = route.route,
                    )

                extension in MEDIA_EXTENSIONS ||
                    normalizedMime.startsWith("audio/") ||
                    normalizedMime.startsWith("video/") ->
                    association(
                        handler = PocketFileHandler.MEDIA_PLAYER,
                        displayName = "Mídia do PocketPC",
                        route = route.route,
                    )

                extension in OFFICE_EXTENSIONS ->
                    association(
                        handler = PocketFileHandler.OFFICE_VIEWER,
                        displayName = "Documentos do PocketPC",
                        route = route.route,
                    )

                extension in WEB_EXTENSIONS ||
                    normalizedMime == "text/html" ||
                    normalizedMime == "application/xhtml+xml" ->
                    association(
                        handler = PocketFileHandler.WEB_DOCUMENT,
                        displayName = "Navegador do PocketPC",
                        route = route.route,
                    )

                extension in TEXT_EXTENSIONS ||
                    normalizedMime.startsWith("text/") ->
                    association(
                        handler = PocketFileHandler.TEXT_EDITOR,
                        displayName = "Editor de Texto do PocketPC",
                        route = route.route,
                        readiness = PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
                    )

                else ->
                    association(
                        handler = PocketFileHandler.GENERIC_INTERNAL,
                        displayName = "Aplicativo do PocketPC",
                        route = route.route,
                    )
            }

        PocketFileRoute.POCKET_UNSUPPORTED ->
            PocketFileAssociation(
                handler = PocketFileHandler.NONE,
                displayName = "Nenhum aplicativo PocketPC associado",
                route = route.route,
                readiness = PocketFileHandlerReadiness.ROUTE_ONLY,
            )
    }
}

fun pocketFileExtension(name: String): String =
    name.trim()
        .substringAfterLast('.', "")
        .lowercase()

private fun association(
    handler: PocketFileHandler,
    displayName: String,
    route: PocketFileRoute,
    readiness: PocketFileHandlerReadiness = PocketFileHandlerReadiness.ROUTE_ONLY,
): PocketFileAssociation =
    PocketFileAssociation(
        handler = handler,
        displayName = displayName,
        route = route,
        readiness = readiness,
    )

private val TEXT_EXTENSIONS =
    setOf("txt", "md", "log", "json", "xml", "csv", "ini", "cfg", "conf")

private val IMAGE_EXTENSIONS =
    setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico")

private val MEDIA_EXTENSIONS =
    setOf("mp3", "wav", "ogg", "flac", "m4a", "mp4", "mkv", "webm", "avi", "mov")

private val OFFICE_EXTENSIONS =
    setOf("doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp")

private val WEB_EXTENSIONS =
    setOf("htm", "html", "xhtml")
