package dev.pocketpc.core.runtime

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dev.pocketpc.core.desktop.DesktopCapabilityProbe
import dev.pocketpc.core.desktop.DesktopCapabilitySnapshot
import dev.pocketpc.core.desktop.DesktopPeripheralProbe
import dev.pocketpc.core.desktop.PeripheralSnapshot
import dev.pocketpc.core.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

data class DeviceEvidenceReport(
    val generatedAtUtc: String,
    val manufacturer: String,
    val model: String,
    val androidApi: Int,
    val abis: List<String>,
    val filesystem: FilesystemEvidence,
    val desktopCapabilities: DesktopCapabilitySnapshot,
    val peripherals: PeripheralSnapshot,
    val orientationLandscape: Boolean,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val nativeHostLoaded: Boolean,
    val nativeHostProbe: String,
    val substrateState: String,
    val substrateApproved: Boolean,
    val policyDigestsVerified: Boolean,
    val artifactIntegrityVerified: Boolean,
    val prootReady: Boolean,
    val buildIdentity: BuildIdentity,
    val outputFile: File,
    val outputSha256: String,
    val sha256File: File,
)

object DeviceEvidenceCollector {
    suspend fun collectAndPersist(
        context: Context,
        nativeHost: NativeHostStatus,
        substrate: ExecutionSubstrateStatus,
    ): Result<DeviceEvidenceReport> = withContext(Dispatchers.IO) {
        runCatching {
            val evidenceRoot = File(
                context.noBackupFilesDir,
                "device-evidence",
            ).apply {
                require(mkdirs() || isDirectory) {
                    "Não foi possível criar device-evidence."
                }
            }

            val filesystem = FilesystemEvidenceProbe.run(
                File(context.cacheDir, "pocketpc-selftest")
            )
            val desktopCapabilities = DesktopCapabilityProbe.inspect(context)
            val peripherals = DesktopPeripheralProbe.inspect(context)
            val configuration = context.resources.configuration
            val orientationLandscape =
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val identity = BuildIdentityCollector.collect(context)
            val generated = Instant.now().toString()
            val output = File(evidenceRoot, "device-evidence-latest.json")
            val shaFile = File(evidenceRoot, "device-evidence-latest.sha256")

            val json = JSONObject()
                .put("schemaVersion", 4)
                .put("pocketPcVersion", BuildConfig.VERSION_NAME)
                .put("generatedAtUtc", generated)
                .put("buildIdentity", BuildIdentityCollector.toJson(identity))
                .put(
                    "device",
                    JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("androidApi", Build.VERSION.SDK_INT)
                        .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())),
                )
                .put(
                    "desktop",
                    JSONObject()
                        .put(
                            "orientationLandscape",
                            orientationLandscape,
                        )
                        .put(
                            "screenWidthDp",
                            configuration.screenWidthDp,
                        )
                        .put(
                            "screenHeightDp",
                            configuration.screenHeightDp,
                        )
                        .put(
                            "secondaryDisplayActivities",
                            desktopCapabilities.secondaryDisplayActivities,
                        )
                        .put(
                            "freeformWindowManagement",
                            desktopCapabilities.freeformWindowManagement,
                        )
                        .put(
                            "pcHardwareType",
                            desktopCapabilities.pcHardwareType,
                        )
                        .put(
                            "externalDisplayCount",
                            desktopCapabilities.externalDisplayCount,
                        )
                        .put(
                            "presentationDisplayCount",
                            desktopCapabilities.presentationDisplayCount,
                        )
                        .put(
                            "peripherals",
                            JSONObject()
                                .put("mouseCount", peripherals.mouseCount)
                                .put(
                                    "keyboardCount",
                                    peripherals.keyboardCount,
                                )
                                .put(
                                    "gamepadCount",
                                    peripherals.gamepadCount,
                                ),
                        )
                        .put(
                            "externalDisplays",
                            JSONArray().apply {
                                desktopCapabilities.externalDisplays.forEach {
                                    display ->
                                    put(
                                        JSONObject()
                                            .put(
                                                "displayId",
                                                display.displayId,
                                            )
                                            .put("name", display.name)
                                            .put(
                                                "widthPx",
                                                display.widthPx,
                                            )
                                            .put(
                                                "heightPx",
                                                display.heightPx,
                                            )
                                            .put(
                                                "refreshRateHz",
                                                display.refreshRateHz,
                                            )
                                            .put(
                                                "presentation",
                                                display.presentation,
                                            )
                                            .put("state", display.state)
                                    )
                                }
                            },
                        ),
                )
                .put(
                    "filesystem",
                    JSONObject()
                        .put("hostCriticalPassed", filesystem.hostCriticalPassed)
                        .put(
                            "runtimeLinkSemanticsReady",
                            filesystem.runtimeLinkSemanticsReady,
                        )
                        .put("allCriticalPassed", filesystem.allCriticalPassed)
                        .put("relativeSymlink", capability(filesystem.relativeSymlink))
                        .put("absoluteSymlink", capability(filesystem.absoluteSymlink))
                        .put("hardlink", capability(filesystem.hardlink))
                        .put("noFollowCleanup", capability(filesystem.noFollowCleanup))
                        .put(
                            "externalTargetPreserved",
                            capability(filesystem.externalTargetPreserved),
                        ),
                )
                .put(
                    "nativeHost",
                    JSONObject()
                        .put("loaded", nativeHost.loaded)
                        .put("probe", nativeHost.probe)
                        .put("graphicsProbe", nativeHost.graphicsProbe)
                        .put("nativeLibraryDir", nativeHost.nativeLibraryDir),
                )
                .put(
                    "substrate",
                    JSONObject()
                        .put("state", substrate.state)
                        .put("approvalManifestApproved", substrate.artifactContractApproved)
                        .put("policyDigestsVerified", substrate.policyDigestsVerified)
                        .put("artifactIntegrityVerified", substrate.artifactIntegrityVerified)
                        .put("prootReady", substrate.prootReady)
                        .put("approvalErrors", JSONArray(substrate.approvalErrors))
                        .put(
                            "components",
                            JSONArray().apply {
                                substrate.components.forEach { component ->
                                    put(
                                        JSONObject()
                                            .put("fileName", component.fileName)
                                            .put("role", component.role)
                                            .put("exists", component.exists)
                                            .put("readable", component.readable)
                                            .put("executable", component.executable)
                                            .put(
                                                "executableRequired",
                                                component.executableRequired,
                                            )
                                    )
                                }
                            },
                        ),
                )

