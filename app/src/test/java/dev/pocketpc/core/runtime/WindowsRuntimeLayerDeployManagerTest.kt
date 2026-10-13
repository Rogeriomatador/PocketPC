package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WindowsRuntimeLayerDeployManagerTest {
    @Test
    fun deployAndRemoveRestoreOriginalSystemDlls() = runBlocking {
        val root = Files.createTempDirectory("pocketpc-layer-deploy-").toFile()
        try {
            val prefixPlan = createReadyPrefix(root)
            val prefix = requireNotNull(prefixPlan.layout)
            val system32 = File(prefix.prefixRoot, "drive_c/windows/system32").apply { mkdirs() }
            val originalD3d11 = File(system32, "d3d11.dll").apply { writeText("builtin-d3d11") }
            val originalDxgi = File(system32, "dxgi.dll").apply { writeText("builtin-dxgi") }
            val staged = createDxvkLayer(root.resolve("package"), "native-d3d11", "native-dxgi")
            val manager = WindowsRuntimeLayerDeployManager(root.resolve("state"))

            val deployed = manager.deploy(staged, prefixPlan).getOrThrow()

            assertEquals("native-d3d11", originalD3d11.readText())
            assertEquals("native-dxgi", originalDxgi.readText())
            assertEquals(1, manager.discover(prefixPlan).size)

            assertTrue(manager.remove(deployed, prefixPlan).getOrThrow())
            assertEquals("builtin-d3d11", originalD3d11.readText())
            assertEquals("builtin-dxgi", originalDxgi.readText())
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    @Test
    fun removalRefusesToOverwriteExternallyChangedDll() = runBlocking {
        val root = Files.createTempDirectory("pocketpc-layer-changed-").toFile()
        try {
            val prefixPlan = createReadyPrefix(root)
            val prefix = requireNotNull(prefixPlan.layout)
            val system32 = File(prefix.prefixRoot, "drive_c/windows/system32").apply { mkdirs() }
            File(system32, "d3d11.dll").writeText("builtin")
            File(system32, "dxgi.dll").writeText("builtin")
            val staged = createDxvkLayer(root.resolve("package"), "native-d3d11", "native-dxgi")
            val manager = WindowsRuntimeLayerDeployManager(root.resolve("state"))
            val deployed = manager.deploy(staged, prefixPlan).getOrThrow()

            File(system32, "d3d11.dll").writeText("external-change")
            val removal = manager.remove(deployed, prefixPlan)

            assertTrue(removal.isFailure)
            assertEquals("external-change", File(system32, "d3d11.dll").readText())
            assertFalse(removal.getOrNull() == true)
        } finally {
            SafeTreeOps.deleteNoFollow(root)
        }
    }

    private fun createReadyPrefix(root: File): WindowsPrefixPlan {
        val plan = WindowsPrefixPlanner.plan(root, "smoke")
        val layout = requireNotNull(plan.layout)
        layout.driveC.mkdirs()
        layout.dosDevices.mkdirs()
        File(layout.prefixRoot, "drive_c/windows/system32").mkdirs()
        layout.systemRegistry.writeText("system")
        layout.userRegistry.writeText("user")
        layout.userDefRegistry.writeText("userdef")
        return plan
    }

    private fun createDxvkLayer(
        directory: File,
        d3d11: String,
        dxgi: String,
    ): StagedWindowsRuntimeLayer {
        val dllDir = directory.resolve("dll").apply { mkdirs() }
        val d3d11File = dllDir.resolve("d3d11.dll").apply { writeText(d3d11) }
        val dxgiFile = dllDir.resolve("dxgi.dll").apply { writeText(dxgi) }

        fun record(file: File): WindowsRuntimeLayerFile {
            val digest = file.inputStream().use { Sha256.digest(it) }
            return WindowsRuntimeLayerFile(
                path = "dll/" + file.name,
                destinationName = file.name,
                sha256 = digest.sha256,
                bytes = digest.bytes,
            )
        }

        val records = listOf(record(d3d11File), record(dxgiFile))
        val manifest = WindowsRuntimeLayerManifest(
            schemaVersion = 1,
            id = "dxvk",
            version = "3.0.2",
            sourceCommit = "6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7",
            license = "Zlib",
            windowsArchitecture = "x86_64-windows",
            targetDirectory = "drive_c/windows/system32",
            files = records,
        )

        val json =
            "{" +
                "\"schemaVersion\":1," +
                "\"id\":\"dxvk\"," +
                "\"version\":\"3.0.2\"," +
                "\"sourceCommit\":\"6b20f622a77b87b2921fe5d2c1774d2f2ba3e9b7\"," +
                "\"license\":\"Zlib\"," +
                "\"windowsArchitecture\":\"x86_64-windows\"," +
                "\"targetDirectory\":\"drive_c/windows/system32\"," +
                "\"files\":[" +
                "{\"path\":\"dll/d3d11.dll\",\"destinationName\":\"d3d11.dll\",\"sha256\":\"" + records[0].sha256 + "\",\"bytes\":" + records[0].bytes + "}," +
                "{\"path\":\"dll/dxgi.dll\",\"destinationName\":\"dxgi.dll\",\"sha256\":\"" + records[1].sha256 + "\",\"bytes\":" + records[1].bytes + "}" +
                "]}"
        directory.resolve("windows-layer-manifest.json").writeText(json)
        return StagedWindowsRuntimeLayer(manifest, directory)
    }
}
