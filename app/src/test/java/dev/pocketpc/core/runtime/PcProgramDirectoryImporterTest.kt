package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream

class PcProgramDirectoryImporterTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Tree : PcProgramDirectorySource {
        override val rootId = "root"
        val entries = mutableMapOf<String, MutableList<PcProgramDocument>>()
        val contents = mutableMapOf<String, ByteArray>()
        val opened = mutableListOf<String>()
        fun file(id: String, name: String, text: String = "EXE", parent: String = rootId, size: Long = text.toByteArray().size.toLong()): Tree {
            entries.getOrPut(parent) { mutableListOf() }.add(PcProgramDocument(id, name, false, size))
            contents[id] = text.toByteArray()
            return this
        }
        fun dir(id: String, name: String, parent: String = rootId): Tree {
            entries.getOrPut(parent) { mutableListOf() }.add(PcProgramDocument(id, name, true))
            return this
        }
        override fun children(directoryId: String): List<PcProgramDocument> = entries[directoryId].orEmpty()
        override fun open(documentId: String): InputStream {
            opened += documentId
            return contents.getValue(documentId).inputStream()
        }
    }

    private fun copy(tree: PcProgramDirectorySource, root: File, importer: PcProgramDirectoryImporter = PcProgramDirectoryImporter(),
                     id: String = "exe", name: String = "app.exe", size: Long = 0) =
        importer.importProgram(tree, id, name, size, root)

    private fun assertEmpty(root: File) = assertTrue(root.listFiles()!!.isEmpty())

    @Test fun preservesExecutableNameDllsDataEmptyFilesAndSubdirectories() {
        val root = temporary.newFolder()
        val tree = Tree().dir("bin", "Programa portátil").dir("data", "data").dir("empty-dir", "empty")
            .file("exe", "app.exe", parent = "bin").file("dll", "library.dll", "DLL", "bin")
            .file("settings", "config.json", "{}", "data").file("empty", "empty.dat", "")
        val result = copy(tree, root).getOrThrow()
        assertEquals("Programa portátil/app.exe", result.relativePath)
        assertEquals("app.exe", result.executable.name)
        assertEquals("EXE", result.executable.readText())
        assertEquals("DLL", File(result.directory, "Programa portátil/library.dll").readText())
        assertEquals("{}", File(result.directory, "data/config.json").readText())
        assertEquals(0L, File(result.directory, "empty.dat").length())
        assertTrue(File(result.directory, "empty").isDirectory)
        assertEquals(4, result.fileCount)
        assertEquals(8L, result.totalBytes)
        assertEquals(Sha256.digest("EXE".byteInputStream()).sha256, result.sha256)
        assertEquals(1, root.listFiles()!!.size)
        assertTrue(result.directory.name.startsWith("program-"))
    }

    @Test fun missingSelectedIdRollsBackInsteadOfGuessingSameName() {
        val root = temporary.newFolder()
        val result = copy(Tree().file("different-document", "app.exe"), root)
        assertEquals("PC_PROGRAM_TARGET_NOT_IN_FOLDER", result.exceptionOrNull()?.message)
        assertEmpty(root)
    }

    @Test fun selectedDirectoryAndChangedNameAreRejected() {
        val root = temporary.newFolder()
        assertTrue(copy(Tree().dir("exe", "app.exe"), root).isFailure)
        assertTrue(copy(Tree().file("exe", "renamed.exe"), root).isFailure)
        assertTrue(copy(Tree(), root, id = "root").isFailure)
        assertEmpty(root)
    }

    @Test fun unsafeWindowsNamesAndTraversalNeverPublishAnything() {
        val root = temporary.newFolder()
        for (name in listOf("../escape", "/absolute", "folder\\app.exe", "C:drive.exe", "NUL.txt", "COM1.exe", "LPT9", "bad.", "bad ", "bad\nname", ".", "..")) {
            assertTrue(name, copy(Tree().file("exe", name), root, name = name).isFailure)
            assertEmpty(root)
        }
    }

    @Test fun caseInsensitiveSiblingCollisionRollsBackAllPreviouslyCopiedFiles() {
        val root = temporary.newFolder()
        val tree = Tree().file("exe", "app.exe").file("other", "APP.EXE")
        assertEquals("PC_PROGRAM_NAME_COLLISION", copy(tree, root).exceptionOrNull()?.message)
        assertEmpty(root)
    }

    @Test fun repeatedDocumentIdsAndProviderCyclesAreRejected() {
        val root = temporary.newFolder()
        assertTrue(copy(Tree().file("exe", "app.exe").file("exe", "other.exe"), root).isFailure)
        assertTrue(copy(Tree().dir("root", "cycle"), root).isFailure)
        assertEmpty(root)
    }

    @Test fun byteLimitAppliesToActualBytesAcrossUnknownSizeFiles() {
        val root = temporary.newFolder()
        val tree = Tree().file("exe", "app.exe", "1234", size = -1).file("dll", "lib.dll", "5678", size = -1)
        assertEquals("PC_PROGRAM_SIZE_LIMIT", copy(tree, root, PcProgramDirectoryImporter(maxBytes = 7)).exceptionOrNull()?.message)
        assertEmpty(root)
    }

    @Test fun oversizedDeclaredFileIsRejectedBeforeOpeningItsStream() {
        val root = temporary.newFolder()
        val tree = Tree().file("exe", "app.exe", size = 100)
        assertTrue(copy(tree, root, PcProgramDirectoryImporter(maxBytes = 10)).isFailure)
        assertTrue(tree.opened.isEmpty())
        assertEmpty(root)
    }

    @Test fun changedProviderSizeAndChangedSelectedSizeAreRejected() {
        val root = temporary.newFolder()
        assertEquals("PC_PROGRAM_SOURCE_SIZE_CHANGED", copy(Tree().file("exe", "app.exe", size = 99), root).exceptionOrNull()?.message)
        assertEquals("PC_PROGRAM_TARGET_SIZE_CHANGED", copy(Tree().file("exe", "app.exe"), root, size = 99).exceptionOrNull()?.message)
        assertTrue(copy(Tree().file("exe", "app.exe", ""), root).isFailure)
        assertEmpty(root)
    }

    @Test fun entryAndDepthLimitsIncludeDirectories() {
        val root = temporary.newFolder()
        assertTrue(copy(Tree().file("exe", "app.exe").dir("empty", "empty"), root,
            PcProgramDirectoryImporter(maxEntries = 1)).isFailure)
        val deep = Tree().dir("a", "a").dir("b", "b", "a").file("exe", "app.exe", parent = "b")
        assertEquals("PC_PROGRAM_DEPTH_LIMIT", copy(deep, root,
            PcProgramDirectoryImporter(maxDepth = 1)).exceptionOrNull()?.message)
        assertEmpty(root)
    }

    @Test fun failedReadRollsBackStagingAndPreservesPreviousCompleteImport() {
        val root = temporary.newFolder()
        val previous = copy(Tree().file("exe", "app.exe"), root).getOrThrow()
        val tree = Tree().file("exe", "app.exe").file("dll", "lib.dll")
        val failing = object : PcProgramDirectorySource by tree {
            override fun open(documentId: String): InputStream = if (documentId == "dll") {
                object : InputStream() { override fun read(): Int = throw java.io.IOException("provider failure") }
            } else tree.open(documentId)
        }
        assertTrue(copy(failing, root).isFailure)
        assertEquals("EXE", previous.executable.readText())
        assertEquals(listOf(previous.directory.name), root.listFiles()!!.map { it.name })
    }

    @Test fun cancellationDuringCopyDoesNotPublishPartialTree() {
        val root = temporary.newFolder()
        var checks = 0
        val result = PcProgramDirectoryImporter().importProgram(Tree().file("exe", "app.exe"),
            "exe", "app.exe", 0, root) {
            if (++checks >= 4) throw java.util.concurrent.CancellationException("cancel")
        }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEmpty(root)
    }

    @Test fun enumerationFailureDoesNotLeaveAStagingDirectory() {
        val root = temporary.newFolder()
        val source = object : PcProgramDirectorySource {
            override val rootId = "root"
            override fun children(directoryId: String): List<PcProgramDocument> = error("permission revoked")
            override fun open(documentId: String): InputStream = error("must not open")
        }
        assertTrue(copy(source, root).isFailure)
        assertEmpty(root)
    }

    @Test fun repeatedSuccessfulImportsNeverOverwriteAnExistingProgram() {
        val root = temporary.newFolder()
        val first = copy(Tree().file("exe", "app.exe", "old"), root).getOrThrow()
        val second = copy(Tree().file("exe", "app.exe", "new"), root).getOrThrow()
        assertNotEquals(first.directory, second.directory)
        assertEquals("old", first.executable.readText())
        assertEquals("new", second.executable.readText())
    }

    @Test fun invalidRootAndInvalidTargetSizeAreRejected() {
        val root = temporary.newFile()
        assertTrue(copy(Tree(), root).isFailure)
        val directory = temporary.newFolder()
        assertTrue(copy(Tree(), directory, size = -1).isFailure)
        assertEmpty(directory)
    }
}
