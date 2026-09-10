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

        else ->
            PocketFileOpenPlan(
                fileName = name,
                association = association,
                capability = PocketFileOpenCapability.INTERNAL_HANDLER_PENDING,
                title = "Abrir com ${association.displayName}",
                description =
                    "O PocketPC possui uma associação interna para este tipo, mas o " +
                        "handler ainda precisa de implementação/validação antes de ser " +
                        "marcado como funcional.",
                canAttemptNow = false,
                leavesPocketPc = false,
            )
    }
}
