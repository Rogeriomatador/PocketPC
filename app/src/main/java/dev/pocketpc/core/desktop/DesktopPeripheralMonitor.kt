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

        for (deviceId in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(deviceId) ?: continue
            if (device.isVirtual) continue

            val sources = device.sources

            if (hasSource(sources, InputDevice.SOURCE_MOUSE)) {
                mice++
            }

            if (
                device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE &&
                hasSource(sources, InputDevice.SOURCE_KEYBOARD)
            ) {
                keyboards++
            }

            if (
                hasSource(sources, InputDevice.SOURCE_GAMEPAD) ||
                hasSource(sources, InputDevice.SOURCE_JOYSTICK)
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

    private fun hasSource(sources: Int, source: Int): Boolean =
        sources.and(source) == source
}
