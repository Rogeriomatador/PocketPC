package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class WindowsPrefixReadinessTest {
    @Test
    fun completeWinePrefixStructureIsReady() {
        val home =
            Files.createTempDirectory(
                "pocketpc-prefix-ready-",
            ).toFile()
        try {
            val plan =
                WindowsPrefixPlanner.plan(
                    home,
                    "smoke",
                )
            val layout =
                requireNotNull(plan.layout)

            layout.driveC.mkdirs()
            layout.dosDevices.mkdirs()
            layout.systemRegistry.writeText("system")
            layout.userRegistry.writeText("user")
            layout.userDefRegistry.writeText("userdef")

            val result =
                WindowsPrefixReadinessProbe
                    .assess(plan)

            assertTrue(
                result.blockers.joinToString(),
                result.ready,
            )
        } finally {
            SafeTreeOps.deleteNoFollow(home)
        }
    }

    @Test
    fun missingRegistryFailsClosed() {
        val home =
            Files.createTempDirectory(
                "pocketpc-prefix-blocked-",
            ).toFile()
        try {
            val plan =
                WindowsPrefixPlanner.plan(
                    home,
                    "smoke",
                )
            val layout =
                requireNotNull(plan.layout)
            layout.driveC.mkdirs()
            layout.dosDevices.mkdirs()

            val result =
                WindowsPrefixReadinessProbe
                    .assess(plan)

            assertFalse(result.ready)
            assertTrue(
                result.blockers.any {
                    it.startsWith(
                        "WINDOWS_PREFIX_REGISTRY_INVALID:",
                    )
                },
            )
        } finally {
            SafeTreeOps.deleteNoFollow(home)
        }
    }
}
