package dev.pocketpc.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProcTreeTest {
    private fun process(
        pid: Long,
        parentPid: Long,
        start: Long,
    ) =
        RuntimeProcProcess(
            pid = pid,
            parentPid = parentPid,
            startTimeTicks = start,
            name = "p$pid",
            residentMemoryBytes =
                pid * 1024L,
            threadCount = 1,
        )

    @Test
    fun familyRejectsPidReuseByStartTime() {
        val known =
            mapOf(
                100L to 10L,
                101L to 11L,
            )
        val processes =
            mapOf(
                100L to
                    process(
                        100L,
                        1L,
                        10L,
                    ),
                101L to
                    process(
                        101L,
                        1L,
                        99L,
                    ),
            )

        val family =
            RuntimeProcTree.family(
                known,
                processes,
            )

        assertEquals(
            listOf(100L),
            family.map {
                it.pid
            },
        )
        assertFalse(
            family.any {
                it.pid == 101L
            },
        )
    }

    @Test
    fun knownReparentedChildRemainsAndDiscoversGrandchild() {
        val known =
            mapOf(
                201L to 21L,
            )
        val processes =
            mapOf(
                201L to
                    process(
                        201L,
                        1L,
                        21L,
                    ),
                202L to
                    process(
                        202L,
                        201L,
                        22L,
                    ),
                203L to
                    process(
                        203L,
                        202L,
                        23L,
                    ),
            )

        val family =
            RuntimeProcTree.family(
                known,
                processes,
            )

        assertEquals(
            setOf(
                201L,
                202L,
                203L,
            ),
            family.map {
                it.pid
            }.toSet(),
        )
    }

    @Test
    fun depthsPlaceDeepestDescendantsBeforeRootForTermination() {
        val family =
            listOf(
                process(
                    300L,
                    1L,
                    30L,
                ),
                process(
                    301L,
                    300L,
                    31L,
                ),
                process(
                    302L,
                    301L,
                    32L,
                ),
            )
        val depths =
            RuntimeProcTree.depths(
                family,
            )

        assertEquals(
            0,
            depths[300L],
        )
        assertEquals(
            1,
            depths[301L],
        )
        assertEquals(
            2,
            depths[302L],
        )

        val terminationOrder =
            family.sortedByDescending {
                depths[it.pid] ?: 0
            }.map {
                it.pid
            }

        assertEquals(
            listOf(
                302L,
                301L,
                300L,
            ),
            terminationOrder,
        )
    }

    @Test
    fun unrelatedProcessesNeverJoinFamily() {
        val known =
            mapOf(
                400L to 40L,
            )
        val processes =
            mapOf(
                400L to
                    process(
                        400L,
                        1L,
                        40L,
                    ),
                401L to
                    process(
                        401L,
                        400L,
                        41L,
                    ),
                900L to
                    process(
                        900L,
                        1L,
                        90L,
                    ),
            )

        val family =
            RuntimeProcTree.family(
                known,
                processes,
            )

        assertTrue(
            family.any {
                it.pid == 401L
            },
        )
        assertFalse(
            family.any {
                it.pid == 900L
            },
        )
    }
}
