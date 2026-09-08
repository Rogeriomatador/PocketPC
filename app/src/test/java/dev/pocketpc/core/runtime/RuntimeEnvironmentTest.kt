package dev.pocketpc.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeEnvironmentTest {
    @Test
    fun minimalEnvironmentIsValid() {
        assertTrue(RuntimeEnvironment.validate(RuntimeEnvironment.minimal()).isEmpty())
    }

    @Test
    fun prootEnvironmentPinsPackagedLoaderAlias() {
        val dir = File("/data/app/example/lib/arm64")
        val environment = RuntimeEnvironment.forProot(dir.path)

        assertTrue(
            environment["PROOT_LOADER"] ==
                File(dir, "libproot_loader.so").path
        )
        assertTrue(RuntimeEnvironment.validate(environment).isEmpty())
    }

    @Test
    fun rejectsNewlineInjection() {
        val errors = RuntimeEnvironment.validate(
            mapOf("HOME" to "/home/pocket\nEVIL=1")
        )
        assertFalse(errors.isEmpty())
    }

    @Test
    fun rejectsInvalidVariableName() {
        val errors = RuntimeEnvironment.validate(mapOf("LD-PRELOAD" to "x"))
        assertFalse(errors.isEmpty())
    }

    @Test
    fun prootHostTempDoesNotReplaceGuestTmpdir() {
        val env = RuntimeEnvironment.forProot("/native", "/data/user/0/dev.pocketpc.core/cache/runtime-tmp")
        assertTrue(env["PROOT_TMP_DIR"] == "/data/user/0/dev.pocketpc.core/cache/runtime-tmp")
        assertTrue(env["TMPDIR"] == "/tmp")
        assertTrue(RuntimeEnvironment.validate(env).isEmpty())
    }
}
