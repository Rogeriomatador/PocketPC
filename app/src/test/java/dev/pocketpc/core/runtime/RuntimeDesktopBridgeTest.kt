package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDesktopBridgeTest {
    private fun window(
        id: Long,
        z: Int,
    ) =
        RuntimeDisplayCompositorWindow(
            windowId = id,
            parentId = 0L,
            flags = 0,
            geometry = null,
            surfaceId = null,
            surfaceGeneration = null,
            frameId = 0L,
            frame = null,
            zIndex = z,
        )

    @Test
    fun activeBindingPublishesAndForwardsCommands() {
        val bridge =
            RuntimeDesktopBridge()
        val commands =
            mutableListOf<
                RuntimeBridgeWindowCommand
            >()
        val binding =
            bridge.bind { command ->
                commands += command
                Result.success(Unit)
            }

        assertTrue(
            binding.publish(
                listOf(
                    window(2, 2),
                    window(1, 1),
                ),
            ),
        )
        assertEquals(
            listOf(1L, 2L),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )

        assertTrue(
            bridge.command(
                windowId = 2L,
                command =
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_COMMAND_CLOSE,
            ).isSuccess,
        )
        assertEquals(
            RuntimeBridgeWindowCommand(
                windowId = 2L,
                command =
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_COMMAND_CLOSE,
            ),
            commands.single(),
        )

        binding.close()
        assertTrue(
            bridge.windows.value
                .isEmpty(),
        )
    }

    @Test
    fun staleBindingCannotOverwriteNewSession() {
        val bridge =
            RuntimeDesktopBridge()
        val first =
            bridge.bind {
                Result.success(Unit)
            }
        assertTrue(
            first.publish(
                listOf(
                    window(1, 1),
                ),
            ),
        )

        val second =
            bridge.bind {
                Result.success(Unit)
            }
        assertTrue(
            second.publish(
                listOf(
                    window(2, 1),
                ),
            ),
        )
        assertFalse(
            first.publish(
                listOf(
                    window(3, 1),
                ),
            ),
        )
        assertEquals(
            listOf(2L),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )

        first.close()
        assertEquals(
            listOf(2L),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )

        second.close()
        assertTrue(
            bridge.windows.value
                .isEmpty(),
        )
    }

    @Test
    fun commandRejectsUnknownWindow() {
        val bridge =
            RuntimeDesktopBridge()
        val binding =
            bridge.bind {
                Result.success(Unit)
            }

        assertTrue(
            binding.publish(
                listOf(
                    window(1, 1),
                ),
            ),
        )

        assertTrue(
            bridge.command(
                windowId = 9L,
                command =
                    RuntimeDisplayBridgePayloadCodec
                        .WINDOW_COMMAND_CLOSE,
            ).isFailure,
        )

        binding.close()
    }

    @Test
    fun duplicateWindowSnapshotFailsClosed() {
        val bridge =
            RuntimeDesktopBridge()
        val binding =
            bridge.bind {
                Result.success(Unit)
            }

        val result =
            runCatching {
                binding.publish(
                    listOf(
                        window(1, 1),
                        window(1, 2),
                    ),
                )
            }

        assertTrue(result.isFailure)
        binding.close()
    }
}
