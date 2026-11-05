package dev.pocketpc.core.update

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketPcUpdaterPolicyTest {
    @Test
    fun firstAutomaticCheckRunsImmediately() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 0L,
                nowMillis = 1_000L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun disabledAutomaticCheckNeverRuns() {
        assertFalse(
            shouldRunUpdateCheck(
                enabled = false,
                lastCheckMillis = 0L,
                nowMillis = Long.MAX_VALUE,
                intervalMillis = 1L,
            )
        )
    }

    @Test
    fun automaticCheckIsThrottledInsideInterval() {
        assertFalse(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 10_000L,
                nowMillis = 15_999L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun automaticCheckRunsAtIntervalBoundary() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 10_000L,
                nowMillis = 16_000L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun clockRollbackDoesNotBlockUpdatesForever() {
        assertTrue(
            shouldRunUpdateCheck(
                enabled = true,
                lastCheckMillis = 20_000L,
                nowMillis = 10_000L,
                intervalMillis = 6_000L,
            )
        )
    }

    @Test
    fun pausedDownloadExplainsMissingNetwork() {
        assertEquals(
            "Download aguardando conexão com a internet.",
            pocketPcDownloadStatusText(
                DownloadManager.STATUS_PAUSED,
                DownloadManager.PAUSED_WAITING_FOR_NETWORK,
            ),
        )
    }

    @Test
    fun failedDownloadExplainsInsufficientSpace() {
        assertEquals(
            "Download falhou: armazenamento insuficiente.",
            pocketPcDownloadStatusText(
                DownloadManager.STATUS_FAILED,
                DownloadManager.ERROR_INSUFFICIENT_SPACE,
            ),
        )
    }

    @Test
    fun httpFailureIncludesServerStatus() {
        assertEquals(
            "Download falhou: servidor respondeu HTTP 503.",
            pocketPcDownloadStatusText(
                DownloadManager.STATUS_FAILED,
                503,
            ),
        )
    }

    @Test
    fun autoInstallRequiresAllSafetyConditions() {
        assertTrue(
            shouldAutoInstallUpdate(
                enabled = true,
                verified = true,
                canInstallPackages = true,
                alreadyAttempted = false,
            )
        )
    }

    @Test
    fun autoInstallRejectsUnverifiedApk() {
        assertFalse(
            shouldAutoInstallUpdate(
                enabled = true,
                verified = false,
                canInstallPackages = true,
                alreadyAttempted = false,
            )
        )
    }

    @Test
    fun autoInstallRejectsMissingInstallerPermission() {
        assertFalse(
            shouldAutoInstallUpdate(
                enabled = true,
                verified = true,
                canInstallPackages = false,
                alreadyAttempted = false,
            )
        )
    }

    @Test
    fun autoInstallRejectsDuplicateAttempt() {
        assertFalse(
            shouldAutoInstallUpdate(
                enabled = true,
                verified = true,
                canInstallPackages = true,
                alreadyAttempted = true,
            )
        )
    }
}
