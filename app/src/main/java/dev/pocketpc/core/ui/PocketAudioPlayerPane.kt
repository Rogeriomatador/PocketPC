package dev.pocketpc.core.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.delay

@Composable
fun PocketAudioPlayerPane(
    request: PocketFileOpenRequest,
) {
    val context = LocalContext.current
    val uriString = request.uri

    var player by remember(uriString) {
        mutableStateOf<MediaPlayer?>(null)
    }
    var prepared by remember(uriString) { mutableStateOf(false) }
    var playing by remember(uriString) { mutableStateOf(false) }
    var durationMs by remember(uriString) { mutableIntStateOf(0) }
    var positionMs by remember(uriString) { mutableIntStateOf(0) }
    var errorMessage by remember(uriString) { mutableStateOf<String?>(null) }

    DisposableEffect(uriString) {
        if (uriString == null) {
            errorMessage = "O PocketPC não recebeu acesso ao áudio."
            onDispose { }
        } else {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            prepared = false
            playing = false
            positionMs = 0
            durationMs = 0
            errorMessage = null

            runCatching {
                mediaPlayer.setDataSource(
                    context,
                    Uri.parse(uriString),
                )
                mediaPlayer.setOnPreparedListener { ready ->
                    durationMs = ready.duration.coerceAtLeast(0)
                    positionMs = ready.currentPosition.coerceAtLeast(0)
                    prepared = true
                }
                mediaPlayer.setOnCompletionListener { completed ->
                    playing = false
                    positionMs = completed.duration.coerceAtLeast(0)
                }
                mediaPlayer.setOnErrorListener { _, what, extra ->
                    prepared = false
                    playing = false
                    errorMessage =
                        "O player interno não conseguiu reproduzir este áudio (código $what/$extra)."
                    true
                }
                mediaPlayer.prepareAsync()
            }.onFailure { error ->
                errorMessage =
                    "Não foi possível preparar o áudio dentro do PocketPC: " +
                        (error.message ?: error.javaClass.simpleName)
            }

            onDispose {
                runCatching { mediaPlayer.stop() }
                runCatching { mediaPlayer.reset() }
                runCatching { mediaPlayer.release() }
                if (player === mediaPlayer) {
                    player = null
                }
            }
        }
    }

    LaunchedEffect(player, playing, prepared) {
        while (playing && prepared) {
            val active = player ?: break
            positionMs =
                runCatching { active.currentPosition }
                    .getOrDefault(positionMs)
                    .coerceAtLeast(0)
            delay(250)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        errorMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!prepared && errorMessage == null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator()
                Text("Preparando áudio dentro do PocketPC...")
            }
        }

        if (prepared) {
            Text(
                "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        player?.let { active ->
                            val target = (positionMs - 10_000).coerceAtLeast(0)
                            runCatching { active.seekTo(target) }
                            positionMs = target
                        }
                    },
                ) {
                    Text("-10 s")
                }

                Button(
                    onClick = {
                        player?.let { active ->
                            if (playing) {
                                runCatching { active.pause() }
                                positionMs =
                                    runCatching { active.currentPosition }
                                        .getOrDefault(positionMs)
                                playing = false
                            } else {
                                if (durationMs > 0 && positionMs >= durationMs) {
                                    runCatching { active.seekTo(0) }
                                    positionMs = 0
                                }
                                runCatching { active.start() }
                                    .onSuccess { playing = true }
                                    .onFailure { error ->
                                        errorMessage =
                                            "Falha ao iniciar reprodução: " +
                                                (error.message ?: error.javaClass.simpleName)
                                    }
                            }
                        }
                    },
                ) {
                    Text(if (playing) "Pausar" else "Reproduzir")
                }

                Button(
                    onClick = {
                        player?.let { active ->
                            val target =
                                (positionMs + 10_000)
                                    .coerceAtMost(durationMs.coerceAtLeast(0))
                            runCatching { active.seekTo(target) }
                            positionMs = target
                        }
                    },
                ) {
                    Text("+10 s")
                }
            }

            Text(
                "A reprodução acontece dentro da janela do PocketPC; nenhum player Android externo é aberto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatAudioTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
