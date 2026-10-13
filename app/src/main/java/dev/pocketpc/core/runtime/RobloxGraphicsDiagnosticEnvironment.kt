package dev.pocketpc.core.runtime

/**
 * Environment switches used only by the controlled Roblox graphics attempt.
 *
 * They intentionally do not leak into winecfg or ordinary Wine launches. The
 * v51 copy path is diagnostic/one-shot and must remain opt-in until physical
 * evidence proves it safe enough for a normal runtime path.
 */
object RobloxGraphicsDiagnosticEnvironment {
    const val GRAPHICS_SESSION_PROTOCOL =
        "POCKETPC_GRAPHICS_SESSION_PROTOCOL"
    const val PRESENT_CONTEXT_DIAGNOSTIC =
        "POCKETPC_VULKAN_PRESENT_CONTEXT_DIAGNOSTIC"
    const val PRESENT_COPY_DIAGNOSTIC =
        "POCKETPC_VULKAN_PRESENT_COPY_DIAGNOSTIC"

    fun applyTo(base: Map<String, String>): Map<String, String> =
        LinkedHashMap(base).apply {
            put(GRAPHICS_SESSION_PROTOCOL, "1")
            put(PRESENT_CONTEXT_DIAGNOSTIC, "1")
            put(PRESENT_COPY_DIAGNOSTIC, "1")
        }
}
