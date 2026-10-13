package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeVulkanFrameRoutingTest {
    private fun createWindow(
        model: RuntimeDisplayCompositorModel,
        windowId: Long,
    ) {
        model.apply(
            RuntimeDisplayHostStep(
                event =
                    RuntimeDisplayBridgeEvent.WindowCreated(
                        RuntimeBridgeWindowState(
                            windowId = windowId,
                            parentId = 0L,
                            flags = 0,
                            geometry = null,
                        ),
                    ),
            ),
        ).getOrThrow()
    }

    private fun emptyWindow(windowId: Long) =
        RuntimeDisplayCompositorWindow(
            windowId = windowId,
            parentId = 0L,
            flags = 0,
            geometry = null,
            surfaceId = null,
            surfaceGeneration = null,
            frameId = 0L,
            frame = null,
        )

    private fun frame(value: Int) =
        RuntimeDisplayFramePixels(
            width = 2,
            height = 1,
            argb = intArrayOf(value, value xor 0x00ffffff),
        )

    @Test
    fun compositorKeepsVulkanIdentitySeparateAndRejectsStaleSequence() {
        val model = RuntimeDisplayCompositorModel()
        createWindow(model, 77L)

        val firstPixels = frame(0xff112233.toInt())
        val firstIdentity =
            RuntimeDisplayExternalFrameIdentity(
                resourceId = 9L,
                generation = 3L,
                sequence = 1L,
            )

        assertTrue(
            model.applyExternalVulkanFrame(
                windowId = 77L,
                identity = firstIdentity,
                frame = firstPixels,
            ).isSuccess,
        )

        val first = model.snapshot().single()
        assertSame(firstPixels, first.frame)
        assertEquals(firstIdentity, first.externalFrameIdentity)
        assertNull(first.surfaceId)
        assertNull(first.surfaceGeneration)
        assertEquals(0L, first.frameId)

        assertTrue(
            model.applyExternalVulkanFrame(
                windowId = 77L,
                identity = firstIdentity,
                frame = frame(0xff445566.toInt()),
            ).isFailure,
        )

        val secondPixels = frame(0xff778899.toInt())
        val secondIdentity = firstIdentity.copy(sequence = 2L)
        assertTrue(
            model.applyExternalVulkanFrame(
                windowId = 77L,
                identity = secondIdentity,
                frame = secondPixels,
            ).isSuccess,
        )
        assertSame(secondPixels, model.snapshot().single().frame)
        assertEquals(secondIdentity, model.snapshot().single().externalFrameIdentity)
    }

    @Test
    fun normalBridgeFrameReplacesExternalIdentityWithoutCrossNamespaceComparison() {
        val model = RuntimeDisplayCompositorModel()
        createWindow(model, 7L)

        model.applyExternalVulkanFrame(
            windowId = 7L,
            identity =
                RuntimeDisplayExternalFrameIdentity(
                    resourceId = 100L,
                    generation = 4L,
                    sequence = 99L,
                ),
            frame = frame(0xff010203.toInt()),
        ).getOrThrow()

        val ready =
            RuntimeBridgeFrameReady(
                windowId = 7L,
                surfaceId = 5L,
                generation = 6L,
                frameId = 1L,
            )
        val sharedPixels = frame(0xffaabbcc.toInt())
        model.apply(
            RuntimeDisplayHostStep(
                event = RuntimeDisplayBridgeEvent.FrameReady(ready),
                presentedFrame =
                    RuntimeDisplayPresentedFrame(
                        ready = ready,
                        pixels = sharedPixels,
                    ),
            ),
        ).getOrThrow()

        val snapshot = model.snapshot().single()
        assertNull(snapshot.externalFrameIdentity)
        assertEquals(5L, snapshot.surfaceId)
        assertEquals(6L, snapshot.surfaceGeneration)
        assertEquals(1L, snapshot.frameId)
        assertSame(sharedPixels, snapshot.frame)
    }

    @Test
    fun desktopBridgeRoutesExternalFrameOnlyToExactWindowOwner() {
        val bridge = RuntimeDesktopBridge()
        val firstFrames = mutableListOf<RuntimeDesktopExternalVulkanFrame>()
        val secondFrames = mutableListOf<RuntimeDesktopExternalVulkanFrame>()

        val first =
            bridge.bind(
                commandSender = { Result.success(Unit) },
                pointerSender = null,
                keySender = null,
                externalVulkanFrameSender = {
                    value ->
                    firstFrames += value
                    Result.success(Unit)
                },
            )
        val second =
            bridge.bind(
                commandSender = { Result.success(Unit) },
                pointerSender = null,
                keySender = null,
                externalVulkanFrameSender = {
                    value ->
                    secondFrames += value
                    Result.success(Unit)
                },
            )

        first.publish(listOf(emptyWindow(101L)))
        second.publish(listOf(emptyWindow(202L)))

        val value =
            RuntimeDesktopExternalVulkanFrame(
                windowId = 202L,
                identity =
                    RuntimeDisplayExternalFrameIdentity(
                        resourceId = 21L,
                        generation = 8L,
                        sequence = 1L,
                    ),
                frame = frame(0xff123456.toInt()),
            )
        assertTrue(bridge.presentExternalVulkanFrame(value).isSuccess)
        assertTrue(firstFrames.isEmpty())
        assertEquals(listOf(value), secondFrames)

        assertTrue(
            bridge.presentExternalVulkanFrame(
                value.copy(windowId = 303L),
            ).isFailure,
        )
        assertTrue(firstFrames.isEmpty())
        assertEquals(1, secondFrames.size)
    }
}
