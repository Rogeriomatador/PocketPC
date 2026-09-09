package dev.pocketpc.core.runtime

import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class ProcessRunSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File? = null,
    val timeoutMillis: Long = 15_000L,
    val maxOutputBytes: Int = 1024 * 1024,
)

data class ProcessSessionSpec(
    val argv: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File? = null,
    val maxOutputBytes: Int = 1024 * 1024,
)

data class ProcessRunResult(
    val started: Boolean,
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val outputTruncated: Boolean,
    val error: String? = null,
)

data class RuntimeProcessMemberSnapshot(
    val pid: Long,
    val parentPid: Long,
    val command: String,
    val residentMemoryBytes: Long?,
    val threadCount: Int?,
    val root: Boolean,
)

data class RuntimeProcessSnapshot(
    val id: Long,
    val pid: Long?,
    val command: String,
    val argv: List<String>,
    val startedAtMillis: Long,
    val alive: Boolean,
    val residentMemoryBytes: Long?,
    val threadCount: Int?,
    val rootAlive: Boolean = alive,
    val descendantCount: Int = 0,
    val familyResidentMemoryBytes:
        Long? = residentMemoryBytes,
    val familyThreadCount:
        Int? = threadCount,
    val familyPids:
        List<Long> = emptyList(),
    val members:
        List<
            RuntimeProcessMemberSnapshot
        > = emptyList(),
)

internal data class RuntimeProcProcess(
    val pid: Long,
    val parentPid: Long,
    val startTimeTicks: Long,
    val name: String,
    val residentMemoryBytes: Long?,
    val threadCount: Int?,
)

internal object RuntimeProcTree {
    fun family(
        knownMembers:
            Map<Long, Long>,
        processes:
            Map<Long, RuntimeProcProcess>,
    ): List<RuntimeProcProcess> {
        val accepted =
            LinkedHashMap<Long, Long>()

        knownMembers.forEach {
            (pid, startTime) ->
            val current =
                processes[pid]
            if (
                current != null &&
                current.startTimeTicks ==
                    startTime
            ) {
                accepted[pid] =
                    startTime
            }
        }

        var changed: Boolean
        do {
            changed = false
            processes.values.forEach {
                process ->
                if (
                    process.pid !in
                        accepted &&
                    process.parentPid in
                        accepted
                ) {
                    accepted[
                        process.pid
                    ] =
                        process.startTimeTicks
                    changed = true
                }
            }
        } while (changed)

        return accepted.keys
            .mapNotNull(
                processes::get,
            )
    }

    fun depths(
        family:
            Collection<
                RuntimeProcProcess
            >,
    ): Map<Long, Int> {
        val byPid =
            family.associateBy {
                it.pid
            }
        val cache =
            HashMap<Long, Int>()

        fun depth(
            pid: Long,
            visiting:
                MutableSet<Long>,
        ): Int {
            cache[pid]?.let {
                return it
            }
            if (!visiting.add(pid)) {
                return 0
            }

            val parent =
                byPid[pid]
                    ?.parentPid
            val value =
                if (
                    parent != null &&
                    parent in byPid
                ) {
                    1 +
                        depth(
                            parent,
                            visiting,
                        )
                } else {
                    0
                }

            visiting.remove(pid)
            cache[pid] = value
            return value
        }

        byPid.keys.forEach {
            pid ->
            depth(
                pid,
                mutableSetOf(),
            )
        }
        return cache
    }
}

object RuntimeProcessRegistry {
    private data class Entry(
        val id: Long,
        val process: Process,
        val argv: List<String>,
        val startedAtMillis: Long,
        val rootPid: Long?,
        val knownMembers:
            LinkedHashMap<
                Long,
                Long
            > =
            linkedMapOf(),
        var rootExited: Boolean = false,
        var rootExitedAtMillis:
            Long? = null,
    )