            val temp = File(evidenceRoot, ".device-evidence-${System.nanoTime()}.tmp")
            temp.writeText(json.toString(2))
            require(temp.renameTo(output) || run {
                output.delete()
                temp.renameTo(output)
            }) {
                "Não foi possível promover device evidence."
            }

            val outputSha = sha256(output)
            val shaTemp = File(evidenceRoot, ".device-evidence-sha-${System.nanoTime()}.tmp")
            shaTemp.writeText("$outputSha  ${output.name}\n")
            require(shaTemp.renameTo(shaFile) || run {
                shaFile.delete()
                shaTemp.renameTo(shaFile)
            }) {
                "Não foi possível promover SHA-256 da evidence."
            }

            DeviceEvidenceReport(
                generatedAtUtc = generated,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                androidApi = Build.VERSION.SDK_INT,
                abis = Build.SUPPORTED_ABIS.toList(),
                filesystem = filesystem,
                desktopCapabilities = desktopCapabilities,
                peripherals = peripherals,
                orientationLandscape = orientationLandscape,
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                nativeHostLoaded = nativeHost.loaded,
                nativeHostProbe = nativeHost.probe,
                substrateState = substrate.state,
                substrateApproved = substrate.artifactContractApproved,
                policyDigestsVerified = substrate.policyDigestsVerified,
                artifactIntegrityVerified = substrate.artifactIntegrityVerified,
                prootReady = substrate.prootReady,
                buildIdentity = identity,
                outputFile = output,
                outputSha256 = outputSha,
                sha256File = shaFile,
            )
        }
    }

    private fun capability(value: CapabilityEvidence): JSONObject =
        JSONObject()
            .put("passed", value.passed)
            .put("detail", value.detail)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
