package dev.pocketpc.core.ui

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.update.PocketPcInstallResult
import dev.pocketpc.core.update.PocketPcUpdateCheck
import dev.pocketpc.core.update.PocketPcUpdateDownload
import dev.pocketpc.core.storage.PocketPcProfileBackup
import dev.pocketpc.core.storage.StorageRepository
import dev.pocketpc.core.update.PocketPcUpdater
import dev.pocketpc.core.update.shouldAutoInstallUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateCenterApp() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val updater =
        remember {
            PocketPcUpdater(
                context.applicationContext
            )
        }
    val storage =
        remember {
            StorageRepository(
                context.applicationContext
            )
        }
    val profileBackup =
        remember {
            PocketPcProfileBackup(
                context = context.applicationContext,
                storage = storage,
            )
        }
    val scope = rememberCoroutineScope()

    var notificationsAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            notificationsAllowed = granted
        }

    var check by remember {
        mutableStateOf<PocketPcUpdateCheck?>(
            null
        )
    }
    var pending by remember {
        mutableStateOf<PocketPcUpdateDownload?>(
            updater.queryPendingDownload()
        )
    }
    var busy by remember {
        mutableStateOf(false)
    }
    var verified by remember {
        mutableStateOf(
            updater.isPendingDownloadVerified()
        )
    }
    var autoCheck by remember {
        mutableStateOf(
            updater.autoCheckEnabled()
        )
    }
    var autoDownload by remember {
        mutableStateOf(
            updater.autoDownloadUnmeteredEnabled()
        )
    }
    var autoInstall by remember {
        mutableStateOf(
            updater.autoInstallVerifiedEnabled()
        )
    }
    var unmetered by remember {
        mutableStateOf(
            updater.isUnmeteredNetwork()
        )
    }
    var status by remember {
        mutableStateOf<String?>(null)
    }

    suspend fun refreshCheck() {
        busy = true
        unmetered = updater.isUnmeteredNetwork()
        status = "Verificando atualizações..."
        updater.checkForUpdate()
            .onSuccess { result ->
                check = result
                updater.rememberManifest(
                    result.manifest
                )
                status =
                    when {
                        result.updateAvailable ->
                            "Nova versão disponível."
                        result.manifest.published ->
                            "PocketPC está atualizado."
                        else ->
                            "Canal configurado, mas ainda sem APK publicado."
                    }
            }
            .onFailure { error ->
                status =
                    "Falha ao verificar: " +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                            )
            }
        busy = false
    }

    LaunchedEffect(Unit) {
        refreshCheck()
    }

    LaunchedEffect(
        pending?.id,
        pending?.status,
    ) {
        val current = pending ?: return@LaunchedEffect
        if (
            current.status !=
                DownloadManager.STATUS_PENDING &&
            current.status !=
                DownloadManager.STATUS_RUNNING &&
            current.status !=
                DownloadManager.STATUS_PAUSED
        ) {
            return@LaunchedEffect
        }

        while (true) {
            val refreshed =
                withContext(Dispatchers.IO) {
                    updater.queryPendingDownload()
                }
                    ?: break
            pending = refreshed
            verified =
                updater.isPendingDownloadVerified()

            if (
                refreshed.status !=
                    DownloadManager.STATUS_PENDING &&
                refreshed.status !=
                    DownloadManager.STATUS_RUNNING &&
                refreshed.status !=
                    DownloadManager.STATUS_PAUSED
            ) {
                break
            }
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Atualizações",
                    style =
                        MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Canal estável • atualização assinada",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        refreshCheck()
                    }
                },
            ) {
                Text("Verificar agora")
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Automação",
                    style =
                        MaterialTheme.typography.titleSmall,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "Verificar automaticamente",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                        )
                        Text(
                            "No máximo uma consulta automática a cada 6 horas.",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = { enabled ->
                            autoCheck = enabled
                            updater
                                .setAutoCheckEnabled(
                                    enabled
                                )
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "Baixar automaticamente",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                        )
                        Text(
                            if (unmetered) {
                                "Rede não medida detectada. Updates podem baixar sozinhos."
                            } else {
                                "Só baixa sozinho em rede não medida; dados móveis não são usados automaticamente."
                            },
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoDownload,
                        onCheckedChange = { enabled ->
                            autoDownload = enabled
                            updater
                                .setAutoDownloadUnmeteredEnabled(
                                    enabled
                                )
                        },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment =
                        Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f)
                    ) {
                        Text(
                            "Instalar automaticamente",
                            style =
                                MaterialTheme
                                    .typography
                                    .bodyMedium,
                        )
                        Text(
                            if (
                                updater
                                    .canRequestPackageInstalls()
                            ) {
                                "Após SHA-256, pacote, revisão e assinatura passarem, o PocketPC tenta atualizar a si próprio."
                            } else {
                                "Ative uma vez “Permitir desta fonte”. Depois o PocketPC pode tentar instalar updates sozinho."
                            },
                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoInstall,
                        onCheckedChange = { enabled ->
                            autoInstall = enabled
                            updater
                                .setAutoInstallVerifiedEnabled(
                                    enabled
                                )
                        },
                    )
                }

                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f)
                        ) {
                            Text(
                                "Aviso de confirmação",
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodyMedium,
                            )
                            Text(
                                if (notificationsAllowed) {
                                    "Permitido. Se o Android exigir ação, o PocketPC pode mostrar um aviso de instalação."
                                } else {
                                    "Bloqueado. Em segundo plano, uma confirmação exigida pelo Android pode não aparecer."
                                },
                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                            )
                        }

                        if (notificationsAllowed) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        "Ativo",
                                        fontSize = 9.sp,
                                    )
                                },
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    notificationPermissionLauncher
                                        .launch(
                                            Manifest.permission
                                                .POST_NOTIFICATIONS
                                        )
                                },
                            ) {
                                Text("Permitir")
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Perfil e migração",
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                Text(
                    if (storage.rootUriString != null) {
                        "Salva tema, pins, geometria das janelas e perfis de jogos em P:\\Backups."
                    } else {
                        "Conecte o PocketDrive para criar um backup fora do C: interno."
                    },
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled =
                            !busy &&
                                storage.rootUriString != null,
                        onClick = {
                            busy = true
                            scope.launch {
                                profileBackup.create()
                                    .onSuccess { backup ->
                                        status =
                                            "Backup criado em P:\\Backups: " +
                                                backup.entry.name +
                                                " (" +
                                                backup.keys +
                                                " configuração(ões))."
                                    }
                                    .onFailure { error ->
                                        status =
                                            "Falha no backup: " +
                                                (
                                                    error.message
                                                        ?: error.javaClass
                                                            .simpleName
                                                    )
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Criar backup em P:")
                    }

                    OutlinedButton(
                        enabled =
                            !busy &&
                                storage.rootUriString != null,
                        onClick = {
                            busy = true
                            scope.launch {
                                profileBackup
                                    .restoreLatest()
                                    .onSuccess { restored ->
                                        status =
                                            "Perfil restaurado de " +
                                                restored.fileName +
                                                ". Reabra o PocketPC para aplicar tudo."
                                    }
                                    .onFailure { error ->
                                        status =
                                            "Falha ao restaurar: " +
                                                (
                                                    error.message
                                                        ?: error.javaClass
                                                            .simpleName
                                                    )
                                    }
                                busy = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Restaurar último")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            UpdateInfoCard(
                title = "Instalado",
                value = BuildConfig.VERSION_NAME,
                detail =
                    "versionCode " +
                        BuildConfig.VERSION_CODE,
                modifier = Modifier.weight(1f),
            )
            UpdateInfoCard(
                title = "Origem",
                value =
                    if (
                        BuildConfig
                            .POCKETPC_SOURCE_REVISION_PINNED
                    ) {
                        BuildConfig
                            .POCKETPC_SOURCE_REVISION
                            .take(12)
                    } else {
                        "LOCAL"
                    },
                detail = "revisão embutida",
                modifier = Modifier.weight(1f),
            )
            UpdateInfoCard(
                title = "Segurança",
                value = "Fail-closed",
                detail =
                    "SHA-256 + package + assinatura",
                modifier = Modifier.weight(1f),
            )
        }

        status?.let {
            Text(
                it,
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }

        if (busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth()
            )
        }

        check?.let { result ->
            val manifest = result.manifest

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (result.updateAvailable) {
                            "Disponível: " +
                                manifest.versionName
                        } else {
                            "Canal: " +
                                manifest.channel
                        },
                        style =
                            MaterialTheme.typography
                                .titleSmall,
                    )
                    ValueRow(
                        "Publicado",
                        if (manifest.published) {
                            "SIM"
                        } else {
                            "não"
                        },
                    )
                    ValueRow(
                        "Version code",
                        manifest.versionCode
                            .toString(),
                    )
                    ValueRow(
                        "Android mínimo",
                        "API " + manifest.minApi,
                    )
                    if (
                        manifest.sourceRevision
                            .isNotBlank()
                    ) {
                        ValueRow(
                            "Source",
                            manifest.sourceRevision
                                .take(12),
                        )
                    }
                    if (manifest.notes.isNotBlank()) {
                        Text(
                            manifest.notes,
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    }

                    if (
                        result.updateAvailable &&
                        pending == null
                    ) {
                        Button(
                            onClick = {
                                updater.beginDownload(
                                    manifest
                                )
                                    .onSuccess {
                                        verified = false
                                        pending =
                                            updater
                                                .queryPendingDownload()
                                        status =
                                            "Download iniciado."
                                    }
                                    .onFailure { error ->
                                        status =
                                            "Falha no download: " +
                                                (
                                                    error.message
                                                        ?: error
                                                            .javaClass
                                                            .simpleName
                                                    )
                                    }
                            },
                        ) {
                            Text("Baixar atualização")
                        }
                    }

                    if (
                        manifest.published &&
                        manifest.apkUrl.startsWith("https://")
                    ) {
                        OutlinedButton(
                            onClick = {
                                uriHandler.openUri(
                                    manifest.apkUrl
                                )
                            },
                        ) {
                            Text("Baixar pelo GitHub")
                        }
                        Text(
                            "O download abre no navegador. A verificação seguinte pertence ao Android/Google Play Protect e pode demorar em uma conexão lenta.",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                }
            }
        }

        pending?.let { download ->
            DownloadUpdateCard(
                download = download,
                verified = verified,
                onVerify = {
                    busy = true
                    scope.launch {
                        updater
                            .verifyPendingDownload()
                            .onSuccess {
                                verified = true
                                status =
                                    "APK verificado e pronto para instalar."
                            }
                            .onFailure { error ->
                                verified = false
                                status =
                                    "Atualização bloqueada: " +
                                        (
                                            error.message
                                                ?: error
                                                    .javaClass
                                                    .simpleName
                                            )
                            }
                        busy = false
                    }
                },
                onInstall = {
                    if (!verified) {
                        status =
                            "Verifique o APK antes de instalar."
                        return@DownloadUpdateCard
                    }

                    busy = true
                    scope.launch {
                        updater.requestInstall(
                            download
                        )
                            .onSuccess { result ->
                                status =
                                    when (result) {
                                        PocketPcInstallResult
                                            .SESSION_COMMITTED ->
                                            "Atualização entregue ao instalador do Android. Se a plataforma permitir, será aplicada sem outra ação."
                                        PocketPcInstallResult
                                            .SESSION_ALREADY_PENDING ->
                                            "Esta atualização já foi entregue ao Android e está aguardando conclusão."
                                        PocketPcInstallResult
                                            .NEEDS_UNKNOWN_SOURCE_PERMISSION ->
                                            "Autorize o PocketPC a instalar atualizações e volte aqui."
                                    }
                            }
                            .onFailure { error ->
                                status =
                                    "Falha ao abrir instalador: " +
                                        (
                                            error.message
                                                ?: error
                                                    .javaClass
                                                    .simpleName
                                            )
                            }
                        busy = false
                    }
                },
                onClear = {
                    val manager =
                        context.getSystemService(
                            android.content.Context
                                .DOWNLOAD_SERVICE
                        ) as DownloadManager
                    manager.remove(download.id)
                    updater.clearPendingDownload()
                    pending = null
                    verified = false
                    status =
                        "Download de atualização removido."
                },
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 1.dp,
        ) {
            Text(
                "Android comum não permite que um app sideloaded " +
                    "se substitua silenciosamente. O PocketPC pode " +
                    "detectar, baixar e verificar tudo sozinho; a etapa " +
                    "final pertence ao instalador do sistema e ao Google " +
                    "Play Protect. Essa tela externa pode demorar se a " +
                    "conexão estiver lenta.",
                modifier = Modifier.padding(12.dp),
                style =
                    MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UpdateInfoCard(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 74.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement =
                Arrangement.spacedBy(3.dp),
        ) {
            Text(
                title,
                fontSize = 9.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
            Text(
                value,
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            Text(
                detail,
                fontSize = 8.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadUpdateCard(
    download: PocketPcUpdateDownload,
    verified: Boolean,
    onVerify: () -> Unit,
    onInstall: () -> Unit,
    onClear: () -> Unit,
) {
    val progress =
        if (download.totalBytes > 0L) {
            (
                download.bytesDownloaded
                    .toFloat() /
                    download.totalBytes
                        .toFloat()
                ).coerceIn(0f, 1f)
        } else {
            null
        }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement =
                Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "Download da atualização",
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            ValueRow(
                "Versão",
                download.manifest.versionName,
            )
            ValueRow(
                "Status",
                updateDownloadStatus(
                    download.status
                ),
            )

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier =
                        Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatBytes(download.bytesDownloaded)} / " +
                        formatBytes(download.totalBytes),
                    fontSize = 9.sp,
                )
            }

            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp),
            ) {
                if (
                    download.status ==
                    DownloadManager
                        .STATUS_SUCCESSFUL
                ) {
                    OutlinedButton(
                        onClick = onVerify,
                    ) {
                        Text(
                            if (verified) {
                                "Verificado ✓"
                            } else {
                                "Verificar APK"
                            }
                        )
                    }

                    Button(
                        onClick = onInstall,
                        enabled = verified,
                    ) {
                        Text("Instalar")
                    }
                }

                TextButton(
                    onClick = onClear,
                ) {
                    Text("Remover")
                }
            }
        }
    }
}

private fun updateDownloadStatus(
    status: Int,
): String =
    when (status) {
        DownloadManager.STATUS_PENDING ->
            "Aguardando"
        DownloadManager.STATUS_RUNNING ->
            "Baixando"
        DownloadManager.STATUS_PAUSED ->
            "Pausado"
        DownloadManager.STATUS_SUCCESSFUL ->
            "Concluído"
        DownloadManager.STATUS_FAILED ->
            "Falhou"
        else ->
            "Desconhecido"
    }

enum class PocketPcUpdateAttention {
    NONE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    BLOCKED,
}

@Composable
fun PocketPcUpdateAutoCheck(
    onAttentionChanged:
        (PocketPcUpdateAttention) -> Unit = {},
) {
    val context = LocalContext.current
    val updater =
        remember {
            PocketPcUpdater(
                context.applicationContext
            )
        }

    LaunchedEffect(Unit) {
        suspend fun attemptAutomaticInstall(
            download: PocketPcUpdateDownload,
        ): Boolean {
            val enabled =
                updater.autoInstallVerifiedEnabled()
            val canInstall =
                updater.canRequestPackageInstalls()
            val alreadyAttempted =
                updater.installAttemptedForPending()

            if (
                !shouldAutoInstallUpdate(
                    enabled = enabled,
                    verified = true,
                    canInstallPackages = canInstall,
                    alreadyAttempted =
                        alreadyAttempted,
                )
            ) {
                if (
                    enabled &&
                    !canInstall
                ) {
                    onAttentionChanged(
                        PocketPcUpdateAttention.READY
                    )
                    Toast.makeText(
                        context,
                        "Update verificado. Autorize uma vez o PocketPC em “Instalar apps desconhecidos” para permitir atualização sem PC.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return alreadyAttempted
            }

            var committed = false
            updater.requestInstall(download)
                .onSuccess { result ->
                    when (result) {
                        PocketPcInstallResult
                            .SESSION_COMMITTED,
                        PocketPcInstallResult
                            .SESSION_ALREADY_PENDING -> {
                            committed = true
                            Toast.makeText(
                                context,
                                "PocketPC entregou a atualização ao Android. Ela será aplicada automaticamente se a plataforma permitir.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }

                        PocketPcInstallResult
                            .NEEDS_UNKNOWN_SOURCE_PERMISSION ->
                            Unit
                    }
                }
                .onFailure { error ->
                    onAttentionChanged(
                        PocketPcUpdateAttention.BLOCKED
                    )
                    Toast.makeText(
                        context,
                        "Instalação automática bloqueada: " +
                            (
                                error.message
                                    ?: error.javaClass
                                        .simpleName
                                ),
                        Toast.LENGTH_LONG,
                    ).show()
                }

            return committed
        }

        suspend fun verifyReadyDownload():
            Boolean {
            val pending =
                updater.queryPendingDownload()
                    ?: return false

            if (
                pending.status !=
                    DownloadManager
                        .STATUS_SUCCESSFUL
            ) {
                return false
            }

            if (
                updater.isPendingDownloadVerified()
            ) {
                onAttentionChanged(
                    PocketPcUpdateAttention.READY
                )
                attemptAutomaticInstall(pending)
                return true
            }

            updater.verifyPendingDownload()
                .onSuccess { verified ->
                    onAttentionChanged(
                        PocketPcUpdateAttention.READY
                    )
                    val autoInstall =
                        updater
                            .autoInstallVerifiedEnabled()

                    Toast.makeText(
                        context,
                        "PocketPC " +
                            verified.manifest.versionName +
                            " baixado e verificado." +
                            if (autoInstall) {
                                " Tentando aplicar automaticamente."
                            } else {
                                " Abra Este PC > Atualizações para instalar."
                            },
                        Toast.LENGTH_LONG,
                    ).show()

                    if (autoInstall) {
                        attemptAutomaticInstall(
                            verified
                        )
                    }
                }
                .onFailure { error ->
                    onAttentionChanged(
                        PocketPcUpdateAttention.BLOCKED
                    )
                    Toast.makeText(
                        context,
                        "Atualização baixada foi bloqueada: " +
                            (
                                error.message
                                    ?: error.javaClass
                                        .simpleName
                                ),
                        Toast.LENGTH_LONG,
                    ).show()
                }

            return updater
                .isPendingDownloadVerified()
        }

        verifyReadyDownload()

        var pending =
            updater.queryPendingDownload()

        when {
            updater.isPendingDownloadVerified() ->
                onAttentionChanged(
                    PocketPcUpdateAttention.READY
                )
            pending != null ->
                onAttentionChanged(
                    PocketPcUpdateAttention.DOWNLOADING
                )
            else ->
                onAttentionChanged(
                    PocketPcUpdateAttention.NONE
                )
        }

        if (
            updater.shouldRunAutomaticCheck()
        ) {
            updater.markAutomaticCheck()

            updater.checkForUpdate()
                .onSuccess { result ->
                    updater.rememberManifest(
                        result.manifest
                    )

                    if (
                        result.updateAvailable &&
                        pending == null
                    ) {
                        val canAutoDownload =
                            updater
                                .autoDownloadUnmeteredEnabled() &&
                                updater
                                    .isUnmeteredNetwork()

                        if (canAutoDownload) {
                            updater.beginDownload(
                                result.manifest
                            )
                                .onSuccess {
                                    onAttentionChanged(
                                        PocketPcUpdateAttention.DOWNLOADING
                                    )
                                    pending =
                                        updater
                                            .queryPendingDownload()
                                    Toast.makeText(
                                        context,
                                        "PocketPC " +
                                            result.manifest
                                                .versionName +
                                            " será baixado automaticamente.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                .onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        "Update encontrado, mas o download automático falhou: " +
                                            (
                                                error.message
                                                    ?: error
                                                        .javaClass
                                                        .simpleName
                                                ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        } else {
                            Toast.makeText(
                                context,
                                "PocketPC " +
                                    result.manifest
                                        .versionName +
                                    " disponível em Este PC > Atualizações.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } else if (
                        !result.updateAvailable &&
                        pending == null
                    ) {
                        onAttentionChanged(
                            PocketPcUpdateAttention.NONE
                        )
                    }
                }
                .onFailure {
                    if (pending == null) {
                        onAttentionChanged(
                            PocketPcUpdateAttention.NONE
                        )
                    }
                }
        }

        var attempts = 0
        while (
            pending != null &&
            attempts < 360
        ) {
            val status =
                pending.status

            if (
                status ==
                    DownloadManager.STATUS_SUCCESSFUL
            ) {
                verifyReadyDownload()
                break
            }

            if (
                status ==
                    DownloadManager.STATUS_FAILED
            ) {
                onAttentionChanged(
                    PocketPcUpdateAttention.BLOCKED
                )
                break
            }

            delay(5_000)
            attempts++
            pending =
                updater.queryPendingDownload()
        }
    }
}

