package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketFileAssociationsTest {
    @Test
    fun windowsExecutablesResolveToPocketPcRuntime() {
        listOf(
            "winrar-x64.exe",
            "setup.msi",
            "installer.cmd",
            "launcher.bat",
        ).forEach { name ->
            val association =
                resolvePocketFileAssociation(name)
            assertEquals(
                PocketFileHandler.WINDOWS_RUNTIME,
                association.handler,
            )
            assertEquals(
                PocketFileRoute.PC_RUNTIME,
                association.route,
            )
        }
    }

    @Test
    fun archiveAndDiskImageHandlersStayInternal() {
        assertEquals(
            PocketFileHandler.ARCHIVE_MANAGER,
            resolvePocketFileAssociation("backup.rar").handler,
        )
        assertEquals(
            PocketFileHandler.DISK_IMAGE_MANAGER,
            resolvePocketFileAssociation("windows.iso").handler,
        )
    }

    @Test
    fun commonDesktopDocumentsResolveToPocketPcApps() {
        assertEquals(
            PocketFileHandler.TEXT_EDITOR,
            resolvePocketFileAssociation("notes.txt").handler,
        )
        assertEquals(
            PocketFileHandler.PDF_VIEWER,
            resolvePocketFileAssociation("manual.pdf").handler,
        )
        assertEquals(
            PocketFileHandler.IMAGE_VIEWER,
            resolvePocketFileAssociation("photo.png").handler,
        )
        assertEquals(
            PocketFileHandler.MEDIA_PLAYER,
            resolvePocketFileAssociation("video.mp4").handler,
        )
        assertEquals(
            PocketFileHandler.OFFICE_VIEWER,
            resolvePocketFileAssociation("trabalho.docx").handler,
        )
        assertEquals(
            PocketFileHandler.WEB_DOCUMENT,
            resolvePocketFileAssociation("index.html").handler,
        )
    }

    @Test
    fun mimeTypeCanResolveFilesWithoutUsefulExtension() {
        assertEquals(
            PocketFileHandler.PDF_VIEWER,
            resolvePocketFileAssociation(
                name = "download.bin",
                mimeType = "application/pdf",
            ).handler,
        )
        assertEquals(
            PocketFileHandler.IMAGE_VIEWER,
            resolvePocketFileAssociation(
                name = "blob",
                mimeType = "image/webp",
            ).handler,
        )
    }

    @Test
    fun androidInstallerIsExplicitSystemBoundary() {
        val association =
            resolvePocketFileAssociation("PocketApp.apk")

        assertEquals(
            PocketFileHandler.ANDROID_PACKAGE_INSTALLER,
            association.handler,
        )
        assertEquals(
            PocketFileHandlerReadiness.ANDROID_SYSTEM_REQUIRED,
            association.readiness,
        )
    }

    @Test
    fun unknownFileHasNoExternalFallback() {
        val association =
            resolvePocketFileAssociation("mystery.unknown")

        assertEquals(
            PocketFileHandler.NONE,
            association.handler,
        )
        assertEquals(
            PocketFileRoute.POCKET_UNSUPPORTED,
            association.route,
        )
    }
}
