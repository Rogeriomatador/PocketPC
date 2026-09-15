package dev.pocketpc.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.pocketpc.core.runtime.RuntimeDisplayExternalFrameIdentity
import dev.pocketpc.core.runtime.RuntimeDisplayFramePixels
import dev.pocketpc.core.runtime.RuntimeGraphicsEvidenceLog

@Composable
internal fun RuntimeDisplayFramePreview(
    frame: RuntimeDisplayFramePixels,
    windowId: Long = 0L,
    frameId: Long = 0L,
    externalFrameIdentity:
        RuntimeDisplayExternalFrameIdentity? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale =
        ContentScale.Fit,
) {
    val image =
        remember(
            frame.width,
            frame.height,
            frame.argb,
            frameId,
            externalFrameIdentity,
        ) {
            Bitmap.createBitmap(
                frame.argb,
                frame.width,
                frame.height,
                Bitmap.Config.ARGB_8888,
            ).asImageBitmap()
        }

    Image(
        bitmap = image,
        contentDescription =
            "Frame capturado do bridge Win32",
        modifier =
            modifier.drawWithContent {
                drawContent()
                externalFrameIdentity?.takeIf { windowId > 0L }?.let { identity ->
                    RuntimeGraphicsEvidenceLog.composeFrameDrawSubmitted(
                        windowId = windowId,
                        identity = identity,
                        width = frame.width,
                        height = frame.height,
                    )
                }
            },
        contentScale =
            contentScale,
    )
}
