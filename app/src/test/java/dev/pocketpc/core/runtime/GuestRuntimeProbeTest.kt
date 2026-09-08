package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class GuestRuntimeProbeTest {
    @Test
    fun shellProbeActuallyRunsAndTerminates() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val result =
            RuntimeProcessSupervisor().runOneShot(
                ProcessRunSpec(
                    argv =
                        listOf("/bin/sh") +
                            GuestRuntimeProbe.SHELL.arguments,
                    environment =
                        mapOf("PATH" to "/usr/bin:/bin"),
                ),
            )
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("shell=running"))
        assertTrue(result.output.contains("architecture="))
        assertTrue(result.output.contains("probe=complete"))
    }

    @Test
    fun rootfsProbeUsesFailClosedProtocol() {
        val script = GuestRuntimeProbe.ROOTFS.script
        assertTrue(script.contains("POCKETPC_ROOTFS_PROBE_V2"))
        for (path in listOf("/bin", "/etc", "/usr", "/tmp", "/proc", "/dev")) {
            assertTrue(path, script.contains(path))
        }
        assertTrue(script.contains("tmp_write=failed"))
        assertTrue(script.contains("rootfs=incomplete"))
        assertTrue(script.contains("exit 5"))
        assertFalse(script.contains("rootfs=ready"))
    }

    @Test
    fun rootfsProbeCompletesOnHostOnlyAsProtocolFixture() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(
            listOf("/bin", "/etc", "/usr", "/tmp", "/proc", "/dev")
                .all { File(it).isDirectory },
        )
        val result =
            RuntimeProcessSupervisor().runOneShot(
                ProcessRunSpec(
                    argv =
                        listOf("/bin/sh") +
                            GuestRuntimeProbe.ROOTFS.arguments,
                    environment =
                        mapOf(
                            "PATH" to "/usr/bin:/bin",
                            "HOME" to "/tmp",
                        ),
                ),
            )
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("POCKETPC_ROOTFS_PROBE_V2"))
        assertTrue(result.output.contains("tmp_write=ok"))
        assertTrue(result.output.contains("rootfs=structurally_ready"))
        assertTrue(result.output.contains("probe=complete"))
    }

    @Test
    fun toolchainProbeUsesCanonicalAttestedOverlayPaths() {
        val script = GuestRuntimeProbe.TOOLCHAIN.script
        assertTrue(script.contains("POCKETPC_TOOLCHAIN_PROBE_V3"))
        assertTrue(script.contains("/opt/pocketpc/box64/bin/box64"))
        assertTrue(script.contains("/opt/pocketpc/wine/bin/wine"))
        assertTrue(script.contains("application_compatibility=not_tested"))
    }

    @Test
    fun absentCanonicalToolsRemainMissingOnHostFixture() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(!File("/opt/pocketpc/box64/bin/box64").exists())
        assumeTrue(!File("/opt/pocketpc/wine/bin/wine").exists())
        val result =
            RuntimeProcessSupervisor().runOneShot(
                ProcessRunSpec(
                    argv =
                        listOf("/bin/sh") +
                            GuestRuntimeProbe.TOOLCHAIN.arguments,
                    environment =
                        mapOf("PATH" to "/usr/bin:/bin"),
                ),
            )
        assertEquals(0, result.exitCode)
        assertEquals(
            2,
            "state=missing_or_not_executable".toRegex()
                .findAll(result.output)
                .count(),
        )
        assertTrue(
            result.output.contains(
                "application_compatibility=not_tested",
            ),
        )
    }

    @Test
    fun box64SmokeProbeRequiresBothTranslatorAndX8664Fixture() {
        val script = GuestRuntimeProbe.BOX64_SMOKE.script
        assertTrue(script.contains("POCKETPC_BOX64_SMOKE_PROBE_V1"))
        assertTrue(script.contains("/opt/pocketpc/box64/bin/box64"))
        assertTrue(
            script.contains(
                "/opt/pocketpc/box64/share/tests/box64-smoke-x86_64",
            ),
        )
        assertTrue(script.contains("box64_x86_64_smoke=passed"))
        assertTrue(script.contains("exit 7"))
        assertTrue(script.contains("exit 8"))
        assertTrue(script.contains("exit 9"))
    }
}
