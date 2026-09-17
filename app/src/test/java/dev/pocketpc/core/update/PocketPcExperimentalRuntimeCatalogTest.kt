package dev.pocketpc.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PocketPcExperimentalRuntimeCatalogTest {
    private val revision = "a".repeat(40)

    @Test
    fun oldFeedWithoutRuntimeOfferRemainsCompatible() {
        val parsed =
            parsePocketPcExperimentalRuntimeOffer(
                raw = feed(runtime = null),
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = true,
            )
        assertNull(parsed)
    }

    @Test
    fun exactPinnedRevisionAcceptsV52Offer() {
        val parsed =
            requireNotNull(
                parsePocketPcExperimentalRuntimeOffer(
                    raw = feed(runtime = runtimeOffer(revision)),
                    expectedPackageName = "dev.pocketpc.core",
                    installedPocketPcSourceRevision = revision,
                    installedPocketPcSourceRevisionPinned = true,
                )
            )
        assertEquals("wine", parsed.kind)
        assertEquals("11-v52-test", parsed.guestToolVersion)
        assertEquals(52, parsed.wineVulkanAbi)
        assertEquals(revision, parsed.pocketPcSourceRevision)
        assertEquals(
            setOf("pocketpc.vulkan.continuous-present.v52"),
            parsed.capabilities,
        )
    }

    @Test
    fun unpinnedApkRejectsExperimentalRuntime() {
        expectFailure("EXPERIMENTAL_RUNTIME_APK_REVISION_NOT_PINNED") {
            parsePocketPcExperimentalRuntimeOffer(
                raw = feed(runtime = runtimeOffer(revision)),
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = false,
            )
        }
    }

    @Test
    fun differentPocketPcRevisionRejectsExperimentalRuntime() {
        expectFailure("EXPERIMENTAL_RUNTIME_REVISION_MISMATCH") {
            parsePocketPcExperimentalRuntimeOffer(
                raw = feed(runtime = runtimeOffer("b".repeat(40))),
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = true,
            )
        }
    }

    @Test
    fun missingGuestToolVersionRejectsExperimentalRuntime() {
        val runtime = runtimeOffer(revision).replace(
            "\"guestToolVersion\":\"11-v52-test\",",
            "",
        )
        expectFailure("EXPERIMENTAL_RUNTIME_GUEST_TOOL_VERSION_INVALID") {
            parsePocketPcExperimentalRuntimeOffer(
                raw = feed(runtime = runtime),
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = true,
            )
        }
    }

    @Test
    fun extraCapabilityRejectsExperimentalRuntime() {
        val runtime = runtimeOffer(revision).replace(
            "\"pocketpc.vulkan.continuous-present.v52\"",
            "\"pocketpc.vulkan.continuous-present.v52\", \"unexpected\"",
        )
        expectFailure("EXPERIMENTAL_RUNTIME_CAPABILITY_SET_INVALID") {
            parsePocketPcExperimentalRuntimeOffer(
                raw = feed(runtime = runtime),
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = true,
            )
        }
    }

    @Test
    fun unpublishedFeedDoesNotExposeRuntimeOffer() {
        val raw = feed(runtime = runtimeOffer(revision))
            .replace("\"published\": true", "\"published\": false")
        val parsed =
            parsePocketPcExperimentalRuntimeOffer(
                raw = raw,
                expectedPackageName = "dev.pocketpc.core",
                installedPocketPcSourceRevision = revision,
                installedPocketPcSourceRevisionPinned = true,
            )
        assertNull(parsed)
    }

    private fun expectFailure(
        message: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected failure: $message")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(message))
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(message))
        }
    }

    private fun feed(runtime: String?): String =
        buildString {
            append("{")
            append("\"schemaVersion\":1,")
            append("\"channel\":\"development\",")
            append("\"published\": true,")
            append("\"versionCode\":220123,")
            append("\"versionName\":\"0.1.0-alpha22.home.123\",")
            append("\"sourceRevision\":\"")
            append(revision)
            append("\",")
            append("\"packageName\":\"dev.pocketpc.core\",")
            append("\"minApi\":26,")
            append("\"apkUrl\":\"https://updates.example/PocketPC.apk\",")
            append("\"apkSha256\":\"")
            append("1".repeat(64))
            append("\"")
            if (runtime != null) {
                append(",\"experimentalRuntime\":")
                append(runtime)
            }
            append("}")
        }

    private fun runtimeOffer(runtimeRevision: String): String =
        """{
            "kind":"wine",
            "experimental":true,
            "guestToolVersion":"11-v52-test",
            "pocketPcSourceRevision":"$runtimeRevision",
            "url":"https://updates.example/PocketPC-Wine-v52.zip",
            "sha256":"${"2".repeat(64)}",
            "bytes":123456,
            "wineVulkanAbi":52,
            "capabilities":["pocketpc.vulkan.continuous-present.v52"]
        }""".trimIndent()
}
