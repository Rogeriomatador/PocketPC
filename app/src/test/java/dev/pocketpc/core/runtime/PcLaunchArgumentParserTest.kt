package dev.pocketpc.core.runtime

import org.junit.Assert.*
import org.junit.Test

class PcLaunchArgumentParserTest {
    @Test fun keepsWindowsPathsAndQuotedArgumentsIntact() {
        assertEquals(listOf("-windowed", "C:\\Program Files\\Game", "", "a b"),
            PcLaunchArgumentParser.parse("-windowed \"C:\\Program Files\\Game\" \"\" 'a b'").getOrThrow())
    }
    @Test fun operatorsArePlainArguments() {
        assertEquals(listOf(";", "$(touch", "marker)", "`id`", "&"),
            PcLaunchArgumentParser.parse("; $(touch marker) `id` &").getOrThrow())
    }
    @Test fun rejectsUnclosedQuotesControlsAndTooManyArguments() {
        assertTrue(PcLaunchArgumentParser.parse("\"unterminated").isFailure)
        assertTrue(PcLaunchArgumentParser.parse("x\ny").isFailure)
        assertTrue(PcLaunchArgumentParser.parse("x\u0000y").isFailure)
        assertTrue(PcLaunchArgumentParser.parse(List(65) { "a" }.joinToString(" ")).isFailure)
        assertTrue(PcLaunchArgumentParser.parse(List(64) { "a" }.joinToString(" ")).isSuccess)
    }
}
