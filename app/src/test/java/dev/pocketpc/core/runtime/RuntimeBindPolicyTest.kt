package dev.pocketpc.core.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeBindPolicyTest {
    @Test
    fun acceptsSystemBindInsideAllowlistedHostRoot() {
        val root = Files.createTempDirectory("pocketpc-bind-").toFile()
        try {
            val child = root.resolve("home").apply { mkdirs() }
            val result = RuntimeBindPolicy.validate(
                listOf(
                    RuntimeBindSpec(
                        hostPath = child,
                        guestPath = "/home/pocket",
                        readOnly = false,
                        purpose = "home",
                        authority = BindAuthority.SYSTEM,
                    )
                ),
                listOf(root),
            )
            assertTrue(result.errors.joinToString(), result.valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUserBindToReservedGuestPath() {
        val root = Files.createTempDirectory("pocketpc-bind-").toFile()
        try {
            val result = RuntimeBindPolicy.validate(
                listOf(
                    RuntimeBindSpec(
                        hostPath = root,
                        guestPath = "/proc",
                        readOnly = true,
                        purpose = "bad",
                        authority = BindAuthority.USER,
                    )
                ),
                listOf(root),
            )
            assertFalse(result.valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsGuestTraversal() {
        assertTrue(RuntimeBindPolicy.normalizeGuestPath("/home/../etc") == null)
    }

    @Test
    fun rejectsHostOutsideAllowlist() {
        val allowed = Files.createTempDirectory("pocketpc-allowed-").toFile()
        val outside = Files.createTempDirectory("pocketpc-outside-").toFile()
        try {
            val result = RuntimeBindPolicy.validate(
                listOf(
                    RuntimeBindSpec(
                        hostPath = outside,
                        guestPath = "/mnt/data",
                        readOnly = true,
                        purpose = "outside",
                        authority = BindAuthority.USER,
                    )
                ),
                listOf(allowed),
            )
            assertFalse(result.valid)
        } finally {
            allowed.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun migratesOneLegacyVersionHomeIntoStableUserHome() {
        val filesDir =
            Files.createTempDirectory(
                "pocketpc-runtime-home-migration-",
            ).toFile()
        try {
            val legacy =
                File(
                    filesDir,
                    "runtime-home/ubuntu/1.0",
                ).apply {
                    mkdirs()
                }
            val registry =
                File(
                    legacy,
                    "windows-prefixes/smoke/user.reg",
                ).apply {
                    parentFile?.mkdirs()
                    writeText("persist-me")
                }

            val migrated =
                RuntimeUserHomeLayout.resolve(
                    filesDir = filesDir,
                    runtimeId = "ubuntu",
                    runtimeVersion = "2.0",
                )

            assertEquals(".user", migrated.name)
            assertFalse(legacy.exists())
            assertTrue(
                File(
                    migrated,
                    "windows-prefixes/smoke/user.reg",
                ).isFile,
            )
            assertEquals(
                "persist-me",
                File(
                    migrated,
                    "windows-prefixes/smoke/user.reg",
                ).readText(),
            )
            assertFalse(registry.exists())

            val afterAnotherRuntimeUpgrade =
                RuntimeUserHomeLayout.resolve(
                    filesDir = filesDir,
                    runtimeId = "ubuntu",
                    runtimeVersion = "3.0",
                )
            assertEquals(
                migrated.canonicalFile,
                afterAnotherRuntimeUpgrade.canonicalFile,
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun stableUserHomeSurvivesVersionPruning() {
        val filesDir =
            Files.createTempDirectory(
                "pocketpc-runtime-home-prune-",
            ).toFile()
        try {
            val stable =
                RuntimeUserHomeLayout.resolve(
                    filesDir = filesDir,
                    runtimeId = "ubuntu",
                    runtimeVersion = "1.0",
                )
            val marker =
                File(stable, "account-session.marker")
                    .apply {
                        writeText("keep")
                    }
            val runtimeHomeRoot =
                File(filesDir, "runtime-home")
            File(runtimeHomeRoot, "ubuntu/1.0")
                .mkdirs()
            val keep =
                File(runtimeHomeRoot, "ubuntu/2.0")
                    .apply {
                        mkdirs()
                    }

            val result =
                VersionedInstallPruner.prune(
                    containerRoot = runtimeHomeRoot,
                    componentId = "ubuntu",
                    keepVersion = "2.0",
                )

            assertTrue(result.failedVersions.isEmpty())
            assertTrue(stable.isDirectory)
            assertTrue(marker.isFile)
            assertEquals("keep", marker.readText())
            assertTrue(keep.isDirectory)
            assertFalse(
                File(
                    runtimeHomeRoot,
                    "ubuntu/1.0",
                ).exists(),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun ambiguousLegacyHomesFailClosedWithoutDeletingEither() {
        val filesDir =
            Files.createTempDirectory(
                "pocketpc-runtime-home-ambiguous-",
            ).toFile()
        try {
            val first =
                File(
                    filesDir,
                    "runtime-home/ubuntu/1.0",
                ).apply {
                    mkdirs()
                    File(this, "first.marker")
                        .writeText("first")
                }
            val second =
                File(
                    filesDir,
                    "runtime-home/ubuntu/2.0",
                ).apply {
                    mkdirs()
                    File(this, "second.marker")
                        .writeText("second")
                }

            val result =
                runCatching {
                    RuntimeUserHomeLayout.resolve(
                        filesDir = filesDir,
                        runtimeId = "ubuntu",
                        runtimeVersion = "3.0",
                    )
                }

            assertTrue(result.isFailure)
            assertTrue(
                result.exceptionOrNull()
                    ?.message
                    ?.startsWith(
                        "RUNTIME_USER_HOME_MIGRATION_AMBIGUOUS:"
                    ) == true,
            )
            assertTrue(first.isDirectory)
            assertTrue(second.isDirectory)
            assertTrue(File(first, "first.marker").isFile)
            assertTrue(File(second, "second.marker").isFile)
            assertFalse(
                File(
                    filesDir,
                    "runtime-home/ubuntu/.user",
                ).exists(),
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
