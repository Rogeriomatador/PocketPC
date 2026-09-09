package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDisplayCompositorModelTest {
    @Test
    fun windowGeometryFrameAndDestroyLifecycle() {
        val model =
            RuntimeDisplayCompositorModel()

        val created =
            RuntimeBridgeWindowState(
                windowId = 7L,
                parentId = 0L,
                flags = 0,
                geometry = null,
            )
        assertTrue(
            model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .WindowCreated(
                                created,
                            ),
                ),
            ).isSuccess,
        )

        val geometry =
            RuntimeBridgeWindowGeometry(
                windowId = 7L,
                x = 10,
                y = 20,
                width = 320,
                height = 200,
                visible = true,
                zOrderFlags =
                    RuntimeDisplayBridgePayloadCodec
                        .Z_ORDER_NO_CHANGE,
                insertAfterWindowId = 0L,
            )
        assertTrue(
            model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .WindowGeometryChanged(
                                geometry,
                            ),
                ),
            ).isSuccess,
        )

        val ready =
            RuntimeBridgeFrameReady(
                windowId = 7L,
                surfaceId = 3L,
                generation = 2L,
                frameId = 1L,
            )
        val pixels =
            RuntimeDisplayFramePixels(
                width = 2,
                height = 1,
                argb =
                    intArrayOf(
                        0xff010203.toInt(),
                        0xff112233.toInt(),
                    ),
            )
        assertTrue(
            model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .FrameReady(
                                ready,
                            ),
                    presentedFrame =
                        RuntimeDisplayPresentedFrame(
                            ready = ready,
                            pixels = pixels,
                        ),
                ),
            ).isSuccess,
        )

        val snapshot =
            model.snapshot()
                .single()
        assertEquals(
            geometry,
            snapshot.geometry,
        )
        assertEquals(
            3L,
            snapshot.surfaceId,
        )
        assertEquals(
            2L,
            snapshot.surfaceGeneration,
        )
        assertEquals(
            1L,
            snapshot.frameId,
        )
        assertEquals(
            pixels,
            snapshot.frame,
        )

        assertTrue(
            model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .WindowDestroyed(
                                7L,
                            ),
                ),
            ).isSuccess,
        )
        assertTrue(
            model.snapshot()
                .isEmpty(),
        )
    }

    @Test
    fun frameWithoutPixelsFailsClosed() {
        val model =
            RuntimeDisplayCompositorModel()
        model.apply(
            RuntimeDisplayHostStep(
                event =
                    RuntimeDisplayBridgeEvent
                        .WindowCreated(
                            RuntimeBridgeWindowState(
                                windowId = 1L,
                                parentId = 0L,
                                flags = 0,
                                geometry = null,
                            ),
                        ),
            ),
        ).getOrThrow()

        val ready =
            RuntimeBridgeFrameReady(
                windowId = 1L,
                surfaceId = 1L,
                generation = 1L,
                frameId = 1L,
            )

        assertTrue(
            model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .FrameReady(
                                ready,
                            ),
                    presentedFrame = null,
                ),
            ).isFailure,
        )

        assertNull(
            model.snapshot()
                .single()
                .frame,
        )
    }

    @Test
    fun staleFrameFailsClosed() {
        val model =
            RuntimeDisplayCompositorModel()
        model.apply(
            RuntimeDisplayHostStep(
                event =
                    RuntimeDisplayBridgeEvent
                        .WindowCreated(
                            RuntimeBridgeWindowState(
                                windowId = 1L,
                                parentId = 0L,
                                flags = 0,
                                geometry = null,
                            ),
                        ),
            ),
        ).getOrThrow()

        fun applyFrame(
            id: Long,
        ): Result<Unit> {
            val ready =
                RuntimeBridgeFrameReady(
                    windowId = 1L,
                    surfaceId = 1L,
                    generation = 1L,
                    frameId = id,
                )
            return model.apply(
                RuntimeDisplayHostStep(
                    event =
                        RuntimeDisplayBridgeEvent
                            .FrameReady(
                                ready,
                            ),
                    presentedFrame =
                        RuntimeDisplayPresentedFrame(
                            ready = ready,
                            pixels =
                                RuntimeDisplayFramePixels(
                                    width = 1,
                                    height = 1,
                                    argb =
                                        intArrayOf(
                                            0xff000000.toInt(),
                                        ),
                                ),
                        ),
                ),
            )
        }

        assertTrue(
            applyFrame(1L)
                .isSuccess,
        )
        assertTrue(
            applyFrame(1L)
                .isFailure,
        )
        assertEquals(
            1L,
            model.snapshot()
                .single()
                .frameId,
        )
    }
}
