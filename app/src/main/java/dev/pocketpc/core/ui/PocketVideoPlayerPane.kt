package dev.pocketpc.core.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocketpc.core.storage.PocketFileOpenRequest
import kotlinx.coroutines.delay

@Composable
fun PocketVideoPlayerPane(
    request: PocketFileOpenRequest,
) {
    val uriString = request.uri
    var view by remember(uriString) {
        mutableStateOf<VideoView?>(null)
    }
    var prepared by remember(uriString) { mutableStateOf(false) }
    var playing by remember(uriString) { mutableStateOf(false) }
    var durationMs by remember(uriString) { mutableIntStateOf(0) }
    var positionMs by remember(uriString) { mutableIntStateOf(0) }
    var errorMessage by remember(uriString) { mutableStateOf<String?>(null) }

    LaunchedEffect(view, playing, prepared) {
        while (playing && prepared) {
            val active = view ?: break
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
        if (uriString == null) {
            Text(
                "O PocketPC não recebeu acesso ao vídeo.",
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        AndroidView(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 520.dp),
            factory = { context ->
                VideoView(context).apply {
                    view = this
                    prepared = false
                    playing = false
                    errorMessage = null

                    setOnPreparedListener { player ->
                        durationMs = player.duration.coerceAtLeast(0)
                        positionMs = currentPosition.coerceAtLeast(0)
                        prepared = true
                    }
                    setOnCompletionListener {
                        playing = false
                        positionMs = duration.coerceAtLeast(0)
                    }
                    setOnErrorListener { _, what, extra ->
                        prepared = false
                        playing = false
                        errorMessage =
                            "O player interno não conseguiu reproduzir este vídeo (código $what/$extra)."
                        true
                    }
                    setVideoURI(Uri.parse(uriString))
                }
            },
            update = { active ->
                view = active
            },
            onRelease = { active ->
                runCatching { active.stopPlayback() }
                if (view === active) {
                    view = null
                }
            },
        )

        errorMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!prepared && errorMessage == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text("Preparando vídeo dentro do PocketPC...")
            }
        }

        if (prepared) {
            Text(
                "${formatVideoTime(positionMs)} / ${formatVideoTime(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        view?.let { active ->
                            val target = (positionMs - 10_000).coerceAtLeast(0)
                            active.seekTo(target)
                            positionMs = target
                        }
                    },
                ) {
                    Text("-10 s")
                }

                Button(
                    onClick = {
                        view?.let { active ->
                            if (playing) {
                                active.pause()
                                positionMs = active.currentPosition.coerceAtLeast(0)
                                playing = false
                            } else {
                                if (durationMs > 0 && positionMs >= durationMs) {
                                    active.seekTo(0)
                                    positionMs = 0
                                }
                                runCatching { active.start() }
                                    .onSuccess { playing = true }
                                    .onFailure { error ->
                                        errorMessage =
                                            "Falha ao iniciar vídeo: " +
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
                        view?.let { active ->
                            val target =
                                (positionMs + 10_000)
                                    .coerceAtMost(durationMs.coerceAtLeast(0))
                            active.seekTo(target)
                            positionMs = target
                        }
                    },
                ) {
                    Text("+10 s")
                }
            }

            Text(
                "O vídeo permanece incorporado à janela do PocketPC. O suporte real a cada codec depende do aparelho e ainda precisa de teste físico.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatVideoTime(milliseconds: Int): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
