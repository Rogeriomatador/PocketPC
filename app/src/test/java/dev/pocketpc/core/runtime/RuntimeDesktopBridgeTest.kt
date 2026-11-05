package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDesktopBridgeTest {
    private fun window(
        id: Long,
        z: Int,
        topmost: Boolean = false,
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
            topmost = topmost,
            zIndex = z,
        )

    @Test
    fun concurrentBindingsPublishAndRouteCommandsByWindowOwner() {
        val bridge =
            RuntimeDesktopBridge()
        val firstCommands =
            mutableListOf<
                RuntimeBridgeWindowCommand
            >()
        val secondCommands =
            mutableListOf<
                RuntimeBridgeWindowCommand
            >()

        val first =
            bridge.bind {
                command ->
                firstCommands += command
                Result.success(Unit)
            }
        val second =
            bridge.bind {
                command ->
                secondCommands += command
                Result.success(Unit)
            }

        assertTrue(
            first.publish(
                listOf(
                    window(
                        0x100000001L,
                        0,
                    ),
                ),
            ),
        )
        assertTrue(
            second.publish(
                listOf(
                    window(
                        0x200000001L,
                        0,
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                0x100000001L,
                0x200000001L,
            ),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )

        assertTrue(
            bridge.closeWindow(
                0x200000001L,
            ).isSuccess,
        )
        assertTrue(firstCommands.isEmpty())
        assertEquals(
            listOf(
                RuntimeBridgeWindowCommand(
                    windowId =
                        0x200000001L,
                    command =
                        RuntimeDisplayBridgePayloadCodec
                            .WINDOW_COMMAND_CLOSE,
                ),
            ),
            secondCommands,
        )
    }

    @Test
    fun releasingOneBindingLeavesOtherPeerWindowsAlive() {
        val bridge =
            RuntimeDesktopBridge()
        val first =
            bridge.bind {
                Result.success(Unit)
            }
        val second =
            bridge.bind {
                Result.success(Unit)
            }

        first.publish(
            listOf(
                window(
                    0x100000001L,
                    0,
                ),
            ),
        )
        second.publish(
            listOf(
                window(
                    0x200000001L,
                    0,
                ),
            ),
        )

        first.close()

        assertEquals(
            listOf(
                0x200000001L,
            ),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )

        assertTrue(
            bridge.activate(
                0x200000001L,
            ).isSuccess,
        )

        second.close()
        assertTrue(
            bridge.windows.value
                .isEmpty(),
        )
    }

    @Test
    fun crossBindingWindowCollisionFailsClosedWithoutStealingOwner() {
        val bridge =
            RuntimeDesktopBridge()
        val firstCommands =
            mutableListOf<
                RuntimeBridgeWindowCommand
            >()
        val first =
            bridge.bind {
                command ->
                firstCommands += command
                Result.success(Unit)
            }
        val second =
            bridge.bind {
                Result.success(Unit)
            }

        first.publish(
            listOf(
                window(77L, 0),
            ),
        )

        val collision =
            runCatching {
                second.publish(
                    listOf(
                        window(
                            77L,
                            0,
                        ),
                    ),
                )
            }

        assertTrue(collision.isFailure)
        assertEquals(
            listOf(77L),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )
        assertTrue(
            bridge.closeWindow(
                77L,
            ).isSuccess,
        )
        assertEquals(1, firstCommands.size)
    }

    @Test
    fun pointerAndKeyboardRouteToOwningPeer() {
        val bridge =
            RuntimeDesktopBridge()
        val firstPointers =
            mutableListOf<
                RuntimeBridgePointerEvent
            >()
        val secondKeys =
            mutableListOf<
                RuntimeBridgeKeyEvent
            >()

        val first =
            bridge.bind(
                commandSender = {
                    Result.success(Unit)
                },
                pointerSender = {
                    event ->
                    firstPointers += event
                    Result.success(Unit)
                },
                keySender = null,
            )
        val second =
            bridge.bind(
                commandSender = {
                    Result.success(Unit)
                },
                pointerSender = null,
                keySender = {
                    event ->
                    secondKeys += event
                    Result.success(Unit)
                },
            )

        first.publish(
            listOf(
                window(
                    0x100000001L,
                    0,
                ),
            ),
        )
        second.publish(
            listOf(
                window(
                    0x200000001L,
                    0,
                ),
            ),
        )

        val pointer =
            RuntimeBridgePointerEvent(
                windowId =
                    0x100000001L,
                action =
                    RuntimeDisplayBridgePayloadCodec
                        .POINTER_ACTION_MOVE,
                x = 10,
                y = 20,
                buttons = 0,
                verticalScroll = 0,
                modifiers = 0,
            )
        val key =
            RuntimeBridgeKeyEvent(
                windowId =
                    0x200000001L,
                action =
                    RuntimeDisplayBridgePayloadCodec
                        .KEY_ACTION_DOWN,
                keyCode = 0x41,
                scanCode = 0x1e,
                modifiers = 0,
                repeatCount = 0,
            )

        assertTrue(
            bridge.sendPointer(
                pointer,
            ).isSuccess,
        )
        assertTrue(
            bridge.sendKey(
                key,
            ).isSuccess,
        )
        assertEquals(
            listOf(pointer),
            firstPointers,
        )
        assertEquals(
            listOf(key),
            secondKeys,
        )
    }

    @Test
    fun topmostWindowsStayAboveNormalWindowsAcrossPeers() {
        val bridge =
            RuntimeDesktopBridge()
        val first =
            bridge.bind {
                Result.success(Unit)
            }
        val second =
            bridge.bind {
                Result.success(Unit)
            }

        first.publish(
            listOf(
                window(
                    1L,
                    0,
                    topmost = true,
                ),
            ),
        )
        second.publish(
            listOf(
                window(
                    2L,
                    0,
                    topmost = false,
                ),
            ),
        )

        assertEquals(
            listOf(2L, 1L),
            bridge.windows.value
                .map {
                    it.windowId
                },
        )
        assertEquals(
            listOf(0, 1),
            bridge.windows.value
                .map {
                    it.zIndex
                },
        )
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
        assertTrue(
            bridge.windows.value
                .isEmpty(),
        )

        binding.close()
        assertFalse(
            runCatching {
                binding.publish(
                    emptyList(),
                )
            }.isSuccess,
        )
    }
}
