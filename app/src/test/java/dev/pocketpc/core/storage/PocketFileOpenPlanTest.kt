package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketFileOpenPlanTest {
    @Test
    fun winrarStaysInsidePocketPcAndTargetsWindowsRuntime() {
        val plan = planPocketFileOpen("winrar-x64.exe")

        assertEquals(
            PocketFileOpenCapability.WINDOWS_RUNTIME_REQUIRED,
            plan.capability,
        )
        assertEquals(
            PocketFileHandler.WINDOWS_RUNTIME,
            plan.association.handler,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun textFileUsesImplementedPocketPcViewerWithoutAndroidEscape() {
        val plan = planPocketFileOpen("notas.txt", "text/plain")

        assertEquals(PocketFileHandler.TEXT_EDITOR, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun rasterImageUsesImplementedPocketPcViewerWithoutAndroidEscape() {
        val plan = planPocketFileOpen("foto.webp", "image/webp")

        assertEquals(PocketFileHandler.IMAGE_VIEWER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun pdfUsesImplementedPocketPcViewerWithoutAndroidEscape() {
        val plan = planPocketFileOpen("manual.pdf", "application/pdf")

        assertEquals(PocketFileHandler.PDF_VIEWER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun audioUsesImplementedPocketPcPlayerWithoutAndroidEscape() {
        val plan = planPocketFileOpen("musica.mp3", "audio/mpeg")

        assertEquals(PocketFileHandler.MEDIA_PLAYER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun videoRemainsInternalButPendingUntilVideoSurfaceExists() {
        val plan = planPocketFileOpen("filme.mp4", "video/mp4")

        assertEquals(PocketFileHandler.MEDIA_PLAYER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.ROUTE_ONLY,
            plan.association.readiness,
        )
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun svgRemainsAssociatedButPendingUntilItsRendererExists() {
        val plan = planPocketFileOpen("vetor.svg", "image/svg+xml")

        assertEquals(PocketFileHandler.IMAGE_VIEWER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.ROUTE_ONLY,
            plan.association.readiness,
        )
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun zipUsesImplementedPocketPcArchiveManagerWithoutAndroidEscape() {
        val plan = planPocketFileOpen("projeto.zip", "application/zip")

        assertEquals(PocketFileHandler.ARCHIVE_MANAGER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun rarHasPocketPcAssociationButDoesNotPretendHandlerIsReady() {
        val plan = planPocketFileOpen("backup.rar")

        assertEquals(PocketFileHandler.ARCHIVE_MANAGER, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.ROUTE_ONLY,
            plan.association.readiness,
        )
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun officeDocumentUsesInternalAssociationWithoutAndroidEscape() {
        val plan = planPocketFileOpen("trabalho.docx")

        assertEquals(PocketFileHandler.OFFICE_VIEWER, plan.association.handler)
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun apkIsTheExplicitSystemBoundary() {
        val plan = planPocketFileOpen("PocketApp.apk")

        assertEquals(
            PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED,
            plan.capability,
        )
        assertTrue(plan.canAttemptNow)
        assertTrue(plan.leavesPocketPc)
    }

    @Test
    fun unknownFileFailsClosed() {
        val plan = planPocketFileOpen("mystery.unknown")

        assertEquals(PocketFileOpenCapability.UNSUPPORTED, plan.capability)
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }
}
