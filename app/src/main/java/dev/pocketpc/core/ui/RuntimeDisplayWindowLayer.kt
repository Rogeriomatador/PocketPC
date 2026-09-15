package dev.pocketpc.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import dev.pocketpc.core.runtime.RuntimeDisplayCompositorWindow
import kotlin.math.min
import kotlin.math.roundToInt

internal data class RuntimeDisplayWindowPlacement(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val zIndex: Int,
)

internal object RuntimeDisplayWindowPlacementResolver {
    fun resolve(
        window:
            RuntimeDisplayCompositorWindow,
    ): RuntimeDisplayWindowPlacement? {
        val geometry =
            window.geometry
                ?: return null
        val frame =
            window.frame
                ?: return null

        if (
            !geometry.visible ||
            geometry.width <= 0 ||
            geometry.height <= 0 ||
            frame.width <= 0 ||
            frame.height <= 0 ||
            frame.argb.size !=
                frame.width *
                    frame.height
        ) {
            return null
        }

        return RuntimeDisplayWindowPlacement(
            x = geometry.x,
            y = geometry.y,
            width = geometry.width,
            height = geometry.height,
            sourceWidth =
                min(
                    geometry.width,
                    frame.width,
                ),
            sourceHeight =
                min(
                    geometry.height,
                    frame.height,
                ),
            zIndex =
                window.zIndex,
        )
    }
}

@Composable
internal fun RuntimeDisplayWindowLayer(
    windows:
        List<
            RuntimeDisplayCompositorWindow
        >,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clipToBounds(),
    ) {
        windows
            .sortedBy {
                it.zIndex
            }
            .forEach {
                window ->
                val placement =
                    RuntimeDisplayWindowPlacementResolver
                        .resolve(
                            window,
                        )
                        ?: return@forEach
                val frame =
                    window.frame
                        ?: return@forEach

                key(
                    window.windowId,
                    window.frameId,
                ) {
                    val bitmap =
                        remember(
                            window.windowId,
                            window.frameId,
                        ) {
                            Bitmap.createBitmap(
                                frame.argb,
                                frame.width,
                                frame.height,
                                Bitmap.Config
                                    .ARGB_8888,
                            )
                        }
                    val image =
                        remember(bitmap) {
                            bitmap.asImageBitmap()
                        }

                    Canvas(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .zIndex(
                                    placement
                                        .zIndex
                                        .toFloat(),
                                ),
                    ) {
                        drawIntoCanvas {
                            canvas ->
                            val src =
                                android.graphics.Rect(
                                    0,
                                    0,
                                    placement
                                        .sourceWidth,
                                    placement
                                        .sourceHeight,
                                )
                            val dst =
                                android.graphics.RectF(
                                    placement.x
                                        .toFloat(),
                                    placement.y
                                        .toFloat(),
                                    (
                                        placement.x +
                                            placement.width
                                    ).toFloat(),
                                    (
                                        placement.y +
                                            placement.height
                                    ).toFloat(),
                                )
                            canvas.nativeCanvas
                                .drawBitmap(
                                    bitmap,
                                    src,
                                    dst,
                                    null,
                                )
                        }
                    }
                }
            }
    }
}
