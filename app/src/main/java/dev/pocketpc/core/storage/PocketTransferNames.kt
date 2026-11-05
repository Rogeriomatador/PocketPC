package dev.pocketpc.core.storage

import java.util.Locale

/**
 * Windows-like collision policy without overwriting any existing SAF entry.
 * The destination provider may be case-sensitive: PocketPC is deliberately not.
 */
internal fun allocatePocketTransferName(
    requestedName: String,
    existingNames: Collection<String>,
): String {
    val requested = validateStorageName(requestedName)
    val existing = existingNames.map {
        it.lowercase(Locale.ROOT)
    }.toHashSet()
    if (requested.lowercase(Locale.ROOT) !in existing) {
        return requested
    }
    val dot = requested.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < requested.lastIndex
    val stem = if (hasExtension) requested.substring(0, dot) else requested
    val suffix = if (hasExtension) requested.substring(dot) else ""
    for (number in 2..9999) {
        val candidate = "$"+"stem ($"+"number)$"+"suffix"
        if (candidate.lowercase(Locale.ROOT) !in existing) {
            return candidate
        }
    }
    error("Muitos arquivos com nomes iguais no destino.")
}
