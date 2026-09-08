package dev.pocketpc.core.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.InputDevice

data class RuntimeIoHostCapabilities(
    val networkInternetCapable: Boolean,
    val networkValidated: Boolean,
    val audioOutputCount: Int,
    val keyboardCount: Int,
    val mouseCount: Int,
    val gamepadCount: Int,
) {
    val inputDeviceCount: Int
        get() = keyboardCount + mouseCount + gamepadCount
}

object RuntimeIoCapabilityProbe {
    fun inspect(context: Context): RuntimeIoHostCapabilities {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivity?.activeNetwork
        val networkCapabilities =
            network?.let { connectivity.getNetworkCapabilities(it) }

        val audio =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val outputs =
            runCatching {
                audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.size ?: 0
            }.getOrDefault(0)

        val inputManager =
            context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        val devices =
            inputManager
                ?.inputDeviceIds
                ?.mapNotNull { id ->
                    runCatching { inputManager.getInputDevice(id) }.getOrNull()
                }
                .orEmpty()

        fun countSource(source: Int): Int =
            devices.count { device ->
                device.sources and source == source
            }

        val gamepads =
            devices.count { device ->
                val sources = device.sources
                (sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
                    (sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            }

        return RuntimeIoHostCapabilities(
            networkInternetCapable =
                networkCapabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                ) == true,
            networkValidated =
                networkCapabilities?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true,
            audioOutputCount = outputs,
            keyboardCount = countSource(InputDevice.SOURCE_KEYBOARD),
            mouseCount = countSource(InputDevice.SOURCE_MOUSE),
            gamepadCount = gamepads,
        )
    }
}
