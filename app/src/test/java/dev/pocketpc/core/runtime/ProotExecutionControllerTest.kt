package dev.pocketpc.core.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotExecutionControllerTest {
    @Test
    fun explicitApprovalIsRequiredBeforeProcessStart() =
        runBlocking {
            val plan =
                ProotInvocationPlan(
                    ready = false,
                    argv = listOf("never-started"),
                    environment = emptyMap(),
                    blockers =
                        listOf(
                            ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER
                        ),
                )

            val result =
                ProotExecutionController()
                    .executeOneShot(
                        plan = plan,
                        userApproved = false,
                    )

            assertEquals(
                ProotExecutionState.BLOCKED,
                result.state,
            )
            assertFalse(result.started)
            assertTrue(
                ProotExecutionController
                    .EXECUTION_APPROVAL_BLOCKER in
                    result.blockers
            )
        }

    @Test
    fun structuralBlockerCannotBeOverriddenByApproval() =
        runBlocking {
            val plan =
                ProotInvocationPlan(
                    ready = false,
                    argv = emptyList(),
                    environment = emptyMap(),
                    blockers =
                        listOf(
                            "SUBSTRATE_NOT_READY",
                            ProotExecutionController
                                .EXECUTION_APPROVAL_BLOCKER,
                        ),
                )

            val result =
                ProotExecutionController()
                    .executeOneShot(
                        plan = plan,
                        userApproved = true,
                    )

            assertEquals(
                ProotExecutionState.BLOCKED,
                result.state,
            )
            assertFalse(result.started)
            assertTrue(
                "SUBSTRATE_NOT_READY" in
                    result.blockers
            )
        }
}
