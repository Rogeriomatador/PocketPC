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
    fun archiveHasPocketPcAssociationButDoesNotPretendHandlerIsReady() {
        val plan = planPocketFileOpen("backup.rar")

        assertEquals(
            PocketFileOpenCapability.INTERNAL_HANDLER_PENDING,
            plan.capability,
        )
        assertEquals(
            PocketFileHandler.ARCHIVE_MANAGER,
            plan.association.handler,
        )
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }

    @Test
    fun officeDocumentUsesInternalAssociationWithoutAndroidEscape() {
        val plan = planPocketFileOpen("trabalho.docx")

        assertEquals(
            PocketFileHandler.OFFICE_VIEWER,
            plan.association.handler,
        )
        assertEquals(
            PocketFileOpenCapability.INTERNAL_HANDLER_PENDING,
            plan.capability,
        )
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

        assertEquals(
            PocketFileOpenCapability.UNSUPPORTED,
            plan.capability,
        )
        assertFalse(plan.canAttemptNow)
        assertFalse(plan.leavesPocketPc)
    }
}
