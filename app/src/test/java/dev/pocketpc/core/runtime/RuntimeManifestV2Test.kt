package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeManifestV2Test {
    private fun manifest() = RuntimeManifest(
        schemaVersion = 2,
        id = "debian.bookworm",
        name = "Debian Bookworm",
        version = "12.9-1",
        architecture = "aarch64",
        rootfsSha256 = "a".repeat(64),
        rootfsBytes = 100_000_000L,
        entrypoint = "/bin/sh",
        license = "Upstream license metadata",
        archiveFormat = "tar.gz",
        extractedBytesLimit = 1_000_000_000L,
        entryLimit = 500_000,
    )

    @Test
    fun acceptsExtractableSchemaV2() {
        val manifest = manifest()
        val validation = RuntimeManifestValidator.validate(manifest, listOf("arm64-v8a"))

        assertTrue(validation.errors.joinToString(), validation.valid)
        assertTrue(RuntimeManifestValidator.canExtract(manifest))
    }

    @Test
    fun rejectsUnknownArchiveFormat() {
        val manifest = manifest().copy(archiveFormat = "zip")
        val validation = RuntimeManifestValidator.validate(manifest, listOf("arm64-v8a"))

        assertFalse(validation.valid)
        assertFalse(RuntimeManifestValidator.canExtract(manifest))
    }

    @Test
    fun rejectsInvalidEntryLimit() {
        val validation = RuntimeManifestValidator.validate(
            manifest().copy(entryLimit = RuntimeManifestValidator.MAX_ENTRY_LIMIT + 1),
            listOf("arm64-v8a"),
        )
        assertFalse(validation.valid)
    }
}
