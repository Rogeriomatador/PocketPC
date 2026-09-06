package dev.pocketpc.core.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

data class SystemSnapshot(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val abis: List<String>,
    val cpuCores: Int,
    val glEsVersion: String,
    val internalTotalBytes: Long,
    val internalFreeBytes: Long,
    val vulkanFeatures: List<String>,
)

fun collectSystemSnapshot(context: Context): SystemSnapshot {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val statFs = StatFs(context.filesDir.absolutePath)
    val vulkan = context.packageManager.systemAvailableFeatures.orEmpty()
        .asSequence()
        .filter { it.name?.startsWith("android.hardware.vulkan") == true }
        .map { feature -> "${feature.name} (version=${feature.version})" }
        .sorted()
        .toList()

    return SystemSnapshot(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidVersion = Build.VERSION.RELEASE ?: "?",
        apiLevel = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        cpuCores = Runtime.getRuntime().availableProcessors(),
        glEsVersion = activityManager.deviceConfigurationInfo.glEsVersion ?: "indisponível",
        internalTotalBytes = statFs.totalBytes,
        internalFreeBytes = statFs.availableBytes,
        vulkanFeatures = vulkan,
    )
}
