package dev.pocketpc.core.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun StoreApp() {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun openSearch(term: String) {
        val normalized = term.trim()
        val marketTarget =
            if (normalized.isBlank()) {
                "market://search?q=apps"
            } else {
                "market://search?q=" +
                    Uri.encode(normalized)
            }
        val webTarget =
            if (normalized.isBlank()) {
                "https://play.google.com/store/apps"
            } else {
                val encoded =
                    URLEncoder.encode(
                        normalized,
                        StandardCharsets.UTF_8.name(),
                    )
                "https://play.google.com/store/search?q=$encoded&c=apps"
            }

        openStoreTarget(
            context = context,
            marketUri = marketTarget,
            webUri = webTarget,
        )
            .onSuccess {
                status =
                    if (normalized.isBlank()) {
                        "Google Play aberta."
                    } else {
                        "Pesquisa enviada: $normalized"
                    }
            }
            .onFailure { error ->
                status =
                    "Falha ao abrir a loja: " +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                            )
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
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
                    "Loja",
                    style =
                        MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Apps e jogos usando a loja oficial do Android",
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                )
            }

            AssistChip(
                onClick = {
                    openSearch("")
                },
                label = {
                    Text(
                        "Google Play",
                        fontSize = 9.sp,
                    )
                },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("Pesquisar apps ou jogos")
            },
            trailingIcon = {
                TextButton(
                    onClick = {
                        openSearch(query)
                    },
                    enabled = query.trim().isNotEmpty(),
                ) {
                    Text("Buscar")
                }
            },
        )

        Text(
            "Atalhos",
            style =
                MaterialTheme.typography.titleSmall,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            StoreShortcut(
                title = "Jogos",
                subtitle = "Play Games",
                modifier = Modifier.weight(1f),
            ) {
                openStoreTarget(
                    context,
                    "market://search?q=games",
                    "https://play.google.com/store/games",
                )
            }
            StoreShortcut(
                title = "Produtividade",
                subtitle = "Office, notas e trabalho",
                modifier = Modifier.weight(1f),
            ) {
                openSearch("productivity")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(8.dp),
        ) {
            StoreShortcut(
                title = "Navegadores",
                subtitle = "Web e internet",
                modifier = Modifier.weight(1f),
            ) {
                openSearch("browser")
            }
            StoreShortcut(
                title = "Ferramentas",
                subtitle = "Utilitários",
                modifier = Modifier.weight(1f),
            ) {
                openSearch("tools")
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
                    Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Como funciona",
                    style =
                        MaterialTheme.typography.titleSmall,
                )
                Text(
                    "O PocketPC não mantém uma loja paralela. " +
                        "Instalação, atualização, pagamento e permissões " +
                        "continuam sob controle da loja oficial e do Android.",
                    style =
                        MaterialTheme.typography.bodySmall,
                )
            }
        }

        status?.let {
            Text(
                it,
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { openSearch("") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Abrir Google Play")
        }
    }
}

@Composable
private fun StoreShortcut(
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(66.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding =
            PaddingValues(horizontal = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                title,
                fontSize = 11.sp,
            )
            Text(
                subtitle,
                fontSize = 8.sp,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
            )
        }
    }
}

private fun openStoreTarget(
    context: Context,
    marketUri: String,
    webUri: String,
): Result<Unit> =
    runCatching {
        val market =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(marketUri),
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
        val web =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(webUri),
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        if (
            market.resolveActivity(
                context.packageManager
            ) != null
        ) {
            context.startActivity(market)
        } else {
            context.startActivity(web)
        }
    }
