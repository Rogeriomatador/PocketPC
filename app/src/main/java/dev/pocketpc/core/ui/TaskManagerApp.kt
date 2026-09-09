package dev.pocketpc.core.ui

import android.os.Process
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.desktop.DesktopApp
import dev.pocketpc.core.desktop.DesktopController
import dev.pocketpc.core.desktop.DesktopWindow
import dev.pocketpc.core.runtime.RuntimeDesktopBridge
import dev.pocketpc.core.runtime.RuntimeDisplayCompositorWindow
import dev.pocketpc.core.runtime.RuntimeProcessRegistry
import dev.pocketpc.core.runtime.RuntimeProcessSnapshot
import kotlinx.coroutines.delay
import kotlin.math.max

private enum class TaskManagerSection {
    APPLICATIONS,
    PROCESSES,
}

@Composable
fun TaskManagerApp(
    desktop: DesktopController,
    runtimeWindows:
        List<RuntimeDisplayCompositorWindow> =
        emptyList(),
    runtimeBridge:
        RuntimeDesktopBridge? = null,
) {
    var section by remember {
        mutableStateOf(TaskManagerSection.APPLICATIONS)
    }
    var query by remember {
        mutableStateOf("")
    }
    var runtimeProcesses by remember {
        mutableStateOf(RuntimeProcessRegistry.snapshots())
    }
    var hostMemoryBytes by remember {
        mutableStateOf(0L)
    }

    LaunchedEffect(Unit) {
        while (true) {
            runtimeProcesses =
                RuntimeProcessRegistry.snapshots()
            val runtime = Runtime.getRuntime()
            hostMemoryBytes =
                runtime.totalMemory() -
                    runtime.freeMemory()
            delay(750)
        }
    }

    val normalizedQuery =
        query.trim().lowercase()
    val windows =
        desktop.windows
            .filter {
                normalizedQuery.isBlank() ||
                    it.title.lowercase()
                        .contains(normalizedQuery) ||
                    it.app.name.lowercase()
                        .contains(normalizedQuery)
            }
            .sortedWith(
                compareByDescending<DesktopWindow> {
                    desktop.activeWindow?.id ==
                        it.id
                }.thenByDescending {
                    it.zIndex
                },
            )
    val filteredRuntimeWindows =
        runtimeWindows
            .filter { window ->
                normalizedQuery.isBlank() ||
                    "win32"
                        .contains(
                            normalizedQuery,
                        ) ||
                    window.windowId
                        .toString()
                        .contains(
                            normalizedQuery,
                        )
            }
            .sortedByDescending {
                it.zIndex
            }

    val processes =
        runtimeProcesses.filter {
            normalizedQuery.isBlank() ||
                it.command.lowercase()
                    .contains(normalizedQuery) ||
                it.argv.joinToString(" ")
                    .lowercase()
                    .contains(normalizedQuery) ||
                it.familyPids
                    .any {
                        pid ->
                        pid.toString()
                            .contains(
                                normalizedQuery,
                            )
                    } ||
                it.members.any {
                    member ->
                    member.command
                        .lowercase()
                        .contains(
                            normalizedQuery,
                        )
                }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(12.dp),
        verticalArrangement =
            Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Gerenciador de Tarefas",
                    style =
                        MaterialTheme.typography
                            .titleLarge,
                    fontWeight =
                        FontWeight.SemiBold,
                )
                val runtimeProcessCount =
                    runtimeProcesses.sumOf {
                        process ->
                        (
                            if (
                                process.rootAlive
                            ) {
                                1
                            } else {
                                0
                            }
                        ) +
                            process
                                .descendantCount
                    }

                Text(
                    "${desktop.windows.size + runtimeWindows.size} app(s) • " +
                        "${runtimeProcessCount} processo(s) runtime em " +
                        "${runtimeProcesses.size} família(s)",
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }
            Text(
                "PocketPC PID ${Process.myPid()}",
                style =
                    MaterialTheme.typography
                        .labelMedium,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = {
                Text("Pesquisar tarefas e processos")
            },
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(
                        rememberScrollState(),
                    ),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            if (
                section ==
                TaskManagerSection.APPLICATIONS
            ) {
                Button(onClick = {}) {
                    Text("Aplicativos")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        section =
                            TaskManagerSection
                                .APPLICATIONS
                    },
                ) {
                    Text("Aplicativos")
                }
            }

            if (
                section ==
                TaskManagerSection.PROCESSES
            ) {
                Button(onClick = {}) {
                    Text("Processos")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        section =
                            TaskManagerSection
                                .PROCESSES
                    },
                ) {
                    Text("Processos")
                }
            }
        }

        when (section) {
            TaskManagerSection.APPLICATIONS ->
                ApplicationsSection(
                    desktop = desktop,
                    windows = windows,
                    runtimeWindows =
                        filteredRuntimeWindows,
                    runtimeBridge =
                        runtimeBridge,
                )

            TaskManagerSection.PROCESSES ->
                ProcessesSection(
                    hostMemoryBytes =
                        hostMemoryBytes,
                    runtimeProcesses =
                        processes,
                )
        }
    }
}

