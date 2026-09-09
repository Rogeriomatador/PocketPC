package dev.pocketpc.core.runtime

import java.io.Closeable
import java.io.File

data class RuntimeDisplaySurfaceState(
    val request:
        RuntimeBridgeSurfaceRequest,
    val framebuffer:
        RuntimeDisplaySharedFramebuffer,
    val lastFrameId: Long,
)

class RuntimeDisplaySurfaceRegistry(
    private val hostTempDirectory: File,
) : Closeable {
    private val surfaces =
        LinkedHashMap<
            Long,
            RuntimeDisplaySurfaceState
        >()

    private var nextSurfaceId = 1L

    init {
        require(
            SafeTreeOps.isPlainDirectory(
                hostTempDirectory.toPath(),
            ),
        ) {
            "DISPLAY_SURFACE_REGISTRY_TEMP_INVALID"
        }
    }

    @Synchronized
    fun snapshot():
        List<RuntimeDisplaySurfaceState> =
        surfaces.values.toList()

    @Synchronized
    fun allocate(
        request:
            RuntimeBridgeSurfaceRequest,
    ): Result<RuntimeBridgeSurfaceAvailable> =
        runCatching {
            require(
                request.windowId > 0L,
            ) {
                "DISPLAY_SURFACE_WINDOW_ID_INVALID"
            }
            require(
                request.generation > 0L,
            ) {
                "DISPLAY_SURFACE_GENERATION_INVALID"
            }
            require(
                request.pixelFormat ==
                    RuntimeDisplayBridgePayloadCodec
                        .PIXEL_FORMAT_BGRA8888 &&
                    request.flags == 0,
            ) {
                "DISPLAY_SURFACE_FORMAT_INVALID"
            }
            require(
                surfaces.size <
                    MAX_SURFACES ||
                    request.windowId in
                        surfaces,
            ) {
                "DISPLAY_SURFACE_LIMIT"
            }

            val previous =
                surfaces[
                    request.windowId
                ]
            if (previous != null) {
                require(
                    request.generation >
                        previous.request
                            .generation,
                ) {
                    "DISPLAY_SURFACE_GENERATION_STALE"
                }
            }

            val surfaceId =
                nextSurfaceId
            require(surfaceId > 0L) {
                "DISPLAY_SURFACE_ID_EXHAUSTED"
            }

            val created =
                RuntimeDisplaySharedFramebuffer
                    .create(
                        hostTempDirectory =
                            hostTempDirectory,
                        windowId =
                            request.windowId,
                        surfaceId =
                            surfaceId,
                        generation =
                            request.generation,
                        width =
                            request.width,
                        height =
                            request.height,
                    )

            try {
                nextSurfaceId =
                    Math.addExact(
                        surfaceId,
                        1L,
                    )
            } catch (
                error:
                    ArithmeticException
            ) {
                created.close()
                throw IllegalStateException(
                    "DISPLAY_SURFACE_ID_EXHAUSTED",
                    error,
                )
            }

            surfaces[
                request.windowId
            ] =
                RuntimeDisplaySurfaceState(
                    request = request,
                    framebuffer = created,
                    lastFrameId = 0L,
                )

            previous
                ?.framebuffer
                ?.close()

            created
                .descriptor
                .surface
        }

    @Synchronized
    fun acceptFrame(
        ready:
            RuntimeBridgeFrameReady,
    ): Result<RuntimeDisplaySharedFramebuffer> =
        runCatching {
            val state =
                surfaces[
                    ready.windowId
                ] ?: error(
                    "DISPLAY_SURFACE_WINDOW_MISSING",
                )
            val surface =
                state.framebuffer
                    .descriptor
                    .surface

            require(
                ready.surfaceId ==
                    surface.surfaceId &&
                    ready.generation ==
                    surface.generation,
            ) {
                "DISPLAY_SURFACE_IDENTITY_MISMATCH"
            }
            require(
                ready.frameId >
                    state.lastFrameId,
            ) {
                "DISPLAY_SURFACE_FRAME_STALE"
            }

            surfaces[
                ready.windowId
            ] =
                state.copy(
                    lastFrameId =
                        ready.frameId,
                )

            state.framebuffer
        }

    @Synchronized
    fun removeWindow(
        windowId: Long,
    ): Boolean {
        val removed =
            surfaces.remove(
                windowId,
            ) ?: return false
        removed.framebuffer.close()
        return true
    }

    @Synchronized
    override fun close() {
        surfaces.values.forEach {
            it.framebuffer.close()
        }
        surfaces.clear()
    }

    companion object {
        const val MAX_SURFACES = 64
    }
}
