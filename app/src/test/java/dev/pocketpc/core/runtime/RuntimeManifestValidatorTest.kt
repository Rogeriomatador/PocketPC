package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestValidatorTest {
    private fun validManifest() = RuntimeManifest(
        schemaVersion = 1,
        id = "debian.bookworm",
        name = "Debian Bookworm",
        version = "12.9-1",
        architecture = "aarch64",
        rootfsSha256 = "a".repeat(64),
        rootfsBytes = 1_048_576L,
        entrypoint = "/bin/sh",
        license = "Debian redistribution terms apply",
    )

    @Test
    fun acceptsSafeArm64Manifest() {
        val result = RuntimeManifestValidator.validate(validManifest(), listOf("arm64-v8a"))
        assertTrue(result.errors.joinToString(), result.valid)
    }

    @Test
    fun rejectsVersionPathTraversal() {
        val result = RuntimeManifestValidator.validate(
            validManifest().copy(version = "../../escape"),
            listOf("arm64-v8a"),
        )
        assertFalse(result.valid)
    }

    @Test
    fun rejectsEntrypointTraversal() {
        val result = RuntimeManifestValidator.validate(
            validManifest().copy(entrypoint = "/usr/../bin/sh"),
            listOf("arm64-v8a"),
        )
        assertFalse(result.valid)
    }

    @Test
    fun rejectsUnsupportedDeviceAbi() {
        val result = RuntimeManifestValidator.validate(validManifest(), listOf("x86_64"))
        assertFalse(result.valid)
    }

    @Test
    fun rejectsMalformedHash() {
        val result = RuntimeManifestValidator.validate(
            validManifest().copy(rootfsSha256 = "not-a-hash"),
            listOf("arm64-v8a"),
        )
        assertFalse(result.valid)
    }
}
