package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestRuntimeProbeTest {
    @Test
    fun shellProbeActuallyRunsAndTerminates() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh") + GuestRuntimeProbe.SHELL.arguments,
            environment = mapOf("PATH" to "/usr/bin:/bin"),
        ))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("shell=running"))
        assertTrue(result.output.contains("architecture="))
        assertTrue(result.output.contains("probe=complete"))
    }

    @Test
    fun rootfsProbeUsesFailClosedProtocol() {
        val script = GuestRuntimeProbe.ROOTFS.script
        assertTrue(script.contains("POCKETPC_ROOTFS_PROBE_V1"))
        assertTrue(script.contains("/bin /etc /usr /tmp"))
        assertTrue(script.contains("/proc /dev"))
        assertTrue(script.contains("tmp_write=failed"))
        assertTrue(script.contains("rootfs=incomplete"))
        assertTrue(script.contains("exit 5"))
        assertFalse(script.contains("rootfs=ready\n"))
    }

    @Test
    fun rootfsProbeCompletesOnHostOnlyAsProtocolFixture() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(listOf("/bin", "/etc", "/usr", "/tmp", "/proc", "/dev").all { File(it).isDirectory })
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh") + GuestRuntimeProbe.ROOTFS.arguments,
            environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/tmp"),
        ))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("POCKETPC_ROOTFS_PROBE_V1"))
        assertTrue(result.output.contains("tmp_write=ok"))
        assertTrue(result.output.contains("rootfs=structurally_ready"))
        assertTrue(result.output.contains("probe=complete"))
    }

    @Test
    fun absentToolsRemainMissingInsteadOfReady() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
            argv = listOf("/bin/sh") + GuestRuntimeProbe.TOOLCHAIN.arguments,
            environment = mapOf("PATH" to "/pocketpc-nonexistent-toolchain"),
        ))
        assertEquals(0, result.exitCode)
        for (tool in listOf("box64", "wine", "wine64")) {
            assertTrue(result.output.contains("component=$tool\nstate=missing"))
        }
        assertTrue(result.output.contains("application_compatibility=not_tested"))
    }

    @Test
    fun versionFailureIsReportedAndDoesNotSkipRemainingTools() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val dir = Files.createTempDirectory("pocketpc-probe-fixture").toFile()
        try {
            // Controlled fixtures test protocol behavior, not actual Box64/Wine compatibility.
            File(dir, "box64").apply { writeText("#!/bin/sh\nprintf fixture-box64\nexit 7\n"); setExecutable(true) }
            File(dir, "wine").apply { writeText("#!/bin/sh\nprintf fixture-wine\n"); setExecutable(true) }
            val result = RuntimeProcessSupervisor().runOneShot(ProcessRunSpec(
                argv = listOf("/bin/sh") + GuestRuntimeProbe.TOOLCHAIN.arguments,
                environment = mapOf("PATH" to dir.path),
            ))
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("version_exit=7"))
            assertTrue(result.output.contains("fixture-wine"))
            assertTrue(result.output.contains("component=wine64\nstate=missing"))
        } finally { dir.deleteRecursively() }
    }
}
