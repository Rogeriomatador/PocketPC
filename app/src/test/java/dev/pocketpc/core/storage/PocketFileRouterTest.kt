package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketFileRouterTest {
    @Test
    fun windowsInstallersStayInsidePocketPcRuntime() {
        listOf(
            "winrar-x64.exe",
            "setup.msi",
            "tool.cmd",
        ).forEach { name ->
            assertEquals(
                PocketFileRoute.PC_RUNTIME,
                routePocketFile(name).route,
            )
        }
    }

    @Test
    fun archivesStayInsidePocketPc() {
        listOf(
            "archive.zip",
            "backup.rar",
            "bundle.7z",
        ).forEach { name ->
            assertEquals(
                PocketFileRoute.POCKET_ARCHIVE,
                routePocketFile(name).route,
            )
        }
    }

    @Test
    fun diskImagesStayInsidePocketPc() {
        listOf(
            "installer.iso",
            "disk.img",
            "machine.vhdx",
        ).forEach { name ->
            assertEquals(
                PocketFileRoute.POCKET_DISK_IMAGE,
                routePocketFile(name).route,
            )
        }
    }

    @Test
    fun commonDocumentsUseInternalPocketPcApps() {
        assertEquals(
            PocketFileRoute.POCKET_INTERNAL_APP,
            routePocketFile("notes.txt").route,
        )
        assertEquals(
            PocketFileRoute.POCKET_INTERNAL_APP,
            routePocketFile(
                name = "download.bin",
                mimeType = "application/pdf",
            ).route,
        )
        assertEquals(
            PocketFileRoute.POCKET_INTERNAL_APP,
            routePocketFile(
                name = "photo",
                mimeType = "image/png",
            ).route,
        )
    }

    @Test
    fun onlyExplicitApkUsesAndroidPackageInstallerRoute() {
        assertEquals(
            PocketFileRoute.ANDROID_PACKAGE_INSTALLER,
            routePocketFile("PocketApp.apk").route,
        )
        assertEquals(
            PocketFileRoute.POCKET_UNSUPPORTED,
            routePocketFile("PocketApp.xapk").route,
        )
    }

    @Test
    fun unknownFilesFailClosedInsidePocketPc() {
        assertEquals(
            PocketFileRoute.POCKET_UNSUPPORTED,
            routePocketFile("mystery.unknown").route,
        )
    }
}
