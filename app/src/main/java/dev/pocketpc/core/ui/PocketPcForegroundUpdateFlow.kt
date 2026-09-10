package dev.pocketpc.core.ui

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
            "Preparando a atualização. Continue aqui; o PocketPC fará o restante automaticamente."
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
                            "Instalação iniciada. Se o Android pedir confirmação, confirme uma única vez. Depois disso o PocketPC tentará retornar sozinho."
                    }

                    PocketPcInstallResult.SESSION_ALREADY_PENDING -> {
                        phase =
                            ForegroundUpdatePhase.INSTALLING
                        status =
                            "A instalação já está em andamento. O PocketPC está aguardando a conclusão para retornar."
                    }

                    PocketPcInstallResult.NEEDS_UNKNOWN_SOURCE_PERMISSION -> {
                        waitingUnknownSourcePermission = true
                        phase =
                            ForegroundUpdatePhase.INSTALLING
                        status =
                            "O Android precisa liberar esta fonte uma vez. Ative “Permitir desta fonte” e volte: a atualização continuará automaticamente."
                        visible = true
                    }
                }
            }
            .onFailure { error ->
                cancelPocketPcReopenAfterUpdate(
                    context.applicationContext
                )
                continueInstallAfterDownload = false
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
            "Download concluído. Verificando integridade, pacote, versão, origem e assinatura..."
        visible = true

        updater.verifyPendingDownload()
            .onSuccess { verifiedDownload ->
                download = verifiedDownload
                verified = true
                phase = ForegroundUpdatePhase.READY
                status =
                    "Atualização verificada com segurança."

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
                continueInstallAfterDownload = false
                phase = ForegroundUpdatePhase.BLOCKED
                status =
                    "A atualização baixada foi bloqueada: " +
                        (
                            error.message
                                ?: error.javaClass
                                    .simpleName
                            )
                visible = true
            }
    }

    fun beginForegroundDownload() {
        val target = manifest ?: return
        deferredThisSession = false
        continueInstallAfterDownload = true
        phase = ForegroundUpdatePhase.DOWNLOADING
        status =
            "Baixando a atualização. Não é necessário pressionar mais nada no PocketPC."
        visible = true

        updater.beginDownload(target)
            .onSuccess {
                download =
                    updater.queryPendingDownload()
                status =
                    "Baixando " +
                        target.versionName +
                        "... O PocketPC verificará e continuará automaticamente."
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
                visible = true
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
                        "A atualização já está baixada e verificada. Toque em Atualizar agora para concluir."
                    ForegroundUpdatePhase.VERIFYING ->
                        "A atualização terminou de baixar e está sendo verificada."
                    ForegroundUpdatePhase.BLOCKED ->
                        "O download anterior falhou."
                    else ->
                        "Existe uma atualização sendo baixada. O progresso continuará visível aqui."
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
                            "Nova versão disponível. Um toque em Atualizar agora inicia todo o processo."
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
                    visible = true
                    break
                }

                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    phase =
                        ForegroundUpdatePhase.DOWNLOADING
                    visible = true
                }
            }

            delay(500)
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
            delay(500)
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
    val isBusy =
        phase == ForegroundUpdatePhase.DOWNLOADING ||
            phase == ForegroundUpdatePhase.VERIFYING ||
            phase == ForegroundUpdatePhase.INSTALLING

    Dialog(
        onDismissRequest = {
            if (!isBusy) {
                deferredThisSession = true
                continueInstallAfterDownload = false
                visible = false
            }
        },
        properties =
            DialogProperties(
                dismissOnBackPress = !isBusy,
                dismissOnClickOutside = !isBusy,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.scrim
                            .copy(alpha = 0.72f)
                    )
                    .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement =
                        Arrangement.spacedBy(14.dp),
                ) {
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
                        },
                        style =
                            MaterialTheme.typography
                                .headlineSmall,
                    )

                    Text(
                        currentManifest.versionName,
                        style =
                            MaterialTheme.typography
                                .titleMedium,
                    )
                    Text(
                        "Versão instalada: " +
                            BuildConfig.VERSION_NAME,
                        style =
                            MaterialTheme.typography
                                .bodySmall,
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

                    UpdatePipelineStatus(
                        phase = phase
                    )

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

                    Spacer(
                        modifier = Modifier.height(2.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (phase) {
                            ForegroundUpdatePhase.AVAILABLE -> {
                                TextButton(
                                    onClick = {
                                        deferredThisSession = true
                                        continueInstallAfterDownload = false
                                        visible = false
                                    }
                                ) {
                                    Text("Depois")
                                }
                                Button(
                                    onClick =
                                        ::beginForegroundDownload
                                ) {
                                    Text("Atualizar agora")
                                }
                            }

                            ForegroundUpdatePhase.READY -> {
                                TextButton(
                                    onClick = {
                                        deferredThisSession = true
                                        continueInstallAfterDownload = false
                                        visible = false
                                    }
                                ) {
                                    Text("Depois")
                                }
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
                                    Text("Atualizar agora")
                                }
                            }

                            ForegroundUpdatePhase.BLOCKED ->
                                Button(
                                    onClick = {
                                        visible = false
                                    }
                                ) {
                                    Text("Fechar")
                                }

                            ForegroundUpdatePhase.DOWNLOADING,
                            ForegroundUpdatePhase.VERIFYING,
                            ForegroundUpdatePhase.INSTALLING ->
                                Text(
                                    "Mantenha o PocketPC aberto. O processo continua automaticamente.",
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                )

                            ForegroundUpdatePhase.IDLE ->
                                Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePipelineStatus(
    phase: ForegroundUpdatePhase,
) {
    val currentStep =
        when (phase) {
            ForegroundUpdatePhase.AVAILABLE -> 0
            ForegroundUpdatePhase.DOWNLOADING -> 1
            ForegroundUpdatePhase.VERIFYING -> 2
            ForegroundUpdatePhase.READY -> 3
            ForegroundUpdatePhase.INSTALLING -> 4
            ForegroundUpdatePhase.BLOCKED -> -1
            ForegroundUpdatePhase.IDLE -> 0
        }

    val steps =
        listOf(
            "1. Download",
            "2. Verificação",
            "3. Preparação",
            "4. Instalação",
        )

    Column(
        verticalArrangement =
            Arrangement.spacedBy(4.dp)
    ) {
        steps.forEachIndexed { index, label ->
            val marker =
                when {
                    currentStep < 0 -> "•"
                    index < currentStep -> "✓"
                    index == currentStep -> "›"
                    else -> "·"
                }
            Text(
                "$marker $label",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }
    }
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
