package dev.pocketpc.core.ui

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot

data class LaunchableAndroidApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val isGame: Boolean,
)

@Composable
fun InstalledAppsApp(capabilities: DesktopCapabilitySnapshot) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableAndroidApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var preferExternal by rememberSaveable { mutableStateOf(true) }
    var gamesOnly by rememberSaveable { mutableStateOf(false) }
    val externalDisplayId = capabilities.preferredExternalDisplayId

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { queryLaunchableApps(context) }
    }

    val filtered = remember(apps, query, gamesOnly) {
        val normalized = query.trim()
        apps.filter { app ->
            val categoryMatches = !gamesOnly || app.isGame
            val queryMatches =
                normalized.isBlank() ||
                    app.label.contains(normalized, ignoreCase = true) ||
                    app.packageName.contains(normalized, ignoreCase = true)
            categoryMatches && queryMatches
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Aplicativos Android", fontSize = 18.sp)
        Text(
            "Abra apps e jogos instalados a partir do desktop PocketPC.",
            fontSize = 12.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = preferExternal,
                onCheckedChange = { preferExternal = it },
                enabled = externalDisplayId != null,
            )
            Text(
                if (externalDisplayId != null) {
                    "Monitor externo detectado: abrir apps nele"
                } else {
                    "Nenhum monitor externo disponivel"
                },
                fontSize = 11.sp,
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Pesquisar aplicativos") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Text(
                "${filtered.size} encontrados",
                modifier = Modifier.align(Alignment.CenterVertically),
                fontSize = 10.sp,
            )
        }

        status?.let { Text(it, fontSize = 12.sp) }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                items = filtered,
                key = { "${it.packageName}/${it.activityName}" },
            ) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                                setClassName(app.packageName, app.activityName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }

                            val requestedDisplay =
                                if (preferExternal) externalDisplayId else null

                            val result = runCatching {
                                if (requestedDisplay != null) {
                                    val options = ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(requestedDisplay)
                                        .toBundle()
                                    context.startActivity(launchIntent, options)
                                    status = "Aberto no monitor externo: ${app.label}"
                                } else {
                                    context.startActivity(launchIntent)
                                    status = "Aberto: ${app.label}"
                                }
                            }

                            result.onFailure { externalError ->
                                if (requestedDisplay != null) {
                                    runCatching {
                                        context.startActivity(launchIntent)
                                    }.onSuccess {
                                        status =
                                            "Monitor externo recusou o launch; " +
                                                "aberto na tela atual."
                                    }.onFailure { fallbackError ->
                                        status =
                                            "Falha ao abrir ${app.label}: " +
                                                (fallbackError.message
                                                    ?: externalError.message
                                                    ?: fallbackError.javaClass.simpleName)
                                    }
                                } else {
                                    status =
                                        "Falha ao abrir ${app.label}: " +
                                            (externalError.message
                                                ?: externalError.javaClass.simpleName)
                                }
                            }
                        }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        modifier = Modifier.width(30.dp),
                        fontSize = 20.sp,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1)
                        Text(
                            if (app.isGame) {
                                "JOGO ANDROID • entrada desktop depende do jogo"
                            } else {
                                "APP ANDROID"
                            },
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                        Text(app.packageName, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
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
