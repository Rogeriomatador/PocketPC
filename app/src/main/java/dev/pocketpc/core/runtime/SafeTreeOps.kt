package dev.pocketpc.core.runtime

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

object SafeTreeOps {
    fun deleteNoFollow(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true

        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult {
                        if (Files.isSymbolicLink(file)) {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }
                        throw exc
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        }.getOrDefault(false)
    }

    fun isPlainDirectory(path: Path): Boolean =
        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)

    fun isPlainFile(path: Path): Boolean =
        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
}
