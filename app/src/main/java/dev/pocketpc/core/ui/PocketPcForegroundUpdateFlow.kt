package dev.pocketpc.core.ui

import android.app.DownloadManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.BuildConfig
import dev.pocketpc.core.update.PocketPcInstallResult
import dev.pocketpc.core.update.PocketPcUpdateDownload
import dev.pocketpc.core.update.PocketPcUpdateManifest
import dev.pocketpc.core.update.PocketPcUpdater
import dev.pocketpc.core.update.cancelPocketPcReopenAfterUpdate
import dev.pocketpc.core.update.requestPocketPcReopenAfterUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ForegroundUpdatePhase {
    IDLE,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY,
    INSTALLING,
    BLOCKED,
}

@Composable
fun PocketPcForegroundUpdateFlow() {
    val context = LocalContext.current
    val updater =
        remember {
            PocketPcUpdater(
                context.applicationContext
            )
        }
    val scope = rememberCoroutineScope()

    var visible by remember {
        mutableStateOf(false)
    }
    var deferredThisSession by remember {
        mutableStateOf(false)
    }
    var manifest by remember {
        mutableStateOf<PocketPcUpdateManifest?>(
            null
        )
    }
    var download by remember {
        mutableStateOf<PocketPcUpdateDownload?>(
            null
        )
    }
    var verified by remember {
        mutableStateOf(false)
    }
    var continueInstallAfterDownload by remember {
        mutableStateOf(false)
    }
    var waitingUnknownSourcePermission by remember {
        mutableStateOf(false)
    }
    var phase by remember {
        mutableStateOf(
            ForegroundUpdatePhase.IDLE
        )
    }
    var status by remember {
        mutableStateOf<String?>(null)
    }

    suspend fun requestInstallNow(
        current: PocketPcUpdateDownload,
    ) {
        waitingUnknownSourcePermission = false
        phase = ForegroundUpdatePhase.INSTALLING
        status =
            "Preparando a instalação. O Android pode pedir uma confirmação de segurança."
        visible = true
        requestPocketPcReopenAfterUpdate(
            context.applicationContext
        )

        updater.requestInstall(current)
            .onSuccess { result ->
                when (result) {
                    PocketPcInstallResult.SESSION_COMMITTED -> {
                        phase =
                            ForegroundUpdatePhase.INSTALLING
                        status =
                            "Atualização entregue ao Android. O PocketPC tentará reabrir automaticamente quando a instalação terminar."
                    }

                    PocketPcInstallResult.SESSION_ALREADY_PENDING -> {
                        phase =
                            ForegroundUpdatePhase.INSTALLING
                        status =
                            "A instalação já está em andamento. O PocketPC tentará reabrir quando ela terminar."
                    }

                    PocketPcInstallResult.NEEDS_UNKNOWN_SOURCE_PERMISSION -> {
                        waitingUnknownSourcePermission = true
                        phase =
                            ForegroundUpdatePhase.READY
                        status =
                            "Autorize “Permitir desta fonte”. Ao voltar, o PocketPC continuará a instalação."
                    }
                }
            }
            .onFailure { error ->
                cancelPocketPcReopenAfterUpdate(
                    context.applicationContext
                )
                phase = ForegroundUpdatePhase.BLOCKED
                status =
                    "Não foi possível instalar: " +
                        (
                            error.message
                                ?: error.javaClass
                                    .simpleName
                            )
                visible = true
            }
    }

    suspend fun verifyDownloaded(
        current: PocketPcUpdateDownload,
    ) {
        phase = ForegroundUpdatePhase.VERIFYING
        status =
            "Download concluído. Verificando SHA-256, pacote, versão, revisão e assinatura..."

        updater.verifyPendingDownload()
            .onSuccess { verifiedDownload ->
                download = verifiedDownload
                verified = true
                phase = ForegroundUpdatePhase.READY
                status =
                    "Atualização verificada e pronta para instalar."

                if (continueInstallAfterDownload) {
                    requestInstallNow(
                        verifiedDownload
                    )
                } else if (!deferredThisSession) {
                    visible = true
                }
            }
            .onFailure { error ->
                verified = false
                phase = ForegroundUpdatePhase.BLOCKED
                status =
                    "A atualização baixada foi bloqueada: " +
                        (
                            error.message
                                ?: error.javaClass
                                    .simpleName
                            )
                if (!deferredThisSession) {
                    visible = true
                }
            }
    }

    fun beginForegroundDownload() {
        val target = manifest ?: return
        deferredThisSession = false
        continueInstallAfterDownload = true
        phase = ForegroundUpdatePhase.DOWNLOADING
        status = "Iniciando download..."
        visible = true

        updater.beginDownload(target)
            .onSuccess {
                download =
                    updater.queryPendingDownload()
                status =
                    "Baixando " +
                        target.versionName +
                        "..."
            }
            .onFailure { error ->
                continueInstallAfterDownload = false
                phase = ForegroundUpdatePhase.BLOCKED
                status =
                    "Falha ao iniciar download: " +
                        (
                            error.message
                                ?: error.javaClass
                                    .simpleName
                            )
            }
    }

    LaunchedEffect(Unit) {
        val pending =
            updater.queryPendingDownload()
        if (pending != null) {
            manifest = pending.manifest
            download = pending
            verified =
                updater.isPendingDownloadVerified()
            phase =
                when {
                    verified ->
                        ForegroundUpdatePhase.READY
                    pending.status ==
                        DownloadManager.STATUS_SUCCESSFUL ->
                        ForegroundUpdatePhase.VERIFYING
                    pending.status ==
                        DownloadManager.STATUS_FAILED ->
                        ForegroundUpdatePhase.BLOCKED
                    else ->
                        ForegroundUpdatePhase.DOWNLOADING
                }
            status =
                when (phase) {
                    ForegroundUpdatePhase.READY ->
                        "Uma atualização já está baixada e pronta para instalar."
                    ForegroundUpdatePhase.VERIFYING ->
                        "Uma atualização terminou de baixar e será verificada."
                    ForegroundUpdatePhase.BLOCKED ->
                        "O download anterior falhou."
                    else ->
                        "Existe uma atualização sendo baixada."
                }
            visible = true

            if (
                pending.status ==
                DownloadManager.STATUS_SUCCESSFUL &&
                !verified
            ) {
                verifyDownloaded(pending)
            }
        } else if (updater.autoCheckEnabled()) {
            updater.checkForUpdate()
                .onSuccess { result ->
                    updater.rememberManifest(
                        result.manifest
                    )
                    if (result.updateAvailable) {
                        manifest = result.manifest
                        phase =
                            ForegroundUpdatePhase.AVAILABLE
                        status =
                            "Nova versão disponível para o PocketPC."
                        visible = true
                    }
                }
        }
    }

    LaunchedEffect(download?.id) {
        val initial = download ?: return@LaunchedEffect
        if (
            initial.status ==
            DownloadManager.STATUS_SUCCESSFUL
        ) {
            if (!updater.isPendingDownloadVerified()) {
                verifyDownloaded(initial)
            }
            return@LaunchedEffect
        }

        while (true) {
            val refreshed =
                updater.queryPendingDownload()
                    ?: break
            download = refreshed

            when (refreshed.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    verifyDownloaded(refreshed)
                    break
                }

                DownloadManager.STATUS_FAILED -> {
                    continueInstallAfterDownload = false
                    phase =
                        ForegroundUpdatePhase.BLOCKED
                    status =
                        "O download da atualização falhou."
                    if (!deferredThisSession) {
                        visible = true
                    }
                    break
                }

                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    phase =
                        ForegroundUpdatePhase.DOWNLOADING
                }
            }

            delay(750)
        }
    }

    LaunchedEffect(
        waitingUnknownSourcePermission
    ) {
        while (waitingUnknownSourcePermission) {
            if (
                updater.canRequestPackageInstalls()
            ) {
                waitingUnknownSourcePermission = false
                val current = download
                if (current != null) {
                    requestInstallNow(current)
                }
                break
            }
            delay(750)
        }
    }

    if (!visible) {
        return
    }

    val currentManifest =
        download?.manifest ?: manifest
        ?: return
    val currentDownload = download
    val progress =
        currentDownload?.let { item ->
            if (item.totalBytes > 0L) {
                (
                    item.bytesDownloaded.toFloat() /
                        item.totalBytes.toFloat()
                    ).coerceIn(0f, 1f)
            } else {
                null
            }
        }

    AlertDialog(
        onDismissRequest = {
            if (
                phase !=
                ForegroundUpdatePhase.INSTALLING
            ) {
                deferredThisSession = true
                continueInstallAfterDownload = false
                visible = false
            }
        },
        title = {
            Text(
                when (phase) {
                    ForegroundUpdatePhase.AVAILABLE ->
                        "Nova atualização disponível"
                    ForegroundUpdatePhase.DOWNLOADING ->
                        "Atualizando PocketPC"
                    ForegroundUpdatePhase.VERIFYING ->
                        "Verificando atualização"
                    ForegroundUpdatePhase.READY ->
                        "Atualização pronta"
                    ForegroundUpdatePhase.INSTALLING ->
                        "Instalando atualização"
                    ForegroundUpdatePhase.BLOCKED ->
                        "Atualização interrompida"
                    ForegroundUpdatePhase.IDLE ->
                        "Atualização do PocketPC"
                }
            )
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    currentManifest.versionName,
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                Text(
                    "Instalado: " +
                        BuildConfig.VERSION_NAME
                )

                if (
                    currentManifest.notes.isNotBlank()
                ) {
                    Text(
                        currentManifest.notes,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }

                status?.let {
                    Text(it)
                }

                if (
                    phase ==
                    ForegroundUpdatePhase.DOWNLOADING
                ) {
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier =
                                Modifier.fillMaxWidth(),
                        )
                        Text(
                            formatForegroundUpdateBytes(
                                currentDownload
                                    ?.bytesDownloaded
                                    ?: 0L
                            ) +
                                " / " +
                                formatForegroundUpdateBytes(
                                    currentDownload
                                        ?.totalBytes
                                        ?: 0L
                                ) +
                                " • " +
                                (progress * 100f)
                                    .toInt() +
                                "%",
                            style =
                                MaterialTheme.typography
                                    .bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier =
                                Modifier.fillMaxWidth()
                        )
                    }
                }

                if (
                    phase ==
                        ForegroundUpdatePhase.VERIFYING ||
                    phase ==
                        ForegroundUpdatePhase.INSTALLING
                ) {
                    LinearProgressIndicator(
                        modifier =
                            Modifier.fillMaxWidth()
                    )
                }

                if (
                    phase ==
                    ForegroundUpdatePhase.AVAILABLE
                ) {
                    Text(
                        "Você pode atualizar agora ou continuar usando o PocketPC e deixar para a próxima abertura."
                    )
                }
            }
        },
        confirmButton = {
            when (phase) {
                ForegroundUpdatePhase.AVAILABLE ->
                    Button(
                        onClick =
                            ::beginForegroundDownload
                    ) {
                        Text("Atualizar agora")
                    }

                ForegroundUpdatePhase.DOWNLOADING ->
                    Button(
                        enabled =
                            !continueInstallAfterDownload,
                        onClick = {
                            deferredThisSession = false
                            continueInstallAfterDownload = true
                            status =
                                "O PocketPC instalará assim que o download e a verificação terminarem."
                        },
                    ) {
                        Text(
                            if (
                                continueInstallAfterDownload
                            ) {
                                "Baixando..."
                            } else {
                                "Atualizar ao concluir"
                            }
                        )
                    }

                ForegroundUpdatePhase.READY ->
                    Button(
                        onClick = {
                            val current = download
                                ?: return@Button
                            deferredThisSession = false
                            continueInstallAfterDownload = true
                            scope.launch {
                                requestInstallNow(
                                    current
                                )
                            }
                        },
                    ) {
                        Text("Instalar agora")
                    }

                ForegroundUpdatePhase.VERIFYING,
                ForegroundUpdatePhase.INSTALLING ->
                    Button(
                        enabled = false,
                        onClick = {},
                    ) {
                        Text(
                            if (
                                phase ==
                                ForegroundUpdatePhase.VERIFYING
                            ) {
                                "Verificando..."
                            } else {
                                "Instalando..."
                            }
                        )
                    }

                ForegroundUpdatePhase.BLOCKED ->
                    TextButton(
                        onClick = {
                            visible = false
                        }
                    ) {
                        Text("Fechar")
                    }

                ForegroundUpdatePhase.IDLE ->
                    Unit
            }
        },
        dismissButton = {
            if (
                phase !=
                    ForegroundUpdatePhase.INSTALLING &&
                phase !=
                    ForegroundUpdatePhase.BLOCKED
            ) {
                TextButton(
                    onClick = {
                        deferredThisSession = true
                        continueInstallAfterDownload = false
                        visible = false
                    }
                ) {
                    Text("Depois")
                }
            }
        },
    )
}

private fun formatForegroundUpdateBytes(
    value: Long,
): String {
    if (value <= 0L) return "0 B"
    if (value < 1024L) return "$value B"

    val kib = value / 1024.0
    if (kib < 1024.0) {
        return String.format("%.1f KiB", kib)
    }

    val mib = kib / 1024.0
    return String.format("%.1f MiB", mib)
}
