package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketGzipArchiveTest {
    @Test
    fun gzDropsOnlyTheGzipSuffix() {
        assertEquals("backup.tar", pocketGzipOutputName("backup.tar.gz"))
        assertEquals("dados.json", pocketGzipOutputName("dados.json.gz"))
    }

    @Test
    fun tgzBecomesTar() {
        assertEquals("linux.tar", pocketGzipOutputName("linux.tgz"))
    }

    @Test
    fun missingGzipSuffixGetsSafeOutputSuffix() {
        assertEquals("arquivo.bin.out", pocketGzipOutputName("arquivo.bin"))
    }

    @Test
    fun emptyLookingNameStillProducesSafeFileName() {
        assertEquals("arquivo", pocketGzipOutputName(".gz"))
    }
}
