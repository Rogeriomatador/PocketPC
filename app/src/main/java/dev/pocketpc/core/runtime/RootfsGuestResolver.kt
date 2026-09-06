package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class GuestPathResolution(
    val requested: String,
    val resolvedGuestPath: String?,
    val hostPath: Path?,
    val linkHops: Int,
    val regularFile: Boolean,
    val error: String?,
)

object RootfsGuestResolver {
    private const val MAX_LINK_HOPS = 40

    fun resolve(
        rootfs: File,
        metadata: List<RootfsMetadataEntry>,
        requestedAbsolutePath: String,
    ): GuestPathResolution {
        val normalized = GuestPath.normalizeAbsolute(requestedAbsolutePath)
            ?: return failure(requestedAbsolutePath, "guest path inválido")

        val entries = metadata.associateBy { it.path }
        var pending = ArrayDeque(
            normalized.removePrefix("/")
                .split('/')
                .filter { it.isNotEmpty() }
        )
        val resolved = ArrayList<String>()
        var hops = 0

        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            val candidate = (resolved + component).joinToString("/")
            val entry = entries[candidate]

            when (entry?.type) {
                RootfsEntryType.SYMLINK -> {
                    hops++
                    if (hops > MAX_LINK_HOPS) {
                        return failure(requestedAbsolutePath, "muitos symlinks", hops)
                    }
                    val target = GuestPath.resolveSymlinkTarget(entry.path, entry.target)
                        ?: return failure(
                            requestedAbsolutePath,
                            "target de symlink inválido: ${entry.path}",
                            hops,
                        )
                    val targetParts = target.removePrefix("/")
                        .split('/')
                        .filter { it.isNotEmpty() }
                    val rest = pending.toList()
                    pending = ArrayDeque<String>().apply {
                        targetParts.forEach { add(it) }
                        rest.forEach { add(it) }
                    }
                    resolved.clear()
                }

                RootfsEntryType.HARDLINK -> {
                    if (pending.isNotEmpty()) {
                        return failure(
                            requestedAbsolutePath,
                            "hardlink usado como diretório: ${entry.path}",
                            hops,
                        )
                    }
                    hops++
                    if (hops > MAX_LINK_HOPS) {
                        return failure(requestedAbsolutePath, "muitos hardlinks", hops)
                    }
                    val target = resolveHardlinkTarget(entry, entries)
                        ?: return failure(
                            requestedAbsolutePath,
                            "target de hardlink inválido: ${entry.path}",
                            hops,
                        )
                    resolved.clear()
                    resolved += target.split('/')
                }

                else -> resolved += component
            }
        }

        val guest = "/" + resolved.joinToString("/")
        val rootPath = rootfs.toPath().toAbsolutePath().normalize()
        val host = rootPath.resolve(resolved.joinToString("/")).normalize()
        if (!host.startsWith(rootPath)) {
            return failure(requestedAbsolutePath, "host path escapou do rootfs", hops)
        }

        if (containsUnexpectedHostSymlink(rootPath, host, entries)) {
            return failure(
                requestedAbsolutePath,
                "symlink host não rastreado em metadata",
                hops,
            )
        }

        return GuestPathResolution(
            requested = requestedAbsolutePath,
            resolvedGuestPath = guest,
            hostPath = host,
            linkHops = hops,
            regularFile = SafeTreeOps.isPlainFile(host),
            error = null,
        )
    }

    private fun resolveHardlinkTarget(
        start: RootfsMetadataEntry,
        entries: Map<String, RootfsMetadataEntry>,
    ): String? {
        val visited = HashSet<String>()
        var current = start

        repeat(MAX_LINK_HOPS) {
            if (!visited.add(current.path)) return null
            val target = GuestPath.hardlinkTarget(current.target) ?: return null
            val targetEntry = entries[target] ?: return null

            when (targetEntry.type) {
                RootfsEntryType.FILE -> return target
                RootfsEntryType.HARDLINK -> current = targetEntry
                else -> return null
            }
        }
        return null
    }

    private fun containsUnexpectedHostSymlink(
        root: Path,
        target: Path,
        entries: Map<String, RootfsMetadataEntry>,
    ): Boolean {
        var current = root
        val relative = root.relativize(target)

        for (component in relative) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                val guest = root.relativize(current).joinToString("/")
                if (entries[guest]?.type != RootfsEntryType.SYMLINK) return true
            }
        }
        return false
    }

    private fun failure(
        requested: String,
        error: String,
        hops: Int = 0,
    ) = GuestPathResolution(
        requested = requested,
        resolvedGuestPath = null,
        hostPath = null,
        linkHops = hops,
        regularFile = false,
        error = error,
    )
}
