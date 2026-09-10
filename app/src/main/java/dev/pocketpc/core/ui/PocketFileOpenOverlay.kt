package dev.pocketpc.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileHandlerReadiness
import dev.pocketpc.core.storage.PocketFileOpenCapability
import dev.pocketpc.core.storage.PocketFileOpenCoordinator

/**
 * Desktop-owned replacement for Android's generic "Abrir com" chooser.
 *
 * It is intentionally informational while an internal handler is ROUTE_ONLY.
 * That prevents the UI from pretending an archive/PDF/office handler works
 * before implementation and tests exist.
 */
@Composable
fun PocketFileOpenOverlay() {
    val plan by
        PocketFileOpenCoordinator
            .plan
            .collectAsState()
    val current = plan ?: return

    AlertDialog(
        onDismissRequest =
            PocketFileOpenCoordinator::dismiss,
        title = {
            Text(current.title)
        },
        text = {
            Column(
                verticalArrangement =
                    Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    current.fileName,
                    style =
                        MaterialTheme.typography
                            .titleSmall,
                )
                Text(current.description)

                Row(
                    modifier =
                        Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                current.association
                                    .displayName
                            )
                        },
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                when (
                                    current.capability
                                ) {
                                    PocketFileOpenCapability
                                        .WINDOWS_RUNTIME_REQUIRED ->
                                        "Runtime necessário"

                                    PocketFileOpenCapability
                                        .ANDROID_SYSTEM_ACTION_REQUIRED ->
                                        "Android necessário"

                                    PocketFileOpenCapability
                                        .INTERNAL_HANDLER_PENDING ->
                                        "Em preparação"

                                    PocketFileOpenCapability
                                        .UNSUPPORTED ->
                                        "Sem associação"
                                }
                            )
                        },
                    )
                }

                if (
                    current.association.readiness ==
                    PocketFileHandlerReadiness.ROUTE_ONLY
                ) {
                    Text(
                        "O arquivo continua dentro do PocketPC. " +
                            "Esta associação não é evidência de que " +
                            "o aplicativo já esteja funcional.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }

                if (current.leavesPocketPc) {
                    Text(
                        "Esta ação cruza uma fronteira do Android " +
                            "e só deve ocorrer após uma ação explícita.",
                        style =
                            MaterialTheme.typography
                                .bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick =
                    PocketFileOpenCoordinator::dismiss,
            ) {
                Text("Entendi")
            }
        },
        modifier = Modifier.padding(8.dp),
    )
}
