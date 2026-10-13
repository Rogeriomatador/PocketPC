package dev.pocketpc.core.ui

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPopupPositionProviderTest {
    @Test
    fun taskbarPopupCentersAboveIcon() {
        val provider =
            TaskbarPopupPositionProvider(
                localAnchorX = null,
                gapPx = 6,
                edgePx = 8,
            )

        val result =
            provider.calculatePosition(
                anchorBounds =
                    IntRect(
                        left = 100,
                        top = 700,
                        right = 148,
                        bottom = 748,
                    ),
                windowSize =
                    IntSize(
                        width = 1000,
                        height = 800,
                    ),
                layoutDirection =
                    LayoutDirection.Ltr,
                popupContentSize =
                    IntSize(
                        width = 220,
                        height = 260,
                    ),
            )

        assertEquals(
            14,
            result.x,
        )
        assertEquals(
            434,
            result.y,
        )
    }

    @Test
    fun taskbarPopupClampsAtRightEdge() {
        val provider =
            TaskbarPopupPositionProvider(
                localAnchorX = null,
                gapPx = 6,
                edgePx = 8,
            )

        val result =
            provider.calculatePosition(
                anchorBounds =
                    IntRect(
                        left = 950,
                        top = 700,
                        right = 998,
                        bottom = 748,
                    ),
                windowSize =
                    IntSize(
                        width = 1000,
                        height = 800,
                    ),
                layoutDirection =
                    LayoutDirection.Ltr,
                popupContentSize =
                    IntSize(
                        width = 220,
                        height = 260,
                    ),
            )

        assertEquals(
            772,
            result.x,
        )
        assertTrue(
            result.y < 700,
        )
    }

    @Test
    fun desktopContextUsesPressedPointWhenThereIsSpace() {
        val provider =
            DesktopContextPopupPositionProvider(
                anchorX = 300,
                anchorY = 200,
                edgePx = 8,
                gapPx = 5,
                fallbackBottomPx = 60,
            )

        val result =
            provider.calculatePosition(
                anchorBounds =
                    IntRect(0, 0, 0, 0),
                windowSize =
                    IntSize(
                        width = 1000,
                        height = 800,
                    ),
                layoutDirection =
                    LayoutDirection.Ltr,
                popupContentSize =
                    IntSize(
                        width = 250,
                        height = 300,
                    ),
            )

        assertEquals(
            305,
            result.x,
        )
        assertEquals(
            205,
            result.y,
        )
    }

    @Test
    fun desktopContextFlipsInsideBottomRightCorner() {
        val provider =
            DesktopContextPopupPositionProvider(
                anchorX = 980,
                anchorY = 780,
                edgePx = 8,
                gapPx = 5,
                fallbackBottomPx = 60,
            )

        val result =
            provider.calculatePosition(
                anchorBounds =
                    IntRect(0, 0, 0, 0),
                windowSize =
                    IntSize(
                        width = 1000,
                        height = 800,
                    ),
                layoutDirection =
                    LayoutDirection.Ltr,
                popupContentSize =
                    IntSize(
                        width = 250,
                        height = 300,
                    ),
            )

        assertEquals(
            725,
            result.x,
        )
        assertEquals(
            475,
            result.y,
        )
    }

    @Test
    fun desktopContextWithoutPointerFallsBackAboveTaskbar() {
        val provider =
            DesktopContextPopupPositionProvider(
                anchorX = null,
                anchorY = null,
                edgePx = 8,
                gapPx = 5,
                fallbackBottomPx = 70,
            )

        val result =
            provider.calculatePosition(
                anchorBounds =
                    IntRect(0, 0, 0, 0),
                windowSize =
                    IntSize(
                        width = 1000,
                        height = 800,
                    ),
                layoutDirection =
                    LayoutDirection.Ltr,
                popupContentSize =
                    IntSize(
                        width = 250,
                        height = 300,
                    ),
            )

        assertEquals(
            8,
            result.x,
        )
        assertEquals(
            430,
            result.y,
        )
    }
}
