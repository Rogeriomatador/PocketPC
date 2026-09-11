package dev.pocketpc.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.pocketpc.core.runtime.RuntimeDisplayExternalFrameIdentity
import dev.pocketpc.core.runtime.RuntimeDisplayFramePixels

@Composable
internal fun RuntimeDisplayFramePreview(
    frame: RuntimeDisplayFramePixels,
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
        modifier = modifier,
        contentScale =
            contentScale,
    )
}