    private val lock = Any()
    private val entries =
        LinkedHashMap<Long, Entry>()
    private var nextId = 1L

    internal fun register(
        process: Process,
        argv: List<String>,
    ): Long {
        val pid =
            processPid(process)
        val rootInfo =
            pid?.let(
                ::readProcProcess,
            )

        return synchronized(lock) {
            val id = nextId++
            val entry =
                Entry(
                    id = id,
                    process = process,
                    argv = argv.toList(),
                    startedAtMillis =
                        System.currentTimeMillis(),
                    rootPid = pid,
                )
            rootInfo?.let {
                info ->
                entry.knownMembers[
                    info.pid
                ] =
                    info.startTimeTicks
            }
            entries[id] = entry
            id
        }
    }

    internal fun unregister(
        id: Long,
        process: Process,
    ) {
        synchronized(lock) {
            if (
                entries[id]
                    ?.process === process
            ) {
                entries.remove(id)
            }
        }
    }

    internal fun observeFamily(
        id: Long,
    ): Int {
        val processes =
            scanProc()
        return synchronized(lock) {
            val entry =
                entries[id]
                    ?: return@synchronized 0
            seedLiveRoot(
                entry,
                processes,
            )
            val family =
                RuntimeProcTree.family(
                    entry.knownMembers,
                    processes,
                )
            entry.knownMembers
                .clear()
            family.forEach {
                member ->
                entry.knownMembers[
                    member.pid
                ] =
                    member.startTimeTicks
            }

            family.count {
                it.pid !=
                    entry.rootPid
            }
        }
    }

    internal fun associateMember(
        id: Long,
        pid: Long,
    ): Boolean {
        if (
            pid ==
                android.os.Process
                    .myPid()
                    .toLong()
        ) {
            return false
        }

        val current =
            readProcProcess(pid)
                ?: return false

        return synchronized(lock) {
            val entry =
                entries[id]
                    ?: return@synchronized false
            entry.knownMembers[
                current.pid
            ] =
                current.startTimeTicks
            true
        }
    }

    internal fun markRootExited(
        id: Long,
        process: Process,
    ): Boolean =
        synchronized(lock) {
            val entry =
                entries[id]
                    ?.takeIf {
                        it.process ===
                            process
                    }
                    ?: return@synchronized false

            entry.rootExited = true
            entry.rootExitedAtMillis =
                System.currentTimeMillis()
            entry.rootPid?.let {
                entry.knownMembers
                    .remove(it)
            }

            true
        }