@Composable
private fun ApplicationsSection(
    desktop: DesktopController,
    windows: List<DesktopWindow>,
    runtimeWindows:
        List<RuntimeDisplayCompositorWindow>,
    runtimeBridge:
        RuntimeDesktopBridge?,
) {
    if (
        windows.isEmpty() &&
        runtimeWindows.isEmpty()
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 1.dp,
        ) {
            Text(
                "Nenhum aplicativo corresponde à pesquisa.",
                modifier = Modifier.padding(16.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = windows,
            key = { it.id },
        ) { window ->
            TaskWindowRow(
                window = window,
                active =
                    desktop.activeWindow?.id ==
                        window.id,
                onActivate = {
                    desktop.open(window.app)
                },
                onMinimize = {
                    desktop.minimize(window.id)
                },
                onToggleMaximize = {
                    desktop.toggleMaximize(
                        window.id,
                    )
                },
                onSnapLeft = {
                    desktop.snapLeft(
                        window.id,
                    )
                },
                onSnapRight = {
                    desktop.snapRight(
                        window.id,
                    )
                },
                onEndTask = {
                    desktop.close(window.id)
                },
            )
        }

        items(
            items = runtimeWindows,
            key = {
                "win32:" +
                    it.windowId
            },
        ) { window ->
            RuntimeTaskWindowRow(
                window = window,
                bridge = runtimeBridge,
            )
        }
    }
}

@Composable
private fun TaskWindowRow(
    window: DesktopWindow,
    active: Boolean,
    onActivate: () -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onSnapLeft: () -> Unit,
    onSnapRight: () -> Unit,
    onEndTask: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation =
            if (active) 4.dp else 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                AppIconTile(
                    app = window.app,
                    size = 34,
                    active = active,
                )
                Spacer(Modifier.width(10.dp))
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        window.title,
                        fontWeight =
                            FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(
                                if (active) {
                                    "Ativo"
                                } else if (
                                    window.minimized
                                ) {
                                    "Minimizado"
                                } else {
                                    "Em segundo plano"
                                },
                            )
                            if (window.maximized) {
                                append(" • maximizado")
                            }
                            if (
                                window.snap.name !=
                                "NONE"
                            ) {
                                append(" • encaixado ")
                                append(
                                    window.snap.name
                                        .lowercase(),
                                )
                            }
                        },
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState(),
                        ),
                horizontalArrangement =
                    Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onActivate,
                ) {
                    Text("Ativar")
                }
                TextButton(
                    onClick = onMinimize,
                    enabled = !window.minimized,
                ) {
                    Text("Minimizar")
                }
                TextButton(
                    onClick = onToggleMaximize,
                ) {
                    Text(
                        if (window.maximized) {
                            "Restaurar"
                        } else {
                            "Maximizar"
                        }
                    )
                }
                TextButton(
                    onClick = onSnapLeft,
                ) {
                    Text("Esquerda")
                }
                TextButton(
                    onClick = onSnapRight,
                ) {
                    Text("Direita")
                }
                TextButton(
                    onClick = onEndTask,
                    enabled =
                        window.app !=
                            DesktopApp.TASK_MANAGER,
                ) {
                    Text(
                        "Finalizar tarefa",
                        color =
                            if (
                                window.app !=
                                DesktopApp.TASK_MANAGER
                            ) {
                                MaterialTheme
                                    .colorScheme
                                    .error
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeTaskWindowRow(
    window:
        RuntimeDisplayCompositorWindow,
    bridge: RuntimeDesktopBridge?,
) {
    val visible =
        window.geometry?.visible ==
            true

    Surface(
        shape =
            RoundedCornerShape(12.dp),
        tonalElevation =
            if (visible) {
                3.dp
            } else {
                1.dp
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                Surface(
                    shape =
                        RoundedCornerShape(
                            9.dp,
                        ),
                    color =
                        MaterialTheme
                            .colorScheme
                            .secondaryContainer,
                ) {
                    Text(
                        "WIN",
                        modifier =
                            Modifier.padding(
                                horizontal =
                                    9.dp,
                                vertical =
                                    7.dp,
                            ),
                        style =
                            MaterialTheme
                                .typography
                                .labelSmall,
                        fontWeight =
                            FontWeight.Bold,
                    )
                }
                Spacer(
                    Modifier.width(10.dp),
                )
                Column(
                    modifier =
                        Modifier.weight(1f),
                ) {
                    Text(
                        "Win32 #" +
                            window.windowId,
                        fontWeight =
                            FontWeight.Medium,
                    )
                    Text(
                        buildString {
                            append(
                                when {
                                    window.geometry ==
                                        null ->
                                        "Inicializando"

                                    visible ->
                                        "Visível"

                                    else ->
                                        "Oculta/minimizada"
                                },
                            )
                            append(
                                " • z=" +
                                    window.zIndex,
                            )
                            if (
                                window.frameId >
                                0L
                            ) {
                                append(
                                    " • frame=" +
                                        window.frameId,
                                )
                            }
                            window.surfaceGeneration
                                ?.let {
                                    generation ->
                                    append(
                                        " • gen=" +
                                            generation,
                                    )
                                }
                        },
                        style =
                            MaterialTheme
                                .typography
                                .bodySmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(
                            rememberScrollState(),
                        ),
                horizontalArrangement =
                    Arrangement.spacedBy(
                        4.dp,
                    ),
            ) {
                TextButton(
                    onClick = {
                        if (visible) {
                            bridge?.activate(
                                window.windowId,
                            )
                        } else {
                            bridge?.restore(
                                window.windowId,
                            )
                        }
                    },
                    enabled =
                        bridge != null,
                ) {
                    Text(
                        if (visible) {
                            "Ativar"
                        } else {
                            "Restaurar"
                        },
                    )
                }
                TextButton(
                    onClick = {
                        bridge?.minimize(
                            window.windowId,
                        )
                    },
                    enabled =
                        bridge != null &&
                            visible,
                ) {
                    Text("Minimizar")
                }
                TextButton(
                    onClick = {
                        bridge?.maximize(
                            window.windowId,
                        )
                    },
                    enabled =
                        bridge != null,
                ) {
                    Text("Maximizar")
                }
                TextButton(
                    onClick = {
                        bridge?.closeWindow(
                            window.windowId,
                        )
                    },
                    enabled =
                        bridge != null,
                ) {
                    Text(
                        "Finalizar tarefa",
                        color =
                            if (
                                bridge != null
                            ) {
                                MaterialTheme
                                    .colorScheme
                                    .error
                            } else {
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessesSection(
    hostMemoryBytes: Long,
    runtimeProcesses:
        List<RuntimeProcessSnapshot>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement =
            Arrangement.spacedBy(8.dp),
    ) {
        item(key = "host") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "PocketPC host",
                        fontWeight =
                            FontWeight.SemiBold,
                    )
                    Text(
                        "PID ${Process.myPid()} • " +
                            "${formatBytes(hostMemoryBytes)} heap Java • protegido",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                    Text(
                        "O processo principal não pode ser finalizado pelo próprio Gerenciador de Tarefas.",
                        style =
                            MaterialTheme.typography
                                .labelSmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                    )
                }
            }
        }

        if (runtimeProcesses.isEmpty()) {
            item(key = "empty-runtime") {
                Surface(
                    shape =
                        RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                ) {
                    Text(
                        "Nenhum processo de runtime supervisionado está ativo.",
                        modifier =
                            Modifier.padding(16.dp),
                    )
                }
            }
        }

        items(
            items = runtimeProcesses,
            key = { it.id },
        ) { process ->
            RuntimeProcessRow(process)
        }
    }
}

@Composable
private fun RuntimeProcessRow(
    process: RuntimeProcessSnapshot,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(5.dp),
        ) {
            Text(
                process.command,
                fontWeight =
                    FontWeight.Medium,
            )
            Text(
                buildString {
                    append(
                        process.pid?.let {
                            "PID raiz $it"
                        } ?: "PID raiz indisponível",
                    )
                    append(" • ")
                    append(
                        when {
                            process.rootAlive ->
                                "launcher/raiz ativo"

                            process.descendantCount >
                                0 ->
                                "launcher encerrado • família ativa"

                            process.alive ->
                                "handoff aguardando processo filho"

                            else ->
                                "encerrando"
                        },
                    )

                    if (
                        process.descendantCount >
                            0
                    ) {
                        append(" • ")
                        append(
                            process
                                .descendantCount,
                        )
                        append(
                            if (
                                process
                                    .descendantCount ==
                                1
                            ) {
                                " subprocesso"
                            } else {
                                " subprocessos"
                            },
                        )
                    }

                    process.familyResidentMemoryBytes
                        ?.let { bytes ->
                            append(" • família ")
                            append(
                                formatBytes(
                                    bytes,
                                ),
                            )
                        }
                    process.familyThreadCount
                        ?.let { threads ->
                            append(" • ")
                            append(threads)
                            append(
                                if (
                                    threads == 1
                                ) {
                                    " thread"
                                } else {
                                    " threads"
                                },
                            )
                        }
                    append(" • ")
                    append(
                        formatDuration(
                            System.currentTimeMillis() -
                                process.startedAtMillis,
                        ),
                    )
                },
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
            Text(
                process.argv
                    .take(6)
                    .joinToString(" "),
                style =
                    MaterialTheme.typography
                        .labelSmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                maxLines = 2,
            )

            if (
                process.familyPids
                    .isNotEmpty()
            ) {
                Text(
                    "PIDs da família: " +
                        process.familyPids
                            .take(12)
                            .joinToString(", ") +
                        if (
                            process.familyPids
                                .size >
                            12
                        ) {
                            "…"
                        } else {
                            ""
                        },
                    style =
                        MaterialTheme.typography
                            .labelSmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                    maxLines = 2,
                )
            }

            val childMembers =
                process.members
                    .filterNot {
                        it.root
                    }

            if (
                childMembers
                    .isNotEmpty()
            ) {
                HorizontalDivider()
                Text(
                    "Subprocessos",
                    style =
                        MaterialTheme.typography
                            .labelMedium,
                    fontWeight =
                        FontWeight.SemiBold,
                )

                childMembers
                    .take(16)
                    .forEach {
                        member ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth(),
                            verticalAlignment =
                                Alignment.CenterVertically,
                            horizontalArrangement =
                                Arrangement.spacedBy(
                                    6.dp,
                                ),
                        ) {
                            Column(
                                modifier =
                                    Modifier.weight(
                                        1f,
                                    ),
                            ) {
                                Text(
                                    member.command,
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                    fontWeight =
                                        FontWeight.Medium,
                                )
                                Text(
                                    buildString {
                                        append(
                                            "PID " +
                                                member.pid
                                        )
                                        member
                                            .residentMemoryBytes
                                            ?.let {
                                                bytes ->
                                                append(
                                                    " • " +
                                                        formatBytes(
                                                            bytes,
                                                        )
                                                )
                                            }
                                        member
                                            .threadCount
                                            ?.let {
                                                threads ->
                                                append(
                                                    " • " +
                                                        threads +
                                                        if (
                                                            threads ==
                                                            1
                                                        ) {
                                                            " thread"
                                                        } else {
                                                            " threads"
                                                        },
                                                )
                                            }
                                    },
                                    style =
                                        MaterialTheme
                                            .typography
                                            .labelSmall,
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant,
                                )
                            }

                            TextButton(
                                onClick = {
                                    RuntimeProcessRegistry
                                        .terminateMember(
                                            id =
                                                process.id,
                                            pid =
                                                member.pid,
                                            force =
                                                false,
                                        )
                                },
                            ) {
                                Text("Finalizar")
                            }
                            TextButton(
                                onClick = {
                                    RuntimeProcessRegistry
                                        .terminateMember(
                                            id =
                                                process.id,
                                            pid =
                                                member.pid,
                                            force =
                                                true,
                                        )
                                },
                            ) {
                                Text(
                                    "Forçar",
                                    color =
                                        MaterialTheme
                                            .colorScheme
                                            .error,
                                )
                            }
                        }
                    }

                if (
                    childMembers.size >
                        16
                ) {
                    Text(
                        "+" +
                            (
                                childMembers.size -
                                    16
                            ) +
                            " subprocessos não exibidos",
                        style =
                            MaterialTheme.typography
                                .labelSmall,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = {
                        RuntimeProcessRegistry
                            .terminate(
                                process.id,
                                force = false,
                            )
                    },
                ) {
                    Text(
                        if (
                            process.descendantCount >
                                0 ||
                            !process.rootAlive
                        ) {
                            "Finalizar família"
                        } else {
                            "Finalizar"
                        }
                    )
                }
                TextButton(
                    onClick = {
                        RuntimeProcessRegistry
                            .terminate(
                                process.id,
                                force = true,
                            )
                    },
                ) {
                    Text(
                        if (
                            process.descendantCount >
                                0 ||
                            !process.rootAlive
                        ) {
                            "Forçar família"
                        } else {
                            "Forçar encerramento"
                        },
                        color =
                            MaterialTheme
                                .colorScheme
                                .error,
                    )
                }
            }
        }
    }
}

private fun formatBytes(
    bytes: Long,
): String {
    val safe = max(0L, bytes)
    return when {
        safe >=
            1024L * 1024L * 1024L ->
            "%.1f GiB".format(
                safe /
                    (1024.0 * 1024.0 * 1024.0),
            )

        safe >=
            1024L * 1024L ->
            "%.1f MiB".format(
                safe /
                    (1024.0 * 1024.0),
            )

        safe >= 1024L ->
            "%.1f KiB".format(
                safe / 1024.0,
            )

        else -> "$safe B"
    }
}

private fun formatDuration(
    millis: Long,
): String {
    val seconds =
        max(0L, millis / 1000L)
    val minutes = seconds / 60L
    val remaining = seconds % 60L
    return if (minutes > 0L) {
        "${minutes}m ${remaining}s"
    } else {
        "${remaining}s"
    }
}
