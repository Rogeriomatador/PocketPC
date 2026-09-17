package dev.pocketpc.core.runtime

enum class RobloxWindowsChannel {
    CLASSIC_PLAYER,
    MICROSOFT_STORE,
    STUDIO,
    UNKNOWN,
}

enum class RobloxWindowsArtifactKind {
    PLAYER_EXECUTABLE,
    PLAYER_INSTALLER,
    STORE_PACKAGE,
    STUDIO_EXECUTABLE,
    UNKNOWN_ROBLOX,
    NOT_ROBLOX,
}

data class RobloxWindowsArtifact(
    val fileName: String,
    val channel: RobloxWindowsChannel,
    val kind: RobloxWindowsArtifactKind,
    val recognized: Boolean,
    val directlyLaunchableCandidate: Boolean,
    val installerCandidate: Boolean,
    val storePackageInstallSupported: Boolean,
    val detail: String,
)

object RobloxWindowsArtifactClassifier {
    private val storePackageExtensions =
        setOf(
            "msix",
            "msixbundle",
            "appx",
            "appxbundle",
        )

    fun classify(fileName: String): RobloxWindowsArtifact {
        val cleanName =
            fileName.trim()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .take(256)
        val lower = cleanName.lowercase()
        val extension =
            lower.substringAfterLast('.', "")

        return when {
            lower == "robloxplayerbeta.exe" ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.CLASSIC_PLAYER,
                    kind = RobloxWindowsArtifactKind.PLAYER_EXECUTABLE,
                    recognized = true,
                    directlyLaunchableCandidate = true,
                    installerCandidate = false,
                    storePackageInstallSupported = false,
                    detail =
                        "Executável clássico do Roblox Player reconhecido. " +
                            "Reconhecimento de nome não prova compatibilidade nem execução.",
                )

            lower == "robloxplayerinstaller.exe" ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.CLASSIC_PLAYER,
                    kind = RobloxWindowsArtifactKind.PLAYER_INSTALLER,
                    recognized = true,
                    directlyLaunchableCandidate = false,
                    installerCandidate = true,
                    storePackageInstallSupported = false,
                    detail =
                        "Bootstrapper clássico do Roblox Player reconhecido. " +
                            "A instalação permanece condicionada ao runtime e à validação específica.",
                )

            lower.contains("roblox") &&
                extension in storePackageExtensions ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.MICROSOFT_STORE,
                    kind = RobloxWindowsArtifactKind.STORE_PACKAGE,
                    recognized = true,
                    directlyLaunchableCandidate = false,
                    installerCandidate = false,
                    storePackageInstallSupported = false,
                    detail =
                        "Pacote de aplicativo Windows/Store relacionado ao Roblox reconhecido, " +
                            "mas o PocketPC ainda não implementa instalação MSIX/AppX.",
                )

            lower.startsWith("robloxstudio") &&
                extension == "exe" ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.STUDIO,
                    kind = RobloxWindowsArtifactKind.STUDIO_EXECUTABLE,
                    recognized = true,
                    directlyLaunchableCandidate = false,
                    installerCandidate = false,
                    storePackageInstallSupported = false,
                    detail =
                        "Roblox Studio foi identificado separadamente do Player; " +
                            "este fluxo não o trata como cliente de jogo.",
                )

            lower.contains("roblox") ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.UNKNOWN,
                    kind = RobloxWindowsArtifactKind.UNKNOWN_ROBLOX,
                    recognized = false,
                    directlyLaunchableCandidate = false,
                    installerCandidate = false,
                    storePackageInstallSupported = false,
                    detail =
                        "O nome menciona Roblox, mas não corresponde a um artefato suportado pelo roteador. " +
                            "O PocketPC mantém a execução bloqueada.",
                )

            else ->
                RobloxWindowsArtifact(
                    fileName = cleanName,
                    channel = RobloxWindowsChannel.UNKNOWN,
                    kind = RobloxWindowsArtifactKind.NOT_ROBLOX,
                    recognized = false,
                    directlyLaunchableCandidate = false,
                    installerCandidate = false,
                    storePackageInstallSupported = false,
                    detail = "Arquivo não identificado como artefato Roblox.",
                )
        }
    }
}
