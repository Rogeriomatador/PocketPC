package dev.pocketpc.core.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class SafeTarExtractorTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun extractsFilesButRecordsLinksWithoutMaterializingThem() {
        val tar = TarTestFactory.build(
            TestTarEntry("etc/", type = '5'),
            TestTarEntry("etc/hello.txt", data = "hello".toByteArray()),
            TestTarEntry("bin", type = '2', linkTarget = "usr/bin"),
        )
        val root = tempDir()
        val metadata = File(root.parentFile, root.name + ".metadata")

        val stats = SafeTarExtractor.extract(
            ByteArrayInputStream(tar),
            root,
            metadata,
            ExtractionPolicy(maxEntries = 10, maxExtractedBytes = 1024),
        )

        assertEquals("hello", File(root, "etc/hello.txt").readText())
        assertFalse(File(root, "bin").exists())
        assertEquals(3, stats.entries)
        assertEquals(1, stats.linksRecorded)
        assertTrue(metadata.readLines().any { it.startsWith("S\t") })
    }

    @Test
    fun rejectsPathTraversal() {
        val tar = TarTestFactory.build(
            TestTarEntry("../escape.txt", data = "bad".toByteArray())
        )
        val root = tempDir()
        val outside = File(root.parentFile, "escape.txt")

        assertThrows(ArchiveSecurityException::class.java) {
            SafeTarExtractor.extract(
                ByteArrayInputStream(tar),
                root,
                File(root.parentFile, root.name + ".metadata"),
                ExtractionPolicy(maxEntries = 10, maxExtractedBytes = 1024),
            )
        }
        assertFalse(outside.exists())
    }

    @Test
    fun rejectsEntryBelowRecordedSymlink() {
        val tar = TarTestFactory.build(
            TestTarEntry("bin", type = '2', linkTarget = "usr/bin"),
            TestTarEntry("bin/tool", data = "bad".toByteArray()),
        )
        val root = tempDir()

        assertThrows(ArchiveSecurityException::class.java) {
            SafeTarExtractor.extract(
                ByteArrayInputStream(tar),
                root,
                File(root.parentFile, root.name + ".metadata"),
                ExtractionPolicy(maxEntries = 10, maxExtractedBytes = 1024),
            )
        }
    }

    @Test
    fun enforcesExtractedByteLimit() {
        val tar = TarTestFactory.build(
            TestTarEntry("big", data = ByteArray(9))
        )
        val root = tempDir()

        assertThrows(ArchiveSecurityException::class.java) {
            SafeTarExtractor.extract(
                ByteArrayInputStream(tar),
                root,
                File(root.parentFile, root.name + ".metadata"),
                ExtractionPolicy(maxEntries = 10, maxExtractedBytes = 8),
            )
        }
    }

    @Test
    fun rejectsCorruptedHeaderChecksum() {
        val tar = TarTestFactory.build(TestTarEntry("file", data = byteArrayOf(1)))
        tar[0] = 'X'.code.toByte()
        val root = tempDir()

        assertThrows(ArchiveSecurityException::class.java) {
            SafeTarExtractor.extract(
                ByteArrayInputStream(tar),
                root,
                File(root.parentFile, root.name + ".metadata"),
                ExtractionPolicy(maxEntries = 10, maxExtractedBytes = 1024),
            )
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("pocketpc-tar-").toFile().also(tempDirs::add)
}
