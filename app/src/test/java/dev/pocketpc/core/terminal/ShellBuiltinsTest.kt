package dev.pocketpc.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellBuiltinsTest {
    @Test
    fun cdWithoutArgumentMapsToHome() {
        assertEquals("~", ShellBuiltins.tokenizeCd("cd"))
    }

    @Test
    fun cdTrimsAndUnquotesPath() {
        assertEquals("My Folder", ShellBuiltins.tokenizeCd("  cd \"My Folder\"  "))
    }

    @Test
    fun nonCdCommandIsNotIntercepted() {
        assertNull(ShellBuiltins.tokenizeCd("echo cd hello"))
    }
}
