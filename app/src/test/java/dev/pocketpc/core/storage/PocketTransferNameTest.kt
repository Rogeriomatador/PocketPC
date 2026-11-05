package dev.pocketpc.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketTransferNameTest {
    @Test
    fun keepsOriginalWhenFree() {
        assertEquals(
            "projeto.zip",
            allocatePocketTransferName("projeto.zip", listOf("outro.zip")),
        )
    }

    @Test
    fun picksNextAvailableNameCaseInsensitively() {
        assertEquals(
            "projeto (3).zip",
            allocatePocketTransferName(
                "projeto.zip",
                listOf("PROJETO.ZIP", "projeto (2).zip"),
            ),
        )
    }

    @Test
    fun handlesExtensionlessNamesAndDotfiles() {
        assertEquals(
            "README (2)",
            allocatePocketTransferName("README", listOf("readme")),
        )
        assertEquals(
            ".config (2)",
            allocatePocketTransferName(".config", listOf(".CONFIG")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathTraversal() {
        allocatePocketTransferName("../escape", emptyList())
    }
}
