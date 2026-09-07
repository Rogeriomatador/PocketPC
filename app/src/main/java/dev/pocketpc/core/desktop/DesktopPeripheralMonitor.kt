package dev.pocketpc.core.desktop

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PeripheralSnapshot(
    val mouseCount: Int,
    val keyboardCount: Int,
    val gamepadCount: Int,
) {
    val desktopInputActive: Boolean
        get() = mouseCount > 0 || keyboardCount > 0
}

class DesktopPeripheralMonitor(context: Context) : InputManager.InputDeviceListener {
    private val inputManager =
        context.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val mutableState = MutableStateFlow(scan())
    val state: StateFlow<PeripheralSnapshot> = mutableState.asStateFlow()

    fun start() {
        inputManager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun stop() {
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()

    override fun onInputDeviceRemoved(deviceId: Int) = refresh()

    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        mutableState.value = scan()
    }

    private fun scan(): PeripheralSnapshot {
        var mice = 0
        var keyboards = 0
        var gamepads = 0

        InputDevice.getDeviceIds()
            .mapNotNull { deviceId -> InputDevice.getDevice(deviceId) }
            .filterNot { it.isVirtual }
            .forEach { device ->
                val sources = device.sources
                if (
                    (sources and InputDevice.SOURCE_MOUSE) ==
                    InputDevice.SOURCE_MOUSE
                ) {
                    mice++
                }
                if (
                    device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE &&
                    (sources and InputDevice.SOURCE_KEYBOARD) ==
                    InputDevice.SOURCE_KEYBOARD
                ) {
                    keyboards++
                }
                if (
                    (sources and InputDevice.SOURCE_GAMEPAD) ==
                        InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) ==
                        InputDevice.SOURCE_JOYSTICK
                ) {
                    gamepads++
                }
            }

        return PeripheralSnapshot(
            mouseCount = mice,
            keyboardCount = keyboards,
            gamepadCount = gamepads,
        )
    }
}
