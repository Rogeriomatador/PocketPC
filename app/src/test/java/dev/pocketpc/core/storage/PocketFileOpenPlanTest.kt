package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketFileOpenPlanTest {
    @Test
    fun winrarStaysInsidePocketPcAndTargetsWindowsRuntime() {
        val plan = planPocketFileOpen("winrar-x64.exe")
        assertEquals(PocketFileOpenCapability.WINDOWS_RUNTIME_REQUIRED, plan.capability)
        assertEquals(PocketFileHandler.WINDOWS_RUNTIME, plan.association.handler)
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun textFileUsesImplementedPocketPcEditorWithoutAndroidEscape() {
        assertImplementedInternal("notas.txt", "text/plain", PocketFileHandler.TEXT_EDITOR)
    }

    @Test
    fun rasterImageUsesImplementedPocketPcViewerWithoutAndroidEscape() {
        assertImplementedInternal("foto.webp", "image/webp", PocketFileHandler.IMAGE_VIEWER)
    }

    @Test
    fun svgUsesIsolatedPocketPcViewerWithoutAndroidEscape() {
        assertImplementedInternal("vetor.svg", "image/svg+xml", PocketFileHandler.IMAGE_VIEWER)
    }

    @Test
    fun htmlUsesIsolatedPocketPcViewerWithoutAndroidEscape() {
        assertImplementedInternal("offline.html", "text/html", PocketFileHandler.WEB_DOCUMENT)
    }

    @Test
    fun pdfUsesImplementedPocketPcViewerWithoutAndroidEscape() {
        assertImplementedInternal("manual.pdf", "application/pdf", PocketFileHandler.PDF_VIEWER)
    }

    @Test
    fun audioUsesImplementedPocketPcPlayerWithoutAndroidEscape() {
        assertImplementedInternal("musica.mp3", "audio/mpeg", PocketFileHandler.MEDIA_PLAYER)
    }

    @Test
    fun videoUsesImplementedPocketPcPlayerWithoutAndroidEscape() {
        assertImplementedInternal("filme.mp4", "video/mp4", PocketFileHandler.MEDIA_PLAYER)
    }

    @Test
    fun zipUsesImplementedPocketPcArchiveManagerWithoutAndroidEscape() {
        assertImplementedInternal("projeto.zip", "application/zip", PocketFileHandler.ARCHIVE_MANAGER)
    }

    @Test
    fun modernOfficeContainersUseInternalTextPreview() {
        assertImplementedInternal(
            "trabalho.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            PocketFileHandler.OFFICE_VIEWER,
        )
        assertImplementedInternal(
            "planilha.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            PocketFileHandler.OFFICE_VIEWER,
        )
        assertImplementedInternal(
            "slides.pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            PocketFileHandler.OFFICE_VIEWER,
        )
        assertImplementedInternal("texto.odt", null, PocketFileHandler.OFFICE_VIEWER)
        assertImplementedInternal("dados.ods", null, PocketFileHandler.OFFICE_VIEWER)
        assertImplementedInternal("apresentacao.odp", null, PocketFileHandler.OFFICE_VIEWER)
    }

    @Test
    fun legacyBinaryOfficeFormatsRemainInternalButPending() {
        for (name in listOf("antigo.doc", "antiga.xls", "antigo.ppt")) {
            val plan = planPocketFileOpen(name)
            assertEquals(PocketFileHandler.OFFICE_VIEWER, plan.association.handler)
            assertEquals(PocketFileHandlerReadiness.ROUTE_ONLY, plan.association.readiness)
            assertFalse(plan.canAttemptNow)
            assertFalse(plan.leavesPocketPc)
        }
    }

    @Test
    fun rarHasPocketPcAssociationButDoesNotPretendHandlerIsReady() {
        val plan = planPocketFileOpen("backup.rar")
        assertEquals(PocketFileHandler.ARCHIVE_MANAGER, plan.association.handler)
        assertEquals(PocketFileHandlerReadiness.ROUTE_ONLY, plan.association.readiness)
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun apkIsTheExplicitSystemBoundary() {
        val plan = planPocketFileOpen("PocketApp.apk")
        assertEquals(PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED, plan.capability)
        assertTrue(plan.canAttemptNow)
        assertTrue(plan.leavesPocketPc)
    }

    @Test
    fun unknownFileFailsClosedUntilUserExplicitlyChoosesText() {
        val defaultPlan = planPocketFileOpen("codigo.nerva")
        assertEquals(PocketFileOpenCapability.UNSUPPORTED, defaultPlan.capability)
        assertFalse(defaultPlan.canAttemptNow)
        assertFalse(defaultPlan.leavesPocketPc)

        val textPlan = planPocketFileOpenAsText("codigo.nerva")
        assertEquals(PocketFileHandler.TEXT_EDITOR, textPlan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            textPlan.association.readiness,
        )
        assertTrue(textPlan.canAttemptNow)
        assertFalse(textPlan.leavesPocketPc)
        assertEquals("codigo.nerva", textPlan.fileName)
    }

    private fun assertImplementedInternal(
        name: String,
        mimeType: String?,
        expectedHandler: PocketFileHandler,
    ) {
        val plan = planPocketFileOpen(name, mimeType)
        assertEquals(expectedHandler, plan.association.handler)
        assertEquals(
            PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
            plan.association.readiness,
        )
        assertTrue(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }
}
