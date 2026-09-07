package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketpc.core.research.PocketPcResearchProbe
import dev.pocketpc.core.research.PocketPcResearchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ResearchLabApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember {
        mutableStateOf<PocketPcResearchReport?>(
            null
        )
    }
    var running by remember {
        mutableStateOf(false)
    }
    var error by remember {
        mutableStateOf<String?>(null)
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
                    "Laboratório PocketPC",
                    style =
                        MaterialTheme.typography
                            .titleLarge,
                )
                Text(
                    "Probes públicos e não destrutivos para descobrir " +
                        "capacidades reais do aparelho.",
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            Button(
                enabled = !running,
                onClick = {
                    running = true
                    error = null
                    scope.launch {
                        runCatching {
                            withContext(
                                Dispatchers.Default
                            ) {
                                PocketPcResearchProbe
                                    .collect(
                                        context
                                    )
                            }
                        }
                            .onSuccess {
                                report = it
                            }
                            .onFailure {
                                error =
                                    it.message
                                        ?: it.javaClass
                                            .simpleName
                            }
                        running = false
                    }
                },
            ) {
                Text("Executar laboratório")
            }
        }

        if (running) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth()
            )
        }

        error?.let {
            Text(
                "Falha: $it",
                color =
                    MaterialTheme.colorScheme.error,
            )
        }

        val current = report

        if (current == null) {
            ResearchCard(
                title = "Nada executado ainda",
            ) {
                Text(
                    "O laboratório não marca suporte como PASS " +
                        "até um probe real rodar no aparelho.",
                    style =
                        MaterialTheme.typography
                            .bodySmall,
                )
            }
            return@Column
        }

        ResearchCard(
            title = "Virtual Display privado",
        ) {
            ValueRow(
                "Resultado",
                if (
                    current.virtualDisplay.created
                ) {
                    "PASS"
                } else {
                    "FAIL / indisponível"
                },
            )
            ValueRow(
                "Display ID temporário",
                current.virtualDisplay
                    .displayId
                    ?.toString()
                    ?: "—",
            )
            Text(
                current.virtualDisplay.detail,
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Encoders para desktop remoto",
        ) {
            ValueRow(
                "Preferido",
                current.preferredRemoteCodec
                    .label,
            )
            ValueRow(
                "Perfil anunciado",
                current.advertisedRemoteStreamProfile
                    .label,
            )
            Text(
                "Perfil anunciado pelo codec; não é benchmark de FPS/latência.",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
            ValueRow(
                "Encoders detectados",
                current.encoders.size
                    .toString(),
            )

            current.encoders
                .take(12)
                .forEach { codec ->
                    ValueRow(
                        codec.mimeType,
                        buildString {
                            append(codec.name)
                            codec.hardwareAccelerated
                                ?.let {
                                    append(
                                        if (it) {
                                            " • HW"
                                        } else {
                                            " • SW"
                                        }
                                    )
                                }
                            if (codec.surfaceInput) {
                                append(" • Surface")
                            }
                            codec.vendor
                                ?.takeIf { it }
                                ?.let {
                                    append(" • vendor")
                                }
                            if (codec.supports1080p60) {
                                append(" • 1080p60")
                            } else if (codec.supports720p60) {
                                append(" • 720p60")
                            }
                            if (codec.supports1440p60) {
                                append(" • 1440p60")
                            }
                            if (codec.supports4k30) {
                                append(" • 4K30")
                            }
                            if (codec.intraRefresh) {
                                append(" • intra-refresh")
                            }
                        },
                    )
                }

            if (current.encoders.size > 12) {
                Text(
                    "+ ${current.encoders.size - 12} " +
                        "encoder(s)",
                    fontSize = 9.sp,
                )
            }
        }

        ResearchCard(
            title = "Pipeline gráfico sem cópia pela CPU",
        ) {
            ValueRow(
                "HardwareBuffer",
                when {
                    current.hardwareBuffer.allocated ->
                        "ALLOCATED"
                    current.hardwareBuffer.supported ->
                        "SUPPORTED / ALLOCATION FAILED"
                    else ->
                        "NOT SUPPORTED"
                },
            )
            ValueRow(
                "Encoder HW + Surface",
                if (
                    current
                        .hardwareSurfaceEncoderAvailable
                ) {
                    "DISPONÍVEL"
                } else {
                    "NÃO CONFIRMADO"
                },
            )
            Text(
                current.hardwareBuffer.detail,
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Virtual Device / Companion",
        ) {
            ValueRow(
                "Companion setup",
                passOrNotAdvertised(
                    current.companionDeviceSetup
                ),
            )
            ValueRow(
                "VirtualDeviceManager",
                if (
                    current
                        .virtualDeviceManagerAvailable
                ) {
                    "SERVICE AVAILABLE"
                } else {
                    "SERVICE UNAVAILABLE"
                },
            )
            ValueRow(
                "CREATE_VIRTUAL_DEVICE",
                if (
                    current
                        .createVirtualDevicePermissionGranted
                ) {
                    "GRANTED"
                } else {
                    "NOT GRANTED"
                },
            )
            ValueRow(
                "Computer Control",
                if (
                    current
                        .computerControlPermissionGranted
                ) {
                    "GRANTED"
                } else {
                    "NOT GRANTED"
                },
            )
            Text(
                "O serviço pode existir no sistema sem o PocketPC " +
                    "ter a função privilegiada necessária para criar " +
                    "um VirtualDevice confiável.",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Conectividade direta",
        ) {
            ValueRow(
                "Wi‑Fi Direct",
                passOrNotAdvertised(
                    current.wifiDirect
                ),
            )
            ValueRow(
                "Wi‑Fi Aware",
                passOrNotAdvertised(
                    current.wifiAware
                ),
            )
            ValueRow(
                "Android PC feature",
                passOrNotAdvertised(
                    current.pcHardwareType
                ),
            )
        }

        ResearchCard(
            title = "Bridges locais / shell",
        ) {
            ValueRow(
                "APK externo",
                if (
                    current.unknownSourceInstallAllowed
                ) {
                    "AUTORIZADO"
                } else {
                    "AGUARDANDO AUTORIZAÇÃO"
                },
            )
            ValueRow(
                "Termux",
                if (current.termuxInstalled) {
                    "INSTALADO"
                } else {
                    "NÃO DETECTADO"
                },
            )
            ValueRow(
                "Termux RUN_COMMAND",
                if (
                    current
                        .termuxRunCommandPermissionGranted
                ) {
                    "GRANTED"
                } else {
                    "NOT GRANTED"
                },
            )
            ValueRow(
                "Shizuku",
                if (current.shizukuInstalled) {
                    "INSTALADO / PERMISSÃO NÃO TESTADA"
                } else {
                    "NÃO DETECTADO"
                },
            )
            Text(
                "Este probe apenas observa pré-requisitos. " +
                    "Ele não executa comandos no Termux, não solicita " +
                    "ADB shell e não usa Shizuku automaticamente.",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Bluetooth HID / controle remoto",
        ) {
            ValueRow(
                "Adaptador Bluetooth",
                if (current.bluetoothAdapterAvailable) {
                    "DISPONÍVEL"
                } else {
                    "NÃO DETECTADO"
                },
            )
            ValueRow(
                "BLUETOOTH_CONNECT",
                if (
                    current
                        .bluetoothConnectPermissionGranted
                ) {
                    "GRANTED"
                } else {
                    "NOT GRANTED"
                },
            )
            ValueRow(
                "HID Device API",
                if (
                    current
                        .bluetoothHidDeviceApiCandidate
                ) {
                    "API CANDIDATE"
                } else {
                    "NOT AVAILABLE"
                },
            )
            Text(
                "Candidato para transformar o telefone em teclado/mouse " +
                    "Bluetooth de outro dispositivo. O perfil HID ainda " +
                    "não foi registrado nem testado fisicamente.",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Hipótese: monitor remoto PocketPC",
        ) {
            val promising =
                current
                    .remoteDesktopFoundationPromising

            ValueRow(
                "Fundação técnica",
                if (promising) {
                    "PROMISSORA"
                } else {
                    "AINDA INCOMPLETA"
                },
            )

            Text(
                if (promising) {
                    "O aparelho confirmou VirtualDisplay de conteúdo " +
                        "próprio, encoder de vídeo por hardware e pelo menos " +
                        "uma tecnologia P2P. Isso justifica implementar um " +
                        "protótipo de segunda tela PocketPC transmitida, " +
                        "mas ainda NÃO é um desktop remoto validado."
                } else {
                    "Uma ou mais bases ainda não foram confirmadas. " +
                        "Nenhum suporte remoto é inferido."
                },
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }

        ResearchCard(
            title = "Limites preservados",
        ) {
            Text(
                "Este teste não injeta eventos em outros apps, não usa " +
                    "interfaces privadas, não cria uma VM privilegiada e " +
                    "não afirma que apps Windows funcionam.",
                style =
                    MaterialTheme.typography
                        .bodySmall,
            )
        }
    }
}

@Composable
private fun ResearchCard(
    title: String,
    body: @Composable ColumnScope.() -> Unit,
) {
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
                title,
                style =
                    MaterialTheme.typography
                        .titleSmall,
            )
            HorizontalDivider()
            body()
        }
    }
}

private fun passOrNotAdvertised(
    value: Boolean,
): String =
    if (value) {
        "ADVERTISED"
    } else {
        "NOT ADVERTISED"
    }
