package dev.pocketpc.core.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.view.Display

data class SystemSnapshot(
    val manufacturer: String,
    val model: String,
    val socManufacturer: String,
    val socModel: String,
    val hardware: String,
    val board: String,
    val androidVersion: String,
    val apiLevel: Int,
    val securityPatch: String,
    val kernelVersion: String,
    val abis: List<String>,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val glEsVersion: String,
    val internalTotalBytes: Long,
    val internalFreeBytes: Long,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val batteryPercent: Int,
    val batteryTemperatureC: Float?,
    val networkTransport: String,
    val vulkanFeatures: List<String>,
)

fun collectSystemSnapshot(context: Context): SystemSnapshot {
    val activityManager =
        context.getSystemService(
            Context.ACTIVITY_SERVICE
        ) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val statFs = StatFs(context.filesDir.absolutePath)

    val vulkan =
        context.packageManager.systemAvailableFeatures
            .orEmpty()
            .asSequence()
            .filter {
                it.name?.startsWith(
                    "android.hardware.vulkan"
                ) == true
            }
            .map { feature ->
                "${feature.name} (version=${feature.version})"
            }
            .sorted()
            .toList()

    val displayManager =
        context.getSystemService(
            Context.DISPLAY_SERVICE
        ) as DisplayManager
    val display =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    val displayMode = display?.mode
    val metrics = context.resources.displayMetrics

    val batteryManager =
        context.getSystemService(
            Context.BATTERY_SERVICE
        ) as BatteryManager
    val batteryPercent =
        batteryManager
            .getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CAPACITY
            )
            .coerceIn(0, 100)
    val batteryIntent =
        context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
    val batteryTemperatureC =
        batteryIntent
            ?.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                Int.MIN_VALUE,
            )
            ?.takeIf { it != Int.MIN_VALUE }
            ?.div(10f)

    val connectivity =
        context.getSystemService(
            Context.CONNECTIVITY_SERVICE
        ) as ConnectivityManager
    val network = connectivity.activeNetwork
    val capabilities =
        network?.let(connectivity::getNetworkCapabilities)
    val transport =
        when {
            capabilities == null ->
                "Offline"
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ) ->
                "Ethernet"
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) ->
                "Wi-Fi"
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) ->
                "Rede móvel"
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN
            ) ->
                "VPN"
            else ->
                "Rede ativa"
        }

    val socManufacturer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
                .ifBlank { Build.MANUFACTURER }
        } else {
            Build.MANUFACTURER
        }

    val socModel =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
                .ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

    return SystemSnapshot(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        socManufacturer = socManufacturer,
        socModel = socModel,
        hardware = Build.HARDWARE,
        board = Build.BOARD,
        androidVersion =
            Build.VERSION.RELEASE ?: "?",
        apiLevel = Build.VERSION.SDK_INT,
        securityPatch =
            Build.VERSION.SECURITY_PATCH
                .orEmpty()
                .ifBlank { "indisponível" },
        kernelVersion =
            System.getProperty("os.version")
                .orEmpty()
                .ifBlank { "indisponível" },
        abis = Build.SUPPORTED_ABIS.toList(),
        cpuCores =
            Runtime.getRuntime().availableProcessors(),
        totalRamBytes = memoryInfo.totalMem,
        availableRamBytes = memoryInfo.availMem,
        glEsVersion =
            activityManager
                .deviceConfigurationInfo
                .glEsVersion
                ?: "indisponível",
        internalTotalBytes = statFs.totalBytes,
        internalFreeBytes = statFs.availableBytes,
        displayWidthPx =
            displayMode?.physicalWidth
                ?: metrics.widthPixels,
        displayHeightPx =
            displayMode?.physicalHeight
                ?: metrics.heightPixels,
        densityDpi = metrics.densityDpi,
        refreshRateHz =
            displayMode?.refreshRate
                ?: display?.refreshRate
                ?: 0f,
        batteryPercent = batteryPercent,
        batteryTemperatureC = batteryTemperatureC,
        networkTransport = transport,
        vulkanFeatures = vulkan,
    )
}
