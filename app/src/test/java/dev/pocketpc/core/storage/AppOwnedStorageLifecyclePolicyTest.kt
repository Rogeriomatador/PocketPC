package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppOwnedStorageLifecyclePolicyTest {
    @Test
    fun cleanupAllowlistContainsOnlyKnownDisposableLegacyRuntimePaths() {
        val actual =
            AppOwnedStorageMaintenancePolicy
                .disposableLegacyTargets
                .toSet()

        val expected =
            setOf(
                AppOwnedStorageMaintenanceTarget(
                    AppOwnedStorageRootKind.FILES,
                    "pocketpc-runtimes",
                ),
                AppOwnedStorageMaintenanceTarget(
                    AppOwnedStorageRootKind.FILES,
                    "runtime-tools",
                ),
                AppOwnedStorageMaintenanceTarget(
                    AppOwnedStorageRootKind.NO_BACKUP,
                    "pocketpc-runtimes",
                ),
                AppOwnedStorageMaintenanceTarget(
                    AppOwnedStorageRootKind.CACHE,
                    "pocketpc-runtimes",
                ),
                AppOwnedStorageMaintenanceTarget(
                    AppOwnedStorageRootKind.CACHE,
                    "runtime-tools",
                ),
            )

        assertEquals(expected, actual)
    }

    @Test
    fun authenticationProfilesAndTraversalAreNotCleanupTargets() {
        val protectedOrInvalidNames =
            listOf(
                "app_webview",
                "shared_prefs",
                "databases",
                "home",
                "pocketpc-home",
                ".wine",
                "wine-prefix",
                "browser-profile",
                "profiles",
                "userdata",
                "../app_webview",
                "foo/bar",
                "foo\\bar",
                ".",
                "..",
                "",
            )

        AppOwnedStorageRootKind.entries.forEach { root ->
            protectedOrInvalidNames.forEach { childName ->
                assertFalse(
                    "$root/$childName must never be startup/update cleanup",
                    AppOwnedStorageMaintenancePolicy.permits(
                        root,
                        childName,
                    ),
                )
            }
        }
    }
}
