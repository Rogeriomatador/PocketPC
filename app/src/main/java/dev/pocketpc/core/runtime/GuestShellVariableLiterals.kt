package dev.pocketpc.core.runtime

// Literal guest-shell variable names used inside Kotlin raw strings.
// Their values deliberately preserve the '$' for expansion by the guest shell.
internal const val POCKETPC_DISPLAY_SOCKET = "\$POCKETPC_DISPLAY_SOCKET"
internal const val POCKETPC_DISPLAY_TOKEN = "\$POCKETPC_DISPLAY_TOKEN"
internal const val POCKETPC_DISPLAY_RUNTIME_SHA256 = "\$POCKETPC_DISPLAY_RUNTIME_SHA256"
