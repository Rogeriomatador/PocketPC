package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

data class CapabilityEvidence(
    val passed: Boolean,
    val detail: String,
)

data class FilesystemEvidence(
    val relativeSymlink: CapabilityEvidence,
    val absoluteSymlink: CapabilityEvidence,
    val hardlink: CapabilityEvidence,
    val noFollowCleanup: CapabilityEvidence,
    val externalTargetPreserved: CapabilityEvidence,
    val allCriticalPassed: Boolean,
)

object FilesystemEvidenceProbe {
    fun run(baseDirectory: File): FilesystemEvidence {
        require(baseDirectory.mkdirs() || baseDirectory.isDirectory) {
            "Não foi possível criar diretório base do self-test."
        }

        val token = System.nanoTime().toString()
        val root = File(baseDirectory, "fs-evidence-root-$token")
        val external = File(baseDirectory, "fs-evidence-external-$token")

        val relative = runCatching {
            val guest = File(root, "guest").apply { mkdirs() }
            File(guest, "usr/bin").mkdirs()
            File(guest, "usr/bin/sh").writeText("guest")

            val link = File(guest, "bin").toPath()
            Files.createSymbolicLink(link, Paths.get("usr/bin"))
            require(Files.isSymbolicLink(link))
            require(Files.readSymbolicLink(link).toString() == "usr/bin")
            CapabilityEvidence(true, "relative symlink created/read successfully")
        }.getOrElse {
            CapabilityEvidence(false, "relative symlink failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        val absolute = runCatching {
            external.mkdirs()
            File(external, "keep.txt").writeText("keep")

            val tree = File(root, "absolute").apply { mkdirs() }
            val link = File(tree, "external").toPath()
            Files.createSymbolicLink(link, external.toPath().toAbsolutePath())
            require(Files.isSymbolicLink(link))
            require(
                Files.readSymbolicLink(link) ==
                    external.toPath().toAbsolutePath()
            )
            CapabilityEvidence(true, "absolute symlink node created/read successfully")
        }.getOrElse {
            CapabilityEvidence(false, "absolute symlink failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        val hardlink = runCatching {
            val dir = File(root, "hardlink").apply { mkdirs() }
            val target = File(dir, "target").apply { writeText("same-inode") }
            val alias = File(dir, "alias").toPath()
            Files.createLink(alias, target.toPath())
            require(Files.isSameFile(alias, target.toPath()))
            CapabilityEvidence(true, "hardlink created and Files.isSameFile=true")
        }.getOrElse {
            CapabilityEvidence(false, "hardlink failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        val cleanup = runCatching {
            val deleted = SafeTreeOps.deleteNoFollow(root)
            require(deleted)
            require(!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS))
            CapabilityEvidence(true, "root tree deleted with NOFOLLOW")
        }.getOrElse {
            CapabilityEvidence(false, "NOFOLLOW cleanup failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        val preserved = runCatching {
            val keep = File(external, "keep.txt")
            require(keep.isFile)
            require(keep.readText() == "keep")
            CapabilityEvidence(true, "external symlink target survived root cleanup")
        }.getOrElse {
            CapabilityEvidence(false, "external target preservation failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        SafeTreeOps.deleteNoFollow(root)
        SafeTreeOps.deleteNoFollow(external)

        val critical = listOf(
            relative,
            absolute,
            hardlink,
            cleanup,
            preserved,
        ).all { it.passed }

        return FilesystemEvidence(
            relativeSymlink = relative,
            absoluteSymlink = absolute,
            hardlink = hardlink,
            noFollowCleanup = cleanup,
            externalTargetPreserved = preserved,
            allCriticalPassed = critical,
        )
    }
}
