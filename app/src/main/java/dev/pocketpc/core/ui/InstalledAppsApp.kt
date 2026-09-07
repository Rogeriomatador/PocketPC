package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LaunchableAndroidApp(
    val label: String,
    val packageName: String,
    val activityName: String,
)

@Composable
fun InstalledAppsApp() {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableAndroidApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { queryLaunchableApps(context) }
    }

    val filtered = remember(apps, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(normalized, ignoreCase = true) ||
                    it.packageName.contains(normalized, ignoreCase = true)
            }
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

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Pesquisar aplicativos") },
        )

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
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_LAUNCHER)
                                        setClassName(app.packageName, app.activityName)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }.onFailure { error ->
                                status =
                                    "Falha ao abrir ${app.label}: " +
                                        (error.message ?: error.javaClass.simpleName)
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
                        Text(app.packageName, fontSize = 10.sp, maxLines = 1)
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
                ?.toString()
                ?.trim()
                .orEmpty()
            if (label.isBlank()) return@mapNotNull null

            LaunchableAndroidApp(
                label = label,
                packageName = info.packageName,
                activityName = info.name,
            )
        }
        .distinctBy { it.packageName to it.activityName }
        .sortedBy { it.label.lowercase() }
}
