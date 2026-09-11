package dev.pocketpc.core.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the host-side lifetime of one authenticated guest graphics session.
 *
 * This class deliberately does not launch Wine by itself. The caller must add
 * [launchEnvironment] to the exact PRoot/Box64/Wine process environment before
 * starting the guest, then call [acceptAuthenticated] from an IO thread. This
 * keeps the security boundary explicit: no graphics resource may be offered
 * until the peer has completed PGH1 authentication (token + SO_PEERCRED PID).
 *
 * Session creation/authentication is transport evidence only. It is not proof
 * that PVI1/PVS1 were consumed by Wine/DXVK, that a Vulkan queue rendered, that
 * a frame was presented, or that Roblox ran.
 */
class GuestGraphicsSessionOrchestrator private constructor(
    private val session: GraphicsSeqpacketSessionHost.Session,
) : AutoCloseable {
    enum class State {
        CREATED,
        AUTHENTICATED,
        CLOSED,
    }

    data class Snapshot(
        val state: State,
        val authenticated: Boolean,
        val nextResourceSequence: Long,
        val blocker: String?,
    )

    companion object {
        const val BLOCKER_SESSION_CREATE_FAILED =
            "GUEST_GRAPHICS_SESSION_CREATE_FAILED"
        const val BLOCKER_NOT_AUTHENTICATED =
            "GUEST_GRAPHICS_SESSION_NOT_AUTHENTICATED"
        const val BLOCKER_ALREADY_AUTHENTICATED =
            "GUEST_GRAPHICS_SESSION_ALREADY_AUTHENTICATED"
        const val BLOCKER_CLOSED =
            "GUEST_GRAPHICS_SESSION_CLOSED"

        fun create(): GuestGraphicsSessionOrchestrator? =
            GraphicsSeqpacketSessionHost.createSession()
                ?.let(::GuestGraphicsSessionOrchestrator)
    }

    private val closed = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val resourceSequence = AtomicLong(0L)

    @Volatile
    private var acceptedConnection: GraphicsSeqpacketSessionHost.AcceptedConnection? = null

    @Volatile
    private var lastBlocker: String? = null

    /** Environment that must be injected into the exact guest process. */
    val launchEnvironment: Map<String, String>
        get() = session.launchEnvironment.variables.toMap()

    /**
     * Blocking authenticated accept. Must run on the runtime IO executor.
     * Only one peer is allowed for an orchestrator instance.
     */
    fun acceptAuthenticated(): Boolean {
        if (closed.get()) return fail(BLOCKER_CLOSED)
        if (!authenticated.compareAndSet(false, true)) {
            return fail(BLOCKER_ALREADY_AUTHENTICATED)
        }

        val connection = session.acceptAuthenticated()
        if (connection == null || !connection.valid) {
            authenticated.set(false)
            connection?.close()
            return fail(BLOCKER_NOT_AUTHENTICATED)
        }

        acceptedConnection = connection
        lastBlocker = null
        return true
    }

    /**
     * Returns a monotonically increasing host sequence only after PGH1 auth.
     * The sequence can bind later PVI1/PVS1 offers to this session without
     * pretending that those resources have already been imported by Vulkan.
     */
    fun nextAuthenticatedResourceSequence(): Long? {
        if (closed.get()) {
            fail(BLOCKER_CLOSED)
            return null
        }
        if (!authenticated.get() || acceptedConnection?.valid != true) {
            fail(BLOCKER_NOT_AUTHENTICATED)
            return null
        }

        while (true) {
            val previous = resourceSequence.get()
            if (previous == Long.MAX_VALUE) {
                fail(BLOCKER_CLOSED)
                close()
                return null
            }
            val next = previous + 1L
            if (resourceSequence.compareAndSet(previous, next)) {
                lastBlocker = null
                return next
            }
        }
    }

    fun snapshot(): Snapshot {
        val isClosed = closed.get()
        val isAuthenticated =
            !isClosed && authenticated.get() && acceptedConnection?.valid == true
        return Snapshot(
            state =
                when {
                    isClosed -> State.CLOSED
                    isAuthenticated -> State.AUTHENTICATED
                    else -> State.CREATED
                },
            authenticated = isAuthenticated,
            nextResourceSequence = resourceSequence.get() + 1L,
            blocker = lastBlocker,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acceptedConnection?.close()
        acceptedConnection = null
        authenticated.set(false)
        session.close()
    }

    private fun fail(blocker: String): Boolean {
        lastBlocker = blocker
        return false
    }
}