    fun snapshots():
        List<RuntimeProcessSnapshot> {
        val processes =
            scanProc()

        return synchronized(lock) {
            val remove =
                mutableListOf<Long>()

            val result =
                entries.values
                    .mapNotNull {
                        entry ->
                        seedLiveRoot(
                            entry,
                            processes,
                        )
                        val family =
                            RuntimeProcTree.family(
                                entry.knownMembers,
                                processes,
                            )

                        entry.knownMembers
                            .clear()
                        family.forEach {
                            member ->
                            entry.knownMembers[
                                member.pid
                            ] =
                                member
                                    .startTimeTicks
                        }

                        val rootAlive =
                            entry.process
                                .isAlive
                        val liveFamily =
                            family.filter {
                                it.pid !=
                                    entry.rootPid ||
                                    rootAlive
                            }

                        if (
                            !rootAlive &&
                            liveFamily.isEmpty()
                        ) {
                            val exitedAt =
                                entry.rootExitedAtMillis
                            val withinHandoffGrace =
                                exitedAt != null &&
                                    System.currentTimeMillis() -
                                        exitedAt <
                                    ROOT_EXIT_HANDOFF_GRACE_MILLIS

                            if (
                                !withinHandoffGrace
                            ) {
                                remove +=
                                    entry.id
                                return@mapNotNull null
                            }
                        }

                        val rootStats =
                            entry.rootPid
                                ?.let(
                                    processes::get,
                                )
                        val familyMemory =
                            liveFamily
                                .mapNotNull {
                                    it.residentMemoryBytes
                                }
                                .takeIf {
                                    it.isNotEmpty()
                                }
                                ?.sum()
                        val familyThreads =
                            liveFamily
                                .mapNotNull {
                                    it.threadCount
                                }
                                .takeIf {
                                    it.isNotEmpty()
                                }
                                ?.sum()

                        RuntimeProcessSnapshot(
                            id = entry.id,
                            pid = entry.rootPid,
                            command =
                                entry.argv
                                    .firstOrNull()
                                    ?.substringAfterLast(
                                        '/',
                                    )
                                    ?.ifBlank {
                                        "processo"
                                    }
                                    ?: "processo",
                            argv =
                                entry.argv,
                            startedAtMillis =
                                entry.startedAtMillis,
                            alive =
                                rootAlive ||
                                    liveFamily
                                        .isNotEmpty(),
                            residentMemoryBytes =
                                rootStats
                                    ?.residentMemoryBytes,
                            threadCount =
                                rootStats
                                    ?.threadCount,
                            rootAlive =
                                rootAlive,
                            descendantCount =
                                liveFamily.count {
                                    it.pid !=
                                        entry.rootPid
                                },
                            familyResidentMemoryBytes =
                                familyMemory,
                            familyThreadCount =
                                familyThreads,
                            familyPids =
                                liveFamily
                                    .map {
                                        it.pid
                                    }
                                    .sorted(),
                            members =
                                liveFamily
                                    .sortedWith(
                                        compareBy<
                                            RuntimeProcProcess
                                        > {
                                            if (
                                                it.pid ==
                                                    entry.rootPid
                                            ) {
                                                0
                                            } else {
                                                1
                                            }
                                        }.thenBy {
                                            it.pid
                                        },
                                    )
                                    .map {
                                        member ->
                                        RuntimeProcessMemberSnapshot(
                                            pid =
                                                member.pid,
                                            parentPid =
                                                member.parentPid,
                                            command =
                                                member.name,
                                            residentMemoryBytes =
                                                member
                                                    .residentMemoryBytes,
                                            threadCount =
                                                member
                                                    .threadCount,
                                            root =
                                                member.pid ==
                                                    entry.rootPid,
                                        )
                                    },
                        )
                    }
                    .sortedBy {
                        it.startedAtMillis
                    }

            remove.forEach(
                entries::remove,
            )
            result
        }
    }

    fun terminateMember(
        id: Long,
        pid: Long,
        force: Boolean = false,
    ): Boolean {
        val processes =
            scanProc()
        val target =
            synchronized(lock) {
                val entry =
                    entries[id]
                        ?: return false
                seedLiveRoot(
                    entry,
                    processes,
                )
                val family =
                    RuntimeProcTree.family(
                        entry.knownMembers,
                        processes,
                    )
                val member =
                    family.singleOrNull {
                        it.pid == pid
                    } ?: return false

                entry to member
            }

        val entry =
            target.first
        val member =
            target.second
        val current =
            readProcProcess(
                member.pid,
            )
                ?: return false

        if (
            current.startTimeTicks !=
                member.startTimeTicks
        ) {
            return false
        }

        return if (
            member.pid ==
                entry.rootPid &&
            entry.process.isAlive
        ) {
            runCatching {
                if (force) {
                    entry.process
                        .destroyForcibly()
                } else {
                    entry.process
                        .destroy()
                }
                true
            }.getOrDefault(false)
        } else {
            runCatching {
                Os.kill(
                    member.pid.toInt(),
                    if (force) {
                        OsConstants.SIGKILL
                    } else {
                        OsConstants.SIGTERM
                    },
                )
                true
            }.getOrDefault(false)
        }
    }

