package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

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
}
