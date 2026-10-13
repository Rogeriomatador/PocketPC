package dev.pocketpc.core.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
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
import dev.pocketpc.core.runtime.GuestToolInstallManager
import dev.pocketpc.core.runtime.GuestToolPackageManager
import kotlinx.coroutines.launch
import java.io.File

private const val PAIRED_V52_PREFS =
    "pocketpc-paired-v52-offer"
private const val KEY_DISMISSED_RUNTIME_SHA256 =
    "dismissed-runtime-sha256"

@Composable
fun PocketPcPairedV52Prompt() {
    val appContext =
        LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val prefs =
        remember(appContext) {
            appContext.getSharedPreferences(
                PAIRED_V52_PREFS,
                Context.MODE_PRIVATE,
            )
        }
    val catalog =
        remember {
            PocketPcExperimentalRuntimeCatalog()
        }
    val packageManager =
        remember(appContext) {
            GuestToolPackageManager(appContext)
        }
    val installManager =
        remember(appContext) {
            GuestToolInstallManager(
                File(
                    appContext.noBackupFilesDir,
                    "runtime-tools",
                )
            )
        }
    val runtimeInstaller =
        remember(
            appContext,
            catalog,
            packageManager,
            installManager,
        ) {
            PocketPcExperimentalRuntimeInstaller(
                context = appContext,
                catalog = catalog,
                packages = packageManager,
                installer = installManager,
            )
        }

    var offer by
        remember {
            mutableStateOf<
                PocketPcExperimentalRuntimeOffer?
            >(null)
        }
    var visible by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var installedVersion by
        remember {
            mutableStateOf<String?>(null)
        }
    var installError by
        remember {
            mutableStateOf<String?>(null)
        }

    LaunchedEffect(Unit) {
        catalog.fetchPairedV52Offer()
            .onSuccess { candidate ->
                offer = candidate
                if (candidate != null) {
                    val dismissed =
                        prefs.getString(
                            KEY_DISMISSED_RUNTIME_SHA256,
                            null,
                        )
                    visible =
                        !candidate.sha256.equals(
                            dismissed,
                            ignoreCase = true,
                        )
                }
            }
    }

    val current = offer
    if (!visible || current == null) {
        return
    }

    fun dismissCurrentOffer() {
        if (installing) return
        prefs.edit()
            .putString(
                KEY_DISMISSED_RUNTIME_SHA256,
                current.sha256,
            )
            .apply()
        visible = false
    }

    AlertDialog(
        onDismissRequest = {
            if (!installing) {
                dismissCurrentOffer()
            }
        },
        title = {
            Text("Wine v52 disponível para teste")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Esta oferta experimental foi pareada com a revisão " +
                        current.pocketPcSourceRevision.take(12) +
                        "… deste APK.",
                    style =
                        MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "O pacote será validado por tamanho, SHA-256, manifesto e arquivos antes da instalação. A instalação não ativa o Vulkan Present v52 automaticamente.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
                Text(
                    "ABI Vulkan privada: ${current.wineVulkanAbi} • Runtime/Physical/Roblox: NOT_EXECUTED até existir evidência real.",
                    style =
                        MaterialTheme.typography.labelSmall,
                )

                if (installing) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Baixando e verificando Wine v52…",
                        style =
                            MaterialTheme.typography.bodySmall,
                    )
                }

                installedVersion?.let { version ->
                    Text(
                        "Wine $version instalado e verificado. Abra Runtimes e ative o toggle v52 apenas para o teste experimental.",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.primary,
                    )
                }

                installError?.let { error ->
                    Text(
                        "Falha ao instalar: $error",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (installedVersion != null) {
                TextButton(
                    onClick = ::dismissCurrentOffer,
                ) {
                    Text("Fechar")
                }
            } else {
                TextButton(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        installError = null
                        scope.launch {
                            runtimeInstaller
                                .installPairedV52()
                                .onSuccess { installed ->
                                    installedVersion =
                                        installed.manifest.version
                                    prefs.edit()
                                        .putString(
                                            KEY_DISMISSED_RUNTIME_SHA256,
                                            current.sha256,
                                        )
                                        .apply()
                                }
                                .onFailure { failure ->
                                    installError =
                                        failure.message
                                            ?: failure.javaClass.simpleName
                                }
                            installing = false
                        }
                    },
                ) {
                    Text("Instalar Wine v52")
                }
            }
        },
        dismissButton = {
            if (!installing && installedVersion == null) {
                TextButton(
                    onClick = ::dismissCurrentOffer,
                ) {
                    Text("Agora não")
                }
            }
        },
    )
}