    fun terminate(
        id: Long,
        force: Boolean = false,
    ): Boolean {
        val processes =
            scanProc()

        val target =
            synchronized(lock) {
                val entry =
                    entries[id]
                        ?: return false
                seedLiveRoot(
                    entry,
                    processes,
                )
                val family =
                    RuntimeProcTree.family(
                        entry.knownMembers,
                        processes,
                    )
                val depths =
                    RuntimeProcTree.depths(
                        family,
                    )
                Triple(
                    entry,
                    family.sortedWith(
                        compareByDescending<
                            RuntimeProcProcess
                        > {
                            depths[it.pid] ?: 0
                        }.thenByDescending {
                            it.pid
                        },
                    ),
                    entry.rootPid,
                )
            }

        val entry =
            target.first
        val family =
            target.second
        val rootPid =
            target.third
        val signal =
            if (force) {
                OsConstants.SIGKILL
            } else {
                OsConstants.SIGTERM
            }

        var acted = false

        family.forEach {
            member ->
            if (
                member.pid ==
                    rootPid
            ) {
                return@forEach
            }

            val current =
                readProcProcess(
                    member.pid,
                )
            if (
                current != null &&
                current.startTimeTicks ==
                    member.startTimeTicks
            ) {
                runCatching {
                    Os.kill(
                        member.pid
                            .toInt(),
                        signal,
                    )
                    acted = true
                }
            }
        }

        if (
            entry.process.isAlive
        ) {
            runCatching {
                if (force) {
                    entry.process
                        .destroyForcibly()
                } else {
                    entry.process
                        .destroy()
                }
                acted = true
            }
        }

        return acted
    }

    private fun seedLiveRoot(
        entry: Entry,
        processes:
            Map<Long, RuntimeProcProcess>,
    ) {
        if (
            entry.rootExited ||
            !entry.process.isAlive
        ) {
            return
        }

        val rootPid =
            entry.rootPid
                ?: return
        val root =
            processes[rootPid]
                ?: return

        entry.knownMembers[
            root.pid
        ] =
            root.startTimeTicks
    }

    private fun scanProc():
        Map<Long, RuntimeProcProcess> {
        val proc =
            File("/proc")
        val directories =
            proc.listFiles()
                ?: return emptyMap()

        return buildMap {
            directories.forEach {
                directory ->
                val pid =
                    directory.name
                        .toLongOrNull()
                        ?: return@forEach
                readProcProcess(
                    pid,
                )?.let {
                    put(
                        pid,
                        it,
                    )
                }
            }
        }
    }

    private fun readProcProcess(
        pid: Long,
    ): RuntimeProcProcess? =
        runCatching {
            if (
                pid <= 0L ||
                pid >
                    Int.MAX_VALUE
            ) {
                return@runCatching null
            }

            val directory =
                File(
                    "/proc/$pid",
                )
            val statusFile =
                File(
                    directory,
                    "status",
                )
            val statFile =
                File(
                    directory,
                    "stat",
                )
            if (
                !statusFile.isFile ||
                !statFile.isFile
            ) {
                return@runCatching null
            }

            var name = "processo"
            var parentPid = 0L
            var rssBytes: Long? = null
            var threads: Int? = null

            statusFile.useLines {
                lines ->
                lines.forEach {
                    line ->
                    when {
                        line.startsWith(
                            "Name:"
                        ) ->
                            name =
                                line.substringAfter(
                                    ':',
                                ).trim()
                                    .ifBlank {
                                        "processo"
                                    }

                        line.startsWith(
                            "PPid:"
                        ) ->
                            parentPid =
                                line.substringAfter(
                                    ':',
                                ).trim()
                                    .toLongOrNull()
                                    ?: 0L

                        line.startsWith(
                            "VmRSS:"
                        ) -> {
                            val kb =
                                line.substringAfter(
                                    ':',
                                ).trim()
                                    .substringBefore(
                                        ' ',
                                    )
                                    .toLongOrNull()
                            rssBytes =
                                kb?.let {
                                    value ->
                                    runCatching {
                                        Math.multiplyExact(
                                            value,
                                            1024L,
                                        )
                                    }.getOrNull()
                                }
                        }

                        line.startsWith(
                            "Threads:"
                        ) ->
                            threads =
                                line.substringAfter(
                                    ':',
                                ).trim()
                                    .toIntOrNull()
                    }
                }
            }

            val stat =
                statFile.readText()
            val closing =
                stat.lastIndexOf(')')
            if (closing <= 0) {
                return@runCatching null
            }
            val fields =
                stat.substring(
                    closing + 1,
                ).trim()
                    .split(
                        Regex("\\s+"),
                    )
            val startTime =
                fields.getOrNull(
                    19,
                )?.toLongOrNull()
                    ?: return@runCatching null

            RuntimeProcProcess(
                pid = pid,
                parentPid =
                    parentPid,
                startTimeTicks =
                    startTime,
                name = name,
                residentMemoryBytes =
                    rssBytes,
                threadCount = threads,
            )
        }.getOrNull()

