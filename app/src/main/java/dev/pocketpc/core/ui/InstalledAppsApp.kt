package dev.pocketpc.core.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot
import dev.pocketpc.core.desktop.DesktopLaunchPolicy
import dev.pocketpc.core.desktop.GameCompatibilityProfile
import dev.pocketpc.core.desktop.GameCompatibilityStore
import dev.pocketpc.core.desktop.GameDesktopRating

data class LaunchableAndroidApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val isGame: Boolean,
)

@Composable
fun InstalledAppsApp(
    capabilities: DesktopCapabilitySnapshot,
) {
    val context = LocalContext.current
    var apps by remember {
        mutableStateOf<List<LaunchableAndroidApp>>(
            emptyList()
        )
    }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val compatibilityStore =
        remember { GameCompatibilityStore(context) }
    var selectedGame by remember {
        mutableStateOf<LaunchableAndroidApp?>(null)
    }
    var profileRevision by remember {
        mutableIntStateOf(0)
    }
    var preferExternal by rememberSaveable {
        mutableStateOf(true)
    }
    var preferWindowed by rememberSaveable {
        mutableStateOf(true)
    }
    var gamesOnly by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        apps =
            withContext(Dispatchers.IO) {
                queryLaunchableApps(context)
            }
    }

    val filtered =
        remember(apps, query, gamesOnly) {
            val normalized = query.trim()
            apps.filter { app ->
                val categoryMatches =
                    !gamesOnly || app.isGame
                val queryMatches =
                    normalized.isBlank() ||
                        app.label.contains(
                            normalized,
                            ignoreCase = true,
                        ) ||
                        app.packageName.contains(
                            normalized,
                            ignoreCase = true,
                        )
                categoryMatches && queryMatches
            }
        }

    fun launch(app: LaunchableAndroidApp) {
        val launchIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )
                setClassName(
                    app.packageName,
                    app.activityName,
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        val launchPlan =
            DesktopLaunchPolicy.plan(
                capabilities = capabilities,
                preferExternal = preferExternal,
                preferWindowed = preferWindowed,
            )

        val result =
            runCatching {
                val options =
                    if (
                        launchPlan.usesExternalDisplay ||
                        launchPlan.usesDesktopWindowing
                    ) {
                        buildDesktopLaunchOptions(
                            context = context,
                            capabilities = capabilities,
                            requestedDisplayId =
                                launchPlan.requestedDisplayId,
                            useFreeformBounds =
                                launchPlan.useFreeformBounds,
                        )
                    } else {
                        null
                    }

                if (options != null) {
                    context.startActivity(
                        launchIntent,
                        options.toBundle(),
                    )
                } else {
                    context.startActivity(
                        launchIntent
                    )
                }

                status =
                    when {
                        launchPlan.usesExternalDisplay &&
                            launchPlan.usesDesktopWindowing ->
                            "Aberto no monitor externo com janela: ${app.label}"
                        launchPlan.usesExternalDisplay ->
                            "Aberto no monitor externo: ${app.label}"
                        launchPlan.usesDesktopWindowing ->
                            "Aberto com pedido de janela livre: ${app.label}"
                        else ->
                            "Aberto: ${app.label}"
                    }
            }

        result.onFailure { externalError ->
            if (launchPlan.usesExternalDisplay) {
                runCatching {
                    context.startActivity(launchIntent)
                }
                    .onSuccess {
                        status =
                            "Monitor externo recusou a abertura; " +
                                "app aberto na tela atual."
                    }
                    .onFailure { fallbackError ->
                        status =
                            "Falha ao abrir ${app.label}: " +
                                (
                                    fallbackError.message
                                        ?: externalError.message
                                        ?: fallbackError
                                            .javaClass
                                            .simpleName
                                    )
                    }
            } else {
                status =
                    "Falha ao abrir ${app.label}: " +
                        (
                            externalError.message
                                ?: externalError
                                    .javaClass
                                    .simpleName
                            )
            }
        }
    }

    val editingGame = selectedGame
    if (editingGame != null) {
        val profile =
            remember(
                editingGame.packageName,
                profileRevision,
            ) {
                compatibilityStore.load(
                    editingGame.packageName
                )
            }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                ),
            verticalArrangement =
                Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    selectedGame = null
                }
            ) {
                Text("← Aplicativos")
            }

            GameCompatibilityEditor(
                app = editingGame,
                profile = profile,
                onSave = { updated ->
                    compatibilityStore.save(updated)
                    profileRevision++
                },
                onReset = {
                    compatibilityStore.clear(
                        editingGame.packageName
                    )
                    profileRevision++
                },
                onClose = {
                    selectedGame = null
                },
            )
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(7.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Aplicativos",
                    style =
                        MaterialTheme.typography.titleLarge,
                )
                Text(
                    "${apps.size} apps detectados pelo Android",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }

            FilterChip(
                selected = !gamesOnly,
                onClick = { gamesOnly = false },
                label = { Text("Todos") },
            )
            FilterChip(
                selected = gamesOnly,
                onClick = { gamesOnly = true },
                label = { Text("Jogos") },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(7.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                singleLine = true,
                placeholder = {
                    Text("Pesquisar aplicativos")
                },
                textStyle =
                    LocalTextStyle.current.copy(
                        fontSize = 11.sp
                    ),
            )

            FilterChip(
                selected =
                    preferWindowed &&
                        capabilities
                            .freeformWindowManagement,
                onClick = {
                    preferWindowed =
                        !preferWindowed
                },
                enabled =
                    capabilities
                        .freeformWindowManagement,
                label = {
                    Text("Janela", fontSize = 10.sp)
                },
            )
            FilterChip(
                selected =
                    preferExternal &&
                        capabilities
                            .preferredExternalDisplayId != null,
                onClick = {
                    preferExternal =
                        !preferExternal
                },
                enabled =
                    capabilities
                        .preferredExternalDisplayId != null,
                label = {
                    Text("Monitor", fontSize = 10.sp)
                },
            )
        }

        status?.let {
            Text(
                it,
                fontSize = 10.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }

        HorizontalDivider()

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (query.isBlank()) {
                        "Nenhum aplicativo encontrado."
                    } else {
                        "Nenhum resultado para a pesquisa."
                    }
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(
                    minSize = 132.dp
                ),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
                contentPadding =
                    androidx.compose.foundation.layout.PaddingValues(
                        bottom = 6.dp
                    ),
            ) {
                items(
                    items = filtered,
                    key = {
                        "${it.packageName}/${it.activityName}"
                    },
                ) { app ->
                    val profile =
                        if (app.isGame) {
                            compatibilityStore.load(
                                app.packageName
                            )
                        } else {
                            null
                        }

                    Card(
                        onClick = { launch(app) },
                        modifier = Modifier.height(118.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme
                                        .colorScheme
                                        .surfaceVariant
                            ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(5.dp),
                        ) {
                            Surface(
                                modifier = Modifier
                                    .size(38.dp),
                                shape =
                                    androidx.compose.foundation
                                        .shape
                                        .RoundedCornerShape(
                                            10.dp
                                        ),
                                color =
                                    if (app.isGame) {
                                        MaterialTheme
                                            .colorScheme
                                            .tertiaryContainer
                                    } else {
                                        MaterialTheme
                                            .colorScheme
                                            .primaryContainer
                                    },
                            ) {
                                Box(
                                    contentAlignment =
                                        Alignment.Center,
                                ) {
                                    Text(
                                        app.label
                                            .take(1)
                                            .uppercase(),
                                        style =
                                            MaterialTheme
                                                .typography
                                                .titleMedium,
                                    )
                                }
                            }

                            Text(
                                app.label,
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                            )

                            Text(
                                if (app.isGame) {
                                    "Jogo • " +
                                        (
                                            profile
                                                ?.rating
                                                ?.label
                                                ?: "NÃO TESTADO"
                                            )
                                } else {
                                    "Aplicativo Android"
                                },
                                maxLines = 1,
                                overflow =
                                    TextOverflow.Ellipsis,
                                fontSize = 8.sp,
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant,
                            )

                            if (app.isGame) {
                                TextButton(
                                    onClick = {
                                        selectedGame = app
                                    },
                                    contentPadding =
                                        androidx.compose.foundation
                                            .layout
                                            .PaddingValues(0.dp),
                                ) {
                                    Text(
                                        "Perfil desktop",
                                        fontSize = 8.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCompatibilityEditor(
    app: LaunchableAndroidApp,
    profile: GameCompatibilityProfile,
    onSave: (GameCompatibilityProfile) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember(profile) { mutableStateOf(profile) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("Perfil desktop: ${app.label}", fontSize = 13.sp)
        Text(
            "Este perfil registra apenas o que foi observado/testado. " +
                "Nao altera o jogo nem injeta entrada.",
            fontSize = 10.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GameDesktopRating.entries.forEach { rating ->
                FilterChip(
                    selected = draft.rating == rating,
                    onClick = { draft = draft.copy(rating = rating) },
                    label = { Text(rating.label, fontSize = 8.sp) },
                )
            }
        }

        ProfileToggle(
            label = "Mouse confirmado",
            checked = draft.mouseConfirmed,
            onCheckedChange = {
                draft = draft.copy(mouseConfirmed = it)
            },
        )
        ProfileToggle(
            label = "Teclado confirmado",
            checked = draft.keyboardConfirmed,
            onCheckedChange = {
                draft = draft.copy(keyboardConfirmed = it)
            },
        )
        ProfileToggle(
            label = "Gamepad confirmado",
            checked = draft.gamepadConfirmed,
            onCheckedChange = {
                draft = draft.copy(gamepadConfirmed = it)
            },
        )
        ProfileToggle(
            label = "Tela externa confirmada",
            checked = draft.externalDisplayConfirmed,
            onCheckedChange = {
                draft = draft.copy(externalDisplayConfirmed = it)
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onSave(draft) }) {
                Text("Salvar")
            }
            OutlinedButton(onClick = onReset) {
                Text("Resetar")
            }
            OutlinedButton(onClick = onClose) {
                Text("Fechar")
            }
        }
    }
}

@Composable
private fun ProfileToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 10.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

private fun buildDesktopLaunchOptions(
    context: Context,
    capabilities: DesktopCapabilitySnapshot,
    requestedDisplayId: Int?,
    useFreeformBounds: Boolean,
): ActivityOptions {
    val options = ActivityOptions.makeBasic()

    if (requestedDisplayId != null) {
        options.setLaunchDisplayId(requestedDisplayId)
    }

    if (useFreeformBounds) {
        val external =
            requestedDisplayId?.let { displayId ->
                capabilities.externalDisplays.firstOrNull {
                    it.displayId == displayId
                }
            }

        val width =
            external?.widthPx
                ?: context.resources.displayMetrics.widthPixels
        val height =
            external?.heightPx
                ?: context.resources.displayMetrics.heightPixels

        val targetWidth = (width * 0.74f).toInt().coerceAtLeast(1)
        val targetHeight = (height * 0.78f).toInt().coerceAtLeast(1)
        val left = ((width - targetWidth) / 2).coerceAtLeast(0)
        val top = ((height - targetHeight) / 2).coerceAtLeast(0)

        options.setLaunchBounds(
            Rect(
                left,
                top,
                left + targetWidth,
                top + targetHeight,
            )
        )
    }

    return options
}

@Suppress("DEPRECATION")
private fun queryLaunchableApps(context: Context): List<LaunchableAndroidApp> {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { resolved ->
            val info = resolved.activityInfo ?: return@mapNotNull null
            val label = resolved.loadLabel(context.packageManager)
                .toString()
                .trim()
            if (label.isBlank()) return@mapNotNull null

            LaunchableAndroidApp(
                label = label,
                packageName = info.packageName,
                activityName = info.name,
                isGame =
                    info.applicationInfo.category ==
                        ApplicationInfo.CATEGORY_GAME,
            )
        }
        .distinctBy { it.packageName to it.activityName }
        .sortedBy { it.label.lowercase() }
}
