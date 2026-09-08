package dev.pocketpc.core.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class PocketPcFirstRunStore(
    context: Context,
) {
    private val prefs =
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE,
        )

    fun shouldShow(): Boolean =
        !prefs.getBoolean(KEY_COMPLETED, false)

    fun complete() {
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    private companion object {
        const val PREFS = "pocketpc-first-run"
        const val KEY_COMPLETED = "completed"
    }
}

@Composable
internal fun PocketPcFirstRunExperience(
    onComplete: () -> Unit,
) {
    var page by rememberSaveable {
        mutableIntStateOf(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme
                    .scrim.copy(alpha = 0.66f)
            ),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxWidth < 600.dp

            Surface(
                modifier = Modifier
                    .fillMaxWidth(
                        if (compact) 1f else 0.72f
                    )
                    .widthIn(max = 760.dp),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 12.dp,
                shadowElevation = 18.dp,
            ) {
                Column(
                    modifier = Modifier.padding(
                        if (compact) 22.dp else 34.dp
                    ),
                    verticalArrangement =
                        Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment =
                            Alignment.CenterVertically,
                        horizontalArrangement =
                            Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "PocketPC",
                            style =
                                MaterialTheme.typography
                                    .headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "ALPHA 21",
                            color =
                                MaterialTheme.colorScheme
                                    .primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    LinearProgressIndicator(
                        progress = {
                            if (page == 0) {
                                0.5f
                            } else {
                                1f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )

                    if (page == 0) {
                        Text(
                            "Seu espaço de trabalho no celular",
                            style =
                                MaterialTheme.typography
                                    .headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "O PocketPC adapta o desktop ao toque, " +
                                "retrato, paisagem e telas maiores. " +
                                "Abra janelas, navegue, organize arquivos " +
                                "e conecte teclado, mouse ou monitor " +
                                "quando o Android oferecer suporte.",
                            style =
                                MaterialTheme.typography.bodyLarge,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                        FirstRunFeature(
                            title = "Desktop adaptativo",
                            body =
                                "No celular, as janelas usam a área útil; " +
                                    "em telas maiores, o modo livre volta " +
                                    "a oferecer mover, redimensionar e snap.",
                        )
                        FirstRunFeature(
                            title = "Navegador e downloads",
                            body =
                                "Downloads concluídos podem ser " +
                                    "reconciliados com P:\\Downloads " +
                                    "quando um PocketDrive estiver conectado.",
                        )
                    } else {
                        Text(
                            "C: para o sistema. P: para você.",
                            style =
                                MaterialTheme.typography
                                    .headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "C: é o espaço interno do PocketPC para dados " +
                                "quentes e componentes do sistema. " +
                                "P: é o PocketDrive escolhido por você para " +
                                "Downloads, Documentos, Apps, Jogos e Projetos.",
                            style =
                                MaterialTheme.typography.bodyLarge,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                        FirstRunFeature(
                            title = "Escolha seu PocketDrive",
                            body =
                                "Abra Arquivos e selecione uma pasta. " +
                                    "O PocketPC prepara a estrutura sem " +
                                    "apagar o restante do armazenamento.",
                        )
                        FirstRunFeature(
                            title = "Compatibilidade sem promessas falsas",
                            body =
                                "APK pode usar o instalador Android. " +
                                    "Pacotes de PC ficam armazenados até que " +
                                    "um runtime Windows real seja validado.",
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(10.dp),
                    ) {
                        if (page > 0) {
                            OutlinedButton(
                                onClick = { page-- },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Voltar")
                            }
                        }

                        Button(
                            onClick = {
                                if (page == 0) {
                                    page = 1
                                } else {
                                    onComplete()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (page == 0) {
                                    "Continuar"
                                } else {
                                    "Entrar no PocketPC"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstRunFeature(
    title: String,
    body: String,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color =
            MaterialTheme.colorScheme
                .surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement =
                Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}
