package dev.pocketpc.core.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.pocketpc.core.runtime.RuntimeBridgeKeyEvent
import dev.pocketpc.core.runtime.RuntimeBridgePointerEvent
import dev.pocketpc.core.runtime.RuntimeDesktopBridge
import dev.pocketpc.core.runtime.RuntimeDisplayBridgePayloadCodec
import dev.pocketpc.core.runtime.RuntimeDisplayCompositorWindow
import dev.pocketpc.core.runtime.RuntimeWindowsKeyMapper
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun RuntimeDesktopWindowLayer(
    windows:
        List<RuntimeDisplayCompositorWindow>,
    bridge: RuntimeDesktopBridge,
    modifier: Modifier = Modifier,
) {
    val layout =
        LocalDesktopLayout.current
    val density =
        LocalDensity.current

    val keyboardFocusRequester =
        remember {
            FocusRequester()
        }
    var focusedWindowId by
        remember {
            mutableStateOf<Long?>(
                null,
            )
        }
    val pressedPointerButtons =
        remember {
            mutableMapOf<Long, Int>()
        }

    LaunchedEffect(
        windows.map {
            it.windowId
        },
    ) {
        val current =
            focusedWindowId
        if (
            current != null &&
            windows.none {
                it.windowId == current
            }
        ) {
            focusedWindowId = null
        }
    }

    val workspaceWidthPx =
        with(density) {
            layout.widthDp.dp.toPx()
        }.coerceAtLeast(1f)
    val workspaceHeightPx =
        with(density) {
            (
                layout.heightDp -
                    layout.taskbarHeightDp
            ).dp.toPx()
        }.coerceAtLeast(1f)

    Box(
        modifier =
            modifier
                .focusRequester(
                    keyboardFocusRequester,
                )
                .onPreviewKeyEvent {
                    composeEvent ->
                    val windowId =
                        focusedWindowId
                            ?: return@onPreviewKeyEvent false
                    val native =
                        composeEvent
                            .nativeKeyEvent
                    val mapped =
                        RuntimeWindowsKeyMapper
                            .map(
                                native.keyCode,
                            )
                            ?: return@onPreviewKeyEvent false

                    val action =
                        when (
                            native.action
                        ) {
                            AndroidKeyEvent
                                .ACTION_DOWN ->
                                if (
                                    native.repeatCount >
                                    0
                                ) {
                                    RuntimeDisplayBridgePayloadCodec
                                        .KEY_ACTION_REPEAT
                                } else {
                                    RuntimeDisplayBridgePayloadCodec
                                        .KEY_ACTION_DOWN
                                }

                            AndroidKeyEvent
                                .ACTION_UP ->
                                RuntimeDisplayBridgePayloadCodec
                                    .KEY_ACTION_UP

                            else ->
                                return@onPreviewKeyEvent false
                        }

                    bridge.sendKey(
                        RuntimeBridgeKeyEvent(
                            windowId =
                                windowId,
                            action =
                                action,
                            keyCode =
                                mapped.virtualKey,
                            scanCode =
                                mapped.scanCode,
                            modifiers =
                                RuntimeWindowsKeyMapper
                                    .modifiers(
                                        native.metaState,
                                    ),
                            repeatCount =
                                if (
                                    action ==
                                    RuntimeDisplayBridgePayloadCodec
                                        .KEY_ACTION_REPEAT
                                ) {
                                    1
                                } else {
                                    0
                                },
                        ),
                    ).isSuccess
                }
                .focusable(),
    ) {
        windows
            .filter {
                it.geometry?.visible ==
                    true
            }
            .sortedBy {
                it.zIndex
            }
            .forEach { window ->
                val geometry =
                    window.geometry
                        ?: return@forEach

                val widthPx =
                    geometry.width
                        .toFloat()
                        .coerceIn(
                            1f,
                            workspaceWidthPx,
                        )
                val heightPx =
                    geometry.height
                        .toFloat()
                        .coerceIn(
                            1f,
                            workspaceHeightPx,
                        )
                val xPx =
                    geometry.x
                        .toFloat()
                        .coerceIn(
                            0f,
                            (
                                workspaceWidthPx -
                                    widthPx
                            ).coerceAtLeast(
                                0f,
                            ),
                        )
                val yPx =
                    geometry.y
                        .toFloat()
                        .coerceIn(
                            0f,
                            (
                                workspaceHeightPx -
                                    heightPx
                            ).coerceAtLeast(
                                0f,
                            ),
                        )

                val widthDp =
                    with(density) {
                        widthPx.toDp()
                    }
                val heightDp =
                    with(density) {
                        heightPx.toDp()
                    }

                Surface(
                    modifier =
                        Modifier
                            .zIndex(
                                1000f +
                                    window.zIndex
                                        .toFloat(),
                            )
                            .offset {
                                IntOffset(
                                    xPx.roundToInt(),
                                    yPx.roundToInt(),
                                )
                            }
                            .width(widthDp)
                            .height(heightDp),
                    shape =
                        RoundedCornerShape(
                            6.dp,
                        ),
                    shadowElevation = 12.dp,
                    tonalElevation = 2.dp,
                ) {
                    Box(
                        Modifier.fillMaxSize(),
                    ) {
                        window.frame
                            ?.let { frame ->
                                RuntimeDisplayFramePreview(
                                    frame = frame,
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInteropFilter {
                                                motion ->
                                                val displayWidth =
                                                    widthPx
                                                        .coerceAtLeast(
                                                            1f,
                                                        )
                                                val displayHeight =
                                                    heightPx
                                                        .coerceAtLeast(
                                                            1f,
                                                        )
                                                val x =
                                                    (
                                                        motion.x /
                                                            displayWidth *
                                                            frame.width
                                                    ).roundToInt()
                                                        .coerceIn(
                                                            0,
                                                            frame.width -
                                                                1,
                                                        )
                                                val y =
                                                    (
                                                        motion.y /
                                                            displayHeight *
                                                            frame.height
                                                    ).roundToInt()
                                                        .coerceIn(
                                                            0,
                                                            frame.height -
                                                                1,
                                                        )
                                                val modifiers =
                                                    RuntimeWindowsKeyMapper
                                                        .modifiers(
                                                            motion.metaState,
                                                        )

                                                fun focusRuntimeWindow() {
                                                    focusedWindowId =
                                                        window.windowId
                                                    keyboardFocusRequester
                                                        .requestFocus()
                                                    bridge.activate(
                                                        window.windowId,
                                                    )
                                                }

                                                fun send(
                                                    action: Int,
                                                    buttons: Int,
                                                    scroll: Int = 0,
                                                ): Boolean =
                                                    bridge.sendPointer(
                                                        RuntimeBridgePointerEvent(
                                                            windowId =
                                                                window.windowId,
                                                            action =
                                                                action,
                                                            x = x,
                                                            y = y,
                                                            buttons =
                                                                buttons,
                                                            verticalScroll =
                                                                scroll,
                                                            modifiers =
                                                                modifiers,
                                                        ),
                                                    ).isSuccess

                                                val previousButtons =
                                                    pressedPointerButtons[
                                                        window.windowId
                                                    ] ?: 0

                                                when (
                                                    motion.actionMasked
                                                ) {
                                                    MotionEvent
                                                        .ACTION_DOWN -> {
                                                        val reported =
                                                            runtimePointerButtonMask(
                                                                motion.buttonState,
                                                            )
                                                        val buttons =
                                                            if (
                                                                reported != 0
                                                            ) {
                                                                reported
                                                            } else {
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_BUTTON_PRIMARY
                                                            }

                                                        pressedPointerButtons[
                                                            window.windowId
                                                        ] =
                                                            previousButtons or
                                                                buttons
                                                        focusRuntimeWindow()
                                                        send(
                                                            RuntimeDisplayBridgePayloadCodec
                                                                .POINTER_ACTION_DOWN,
                                                            buttons,
                                                        )
                                                    }

                                                    MotionEvent
                                                        .ACTION_UP -> {
                                                        val buttons =
                                                            previousButtons
                                                        pressedPointerButtons
                                                            .remove(
                                                                window.windowId,
                                                            )
                                                        if (
                                                            buttons == 0 &&
                                                            motion.getToolType(
                                                                0,
                                                            ) ==
                                                            MotionEvent
                                                                .TOOL_TYPE_MOUSE
                                                        ) {
                                                            true
                                                        } else {
                                                            send(
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_ACTION_UP,
                                                                if (
                                                                    buttons != 0
                                                                ) {
                                                                    buttons
                                                                } else {
                                                                    RuntimeDisplayBridgePayloadCodec
                                                                        .POINTER_BUTTON_PRIMARY
                                                                },
                                                            )
                                                        }
                                                    }

                                                    MotionEvent
                                                        .ACTION_CANCEL -> {
                                                        pressedPointerButtons
                                                            .remove(
                                                                window.windowId,
                                                            )
                                                        if (
                                                            previousButtons ==
                                                            0
                                                        ) {
                                                            true
                                                        } else {
                                                            send(
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_ACTION_UP,
                                                                previousButtons,
                                                            )
                                                        }
                                                    }

                                                    MotionEvent
                                                        .ACTION_BUTTON_PRESS -> {
                                                        val changed =
                                                            runtimePointerButtonMask(
                                                                motion.actionButton,
                                                            )
                                                        if (
                                                            changed == 0
                                                        ) {
                                                            false
                                                        } else if (
                                                            previousButtons and
                                                                changed != 0
                                                        ) {
                                                            true
                                                        } else {
                                                            pressedPointerButtons[
                                                                window.windowId
                                                            ] =
                                                                previousButtons or
                                                                    changed
                                                            focusRuntimeWindow()
                                                            send(
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_ACTION_DOWN,
                                                                changed,
                                                            )
                                                        }
                                                    }

                                                    MotionEvent
                                                        .ACTION_BUTTON_RELEASE -> {
                                                        val changed =
                                                            runtimePointerButtonMask(
                                                                motion.actionButton,
                                                            )
                                                        if (
                                                            changed == 0
                                                        ) {
                                                            false
                                                        } else if (
                                                            previousButtons and
                                                                changed == 0
                                                        ) {
                                                            true
                                                        } else {
                                                            val remaining =
                                                                previousButtons and
                                                                    changed.inv()
                                                            if (
                                                                remaining == 0
                                                            ) {
                                                                pressedPointerButtons
                                                                    .remove(
                                                                        window.windowId,
                                                                    )
                                                            } else {
                                                                pressedPointerButtons[
                                                                    window.windowId
                                                                ] =
                                                                    remaining
                                                            }
                                                            send(
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_ACTION_UP,
                                                                changed,
                                                            )
                                                        }
                                                    }

                                                    MotionEvent
                                                        .ACTION_MOVE,
                                                    MotionEvent
                                                        .ACTION_HOVER_MOVE -> {
                                                        val reported =
                                                            runtimePointerButtonMask(
                                                                motion.buttonState,
                                                            )
                                                        send(
                                                            RuntimeDisplayBridgePayloadCodec
                                                                .POINTER_ACTION_MOVE,
                                                            if (
                                                                reported != 0
                                                            ) {
                                                                reported
                                                            } else {
                                                                previousButtons
                                                            },
                                                        )
                                                    }

                                                    MotionEvent
                                                        .ACTION_SCROLL -> {
                                                        val wheel =
                                                            (
                                                                motion.getAxisValue(
                                                                    MotionEvent
                                                                        .AXIS_VSCROLL,
                                                                ) *
                                                                    WINDOWS_WHEEL_DELTA
                                                            ).roundToInt()
                                                        if (
                                                            wheel == 0
                                                        ) {
                                                            false
                                                        } else {
                                                            send(
                                                                RuntimeDisplayBridgePayloadCodec
                                                                    .POINTER_ACTION_SCROLL,
                                                                previousButtons,
                                                                wheel,
                                                            )
                                                        }
                                                    }

                                                    else -> false
                                                }
                                            },
                                    contentScale =
                                        ContentScale
                                            .FillBounds,
                                )
                            }
                            ?: Box(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            MaterialTheme
                                                .colorScheme
                                                .surfaceVariant,
                                        ),
                                contentAlignment =
                                    Alignment.Center,
                            ) {
                                Text(
                                    "Aguardando frame Win32…",
                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,
                                )
                            }

                        Row(
                            modifier =
                                Modifier
                                    .align(
                                        Alignment.TopEnd,
                                    )
                                    .clip(
                                        RoundedCornerShape(
                                            bottomStart =
                                                8.dp,
                                        ),
                                    )
                                    .background(
                                        MaterialTheme
                                            .colorScheme
                                            .surfaceContainerHigh
                                            .copy(
                                                alpha =
                                                    0.94f,
                                            ),
                                    )
                                    .padding(
                                        horizontal =
                                            2.dp,
                                    ),
                            verticalAlignment =
                                Alignment.CenterVertically,
                        ) {
                            Text(
                                "Win32 #${window.windowId}",
                                modifier =
                                    Modifier.padding(
                                        horizontal =
                                            6.dp,
                                    ),
                                style =
                                    MaterialTheme
                                        .typography
                                        .labelSmall,
                            )
                            RuntimeWindowControl(
                                "—",
                            ) {
                                bridge.minimize(
                                    window.windowId,
                                )
                            }
                            RuntimeWindowControl(
                                "□",
                            ) {
                                bridge.maximize(
                                    window.windowId,
                                )
                            }
                            RuntimeWindowControl(
                                "×",
                                danger = true,
                            ) {
                                bridge.closeWindow(
                                    window.windowId,
                                )
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun RuntimeWindowControl(
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier =
            Modifier.size(
                34.dp,
            ),
        contentPadding =
            PaddingValues(0.dp),
    ) {
        Text(
            label,
            color =
                if (danger) {
                    MaterialTheme
                        .colorScheme
                        .error
                } else {
                    MaterialTheme
                        .colorScheme
                        .onSurface
                },
        )
    }
}