    private const val
        ROOT_EXIT_HANDOFF_GRACE_MILLIS =
        5_000L

    internal fun processPid(
        process: Process,
    ): Long? =
        runCatching {
            val method =
                process.javaClass.methods
                    .firstOrNull {
                        it.name == "pid" &&
                            it.parameterCount ==
                                0
                    }
                    ?: return@runCatching null
            (
                method.invoke(
                    process,
                ) as? Number
            )?.toLong()
        }.getOrNull()
}

class RuntimeProcessSupervisor {
    @Volatile
    private var active: Process? = null

    @Volatile
    private var activeRegistryId:
        Long? = null

    suspend fun runOneShot(spec: ProcessRunSpec): ProcessRunResult =
        withContext(Dispatchers.IO) {
            var registryId: Long? = null
            if (spec.argv.isEmpty()) {
                return@withContext ProcessRunResult(
                    started = false,
                    exitCode = null,
                    timedOut = false,
                    output = "",
                    outputTruncated = false,
                    error = "argv vazio.",
                )
            }

            require(spec.timeoutMillis in 1_000L..300_000L)
            require(spec.maxOutputBytes in 1024..(16 * 1024 * 1024))

            val process = runCatching {
                // Reserve and publish under the same lock: two callers must never
                // both start a process while active is still null.
                synchronized(this@RuntimeProcessSupervisor) {
                    check(active == null) { "Já existe um processo supervisionado ativo." }
                    ProcessBuilder(spec.argv)
                        .directory(spec.workingDirectory)
                        .redirectErrorStream(true)
                        .apply {
                            environment().clear()
                            environment().putAll(spec.environment)
                        }
                        .start()
                        .also { process ->
                            active = process
                            registryId =
                                RuntimeProcessRegistry
                                    .register(
                                        process,
                                        spec.argv,
                                    )
                            activeRegistryId =
                                registryId
                        }
                }
            }.getOrElse {
                return@withContext ProcessRunResult(
                    started = false,
                    exitCode = null,
                    timedOut = false,
                    output = "",
                    outputTruncated = false,
                    error = it.message ?: it.javaClass.simpleName,
                )
            }

            try {
                // One-shot probes have no interactive input; signal EOF immediately.
                process.outputStream.close()
                val stored = ByteArrayOutputStream(minOf(spec.maxOutputBytes, 64 * 1024))
                val buffer = ByteArray(8192)
                var truncated = false
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(spec.timeoutMillis)
                var timedOut = false
                // Read only available bytes. Waiting for EOF can hang forever when
                // a descendant inherits stdout after the supervised process exits.
                fun drainAvailable() {
                    var remaining = 64 * 1024
                    while (remaining > 0) {
                        val available = process.inputStream.available()
                        if (available <= 0) break
                        val read = process.inputStream.read(buffer, 0, minOf(buffer.size, available, remaining))
                        if (read < 0) break
                        val room = spec.maxOutputBytes - stored.size()
                        if (room > 0) stored.write(buffer, 0, minOf(room, read))
                        if (read > room) truncated = true
                        remaining -= read
                    }
                }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    drainAvailable()
                    if (!process.isAlive) {
                        drainAvailable()
                        break
                    }
                    if (System.nanoTime() >= deadline) {
                        timedOut = true
                        registryId
                            ?.let {
                                RuntimeProcessRegistry
                                    .terminate(
                                        it,
                                        force = false,
                                    )
                            }
                            ?: process.destroy()
                        if (
                            !process.waitFor(
                                500,
                                TimeUnit.MILLISECONDS,
                            )
                        ) {
                            registryId
                                ?.let {
                                    RuntimeProcessRegistry
                                        .terminate(
                                            it,
                                            force = true,
                                        )
                                }
                                ?: process
                                    .destroyForcibly()
                            process.waitFor(
                                2,
                                TimeUnit.SECONDS,
                            )
                        }
                        runCatching { drainAvailable() }
                        break
                    }
                    process.waitFor(10, TimeUnit.MILLISECONDS)
                }
                ProcessRunResult(
                    started = true,
                    exitCode = if (timedOut) null else runCatching { process.exitValue() }.getOrNull(),
                    timedOut = timedOut,
                    output = stored.toByteArray().toString(Charsets.UTF_8),
                    outputTruncated = truncated,
                )
            } finally {
                registryId?.let {
                    RuntimeProcessRegistry
                        .terminate(
                            it,
                            force = true,
                        )
                } ?: if (
                    process.isAlive
                ) {
                    process.destroyForcibly()
                }
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                registryId?.let { id ->
                    RuntimeProcessRegistry
                        .unregister(
                            id,
                            process,
                        )
                }
                synchronized(this@RuntimeProcessSupervisor) {
                    if (active === process) {
                        active = null
                    }
                    if (
                        activeRegistryId ==
                            registryId
                    ) {
                        activeRegistryId =
                            null
                    }
                }
            }
        }

    suspend fun runSession(
        spec: ProcessSessionSpec,
    ): ProcessRunResult =
        withContext(Dispatchers.IO) {
            var registryId: Long? = null

            if (spec.argv.isEmpty()) {
                return@withContext
                    ProcessRunResult(
                        started = false,
                        exitCode = null,
                        timedOut = false,
                        output = "",
                        outputTruncated =
                            false,
                        error = "argv vazio.",
                    )
            }

            require(
                spec.maxOutputBytes in
                    1024..
                        (16 * 1024 * 1024),
            )

            val process =
                runCatching {
                    synchronized(
                        this@RuntimeProcessSupervisor,
                    ) {
                        check(active == null) {
                            "Já existe um processo supervisionado ativo."
                        }
                        ProcessBuilder(
                            spec.argv,
                        )
                            .directory(
                                spec.workingDirectory,
                            )
                            .redirectErrorStream(
                                true,
                            )
                            .apply {
                                environment()
                                    .clear()
                                environment()
                                    .putAll(
                                        spec.environment,
                                    )
                            }
                            .start()
                            .also {
                                startedProcess ->
                                active =
                                    startedProcess
                                registryId =
                                    RuntimeProcessRegistry
                                        .register(
                                            startedProcess,
                                            spec.argv,
                                        )
                                activeRegistryId =
                                    registryId
                            }
                    }
                }.getOrElse {
                    return@withContext
                        ProcessRunResult(
                            started = false,
                            exitCode = null,
                            timedOut = false,
                            output = "",
                            outputTruncated =
                                false,
                            error =
                                it.message
                                    ?: it.javaClass
                                        .simpleName,
                        )
                }

            try {
                process.outputStream
                    .close()

                val stored =
                    ByteArrayOutputStream(
                        minOf(
                            spec.maxOutputBytes,
                            64 * 1024,
                        ),
                    )
                val buffer =
                    ByteArray(8192)
                var truncated = false

                fun drainAvailable() {
                    var remaining =
                        64 * 1024
                    while (remaining > 0) {
                        val available =
                            process.inputStream
                                .available()
                        if (available <= 0) {
                            break
                        }
                        val read =
                            process.inputStream
                                .read(
                                    buffer,
                                    0,
                                    minOf(
                                        buffer.size,
                                        available,
                                        remaining,
                                    ),
                                )
                        if (read < 0) {
                            break
                        }
                        val room =
                            spec.maxOutputBytes -
                                stored.size()
                        if (room > 0) {
                            stored.write(
                                buffer,
                                0,
                                minOf(
                                    room,
                                    read,
                                ),
                            )
                        }
                        if (read > room) {
                            truncated = true
                        }
                        remaining -= read
                    }
                }

                while (true) {
                    currentCoroutineContext()
                        .ensureActive()
                    drainAvailable()

                    registryId?.let {
                        RuntimeProcessRegistry
                            .observeFamily(it)
                    }

                    if (!process.isAlive) {
                        drainAvailable()
                        break
                    }

                    process.waitFor(
                        25,
                        TimeUnit.MILLISECONDS,
                    )
                }

                ProcessRunResult(
                    started = true,
                    exitCode =
                        runCatching {
                            process.exitValue()
                        }.getOrNull(),
                    timedOut = false,
                    output =
                        stored.toByteArray()
                            .toString(
                                Charsets.UTF_8,
                            ),
                    outputTruncated =
                        truncated,
                )
            } finally {
                registryId?.let {
                    RuntimeProcessRegistry
                        .observeFamily(it)
                }

                if (process.isAlive) {
                    registryId
                        ?.let {
                            RuntimeProcessRegistry
                                .terminate(
                                    it,
                                    force = false,
                                )
                        }
                        ?: process.destroy()

                    if (
                        !process.waitFor(
                            500,
                            TimeUnit.MILLISECONDS,
                        )
                    ) {
                        registryId
                            ?.let {
                                RuntimeProcessRegistry
                                    .terminate(
                                        it,
                                        force = true,
                                    )
                            }
                            ?: process
                                .destroyForcibly()
                        process.waitFor(
                            2,
                            TimeUnit.SECONDS,
                        )
                    }
                }
                runCatching {
                    process.outputStream.close()
                }
                runCatching {
                    process.inputStream.close()
                }
                runCatching {
                    process.errorStream.close()
                }
                val familyRetained =
                    registryId?.let { id ->
                        RuntimeProcessRegistry
                            .observeFamily(id)
                        RuntimeProcessRegistry
                            .markRootExited(
                                id,
                                process,
                            )
                    } ?: false
                synchronized(
                    this@RuntimeProcessSupervisor,
                ) {
                    if (active === process) {
                        active = null
                    }
                    if (
                        activeRegistryId ==
                            registryId &&
                        !familyRetained
                    ) {
                        activeRegistryId =
                            null
                    }
                }
            }
        }

    fun activeFamilyId():
        Long? =
        activeRegistryId

    fun associateActiveFamilyPid(
        pid: Long,
    ): Boolean {
        val id =
            activeRegistryId
                ?: return false
        return RuntimeProcessRegistry
            .associateMember(
                id,
                pid,
            )
    }

    fun stopActive(): Boolean {
        val current =
            synchronized(this) {
                active to
                    activeRegistryId
            }
        val process =
            current.first
        val registryId =
            current.second

        val familyStopped =
            registryId?.let {
                RuntimeProcessRegistry
                    .terminate(
                        it,
                        force = true,
                    )
            } ?: false

        if (familyStopped) {
            return true
        }

        if (process == null) {
            return false
        }

        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
        return true
    }

}
