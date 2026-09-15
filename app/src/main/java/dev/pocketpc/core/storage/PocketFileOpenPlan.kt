package dev.pocketpc.core.storage

/**
 * User-facing open plan for a file inside the PocketPC desktop.
 *
 * This model deliberately separates ownership from readiness. A file may have
 * a clear PocketPC handler while that handler/runtime is still ROUTE_ONLY and
 * therefore must not be presented as working or physically validated.
 */
enum class PocketFileOpenCapability {
    WINDOWS_RUNTIME_REQUIRED,
    ANDROID_SYSTEM_ACTION_REQUIRED,
    INTERNAL_HANDLER_PENDING,
    UNSUPPORTED,
}

data class PocketFileOpenPlan(
    val fileName: String,
    val association: PocketFileAssociation,
    val capability: PocketFileOpenCapability,
    val title: String,
    val description: String,
    val canAttemptNow: Boolean,
    val leavesPocketPc: Boolean,
)

fun planPocketFileOpen(
    name: String,
    mimeType: String? = null,
): PocketFileOpenPlan {
    val association =
        resolvePocketFileAssociation(
            name = name,
            mimeType = mimeType,
        )

    return when (association.handler) {
        PocketFileHandler.WINDOWS_RUNTIME ->
            PocketFileOpenPlan(
                fileName = name,
                association = association,
                capability = PocketFileOpenCapability.WINDOWS_RUNTIME_REQUIRED,
                title = "Abrir com ${association.displayName}",
                description =
                    "O PocketPC reconheceu este arquivo como aplicativo de PC. " +
                        "A execução depende dos gates reais do runtime Windows.",
                canAttemptNow = true,
                leavesPocketPc = false,
            )

        PocketFileHandler.ANDROID_PACKAGE_INSTALLER ->
            PocketFileOpenPlan(
                fileName = name,
                association = association,
                capability = PocketFileOpenCapability.ANDROID_SYSTEM_ACTION_REQUIRED,
                title = "Instalar pacote Android",
                description =
                    "Este é um APK. A instalação é uma fronteira explícita do Android " +
                        "e pode exigir confirmação do sistema.",
                canAttemptNow = true,
                leavesPocketPc = true,
            )

        PocketFileHandler.NONE ->
            PocketFileOpenPlan(
                fileName = name,
                association = association,
                capability = PocketFileOpenCapability.UNSUPPORTED,
                title = "Nenhum aplicativo PocketPC associado",
                description =
                    "O arquivo continuará dentro do PocketPC. Nenhum aplicativo " +
                        "Android será aberto automaticamente.",
                canAttemptNow = false,
                leavesPocketPc = false,
            )

        else -> {
            val implemented =
                association.readiness ==
                    PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL

            PocketFileOpenPlan(
                fileName = name,
                association = association,
                capability = PocketFileOpenCapability.INTERNAL_HANDLER_PENDING,
                title = "Abrir com ${association.displayName}",
                description =
                    if (implemented) {
                        "Este tipo possui um handler interno implementado no PocketPC. " +
                            "A abertura permanece dentro do desktop."
                    } else {
                        "O PocketPC possui uma associação interna para este tipo, mas o " +
                            "handler ainda precisa de implementação/validação antes de ser " +
                            "marcado como funcional."
                    },
                canAttemptNow = implemented,
                leavesPocketPc = false,
            )
        }
    }
}

/**
 * Explicit user override for unknown textual/project formats.
 *
 * This does not alter the global association table and does not infer that the
 * bytes are text. The editor's own size/read-only safeguards still apply.
 */
fun planPocketFileOpenAsText(
    name: String,
): PocketFileOpenPlan {
    val association =
        PocketFileAssociation(
            handler = PocketFileHandler.TEXT_EDITOR,
            displayName = "Editor de Texto do PocketPC",
            route = PocketFileRoute.POCKET_INTERNAL_APP,
            readiness = PocketFileHandlerReadiness.IMPLEMENTED_INTERNAL,
        )

    return PocketFileOpenPlan(
        fileName = name,
        association = association,
        capability = PocketFileOpenCapability.INTERNAL_HANDLER_PENDING,
        title = "Abrir como texto",
        description =
            "Abertura manual solicitada dentro do PocketPC. " +
                "O tipo original do arquivo não foi alterado.",
        canAttemptNow = true,
        leavesPocketPc = false,
    )
}
