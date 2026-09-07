package dev.pocketpc.core.runtime

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class FilesystemEvidenceProbeTest {
    @Test
    fun selfTestPassesOnHostFilesystemWithoutFollowingExternalTarget() {
        val base = Files.createTempDirectory("pocketpc-device-evidence-").toFile()
        try {
            val result = FilesystemEvidenceProbe.run(base)

            if (System.getProperty("os.name").startsWith("Windows")) {
                assumeTrue(
                    "Windows host does not permit symbolic links",
                    result.relativeSymlink.passed && result.absoluteSymlink.passed,
                )
            }

            assertTrue(result.relativeSymlink.detail, result.relativeSymlink.passed)
            assertTrue(result.absoluteSymlink.detail, result.absoluteSymlink.passed)
            assertTrue(result.hardlink.detail, result.hardlink.passed)
            assertTrue(result.noFollowCleanup.detail, result.noFollowCleanup.passed)
            assertTrue(
                result.externalTargetPreserved.detail,
                result.externalTargetPreserved.passed,
            )
            assertTrue(result.allCriticalPassed)
        } finally {
            SafeTreeOps.deleteNoFollow(base)
        }
    }
}
