package dev.pocketpc.core.runtime

/** Fixed, non-interactive guest commands. A completed probe is not application compatibility. */
enum class GuestRuntimeProbe(val label: String, val description: String) {
    SHELL("Shell Linux", "Executa o shell, identifica a arquitetura e mostra a pasta do guest."),
    ROOTFS("Rootfs Linux", "Verifica diretórios essenciais, /proc, /dev, escrita em /tmp e identidade do guest sem alterar o sistema."),
    TOOLCHAIN("Box64 / Wine", "Procura Box64 e Wine dentro do Linux e consulta as versões encontradas.");

    val arguments: List<String>
        get() = listOf("-c", script)

    internal val script: String
        get() = when (this) {
            SHELL -> """
                printf 'POCKETPC_GUEST_PROBE_V1\n'
                printf 'shell=running\narchitecture='
                uname -m || exit 3
                printf 'directory='
                pwd || exit 4
                printf 'probe=complete\n'
            """.trimIndent()
            ROOTFS -> """
                printf 'POCKETPC_ROOTFS_PROBE_V1\n'
                failed=0
                for path in /bin /etc /usr /tmp; do
                    if [ -d "${'$'}path" ]; then
                        printf 'path=%s state=present\n' "${'$'}path"
                    else
                        printf 'path=%s state=missing\n' "${'$'}path"
                        failed=1
                    fi
                done
                for path in /proc /dev; do
                    if [ -d "${'$'}path" ]; then
                        printf 'kernel_path=%s state=present\n' "${'$'}path"
                    else
                        printf 'kernel_path=%s state=missing\n' "${'$'}path"
                        failed=1
                    fi
                done
                marker=/tmp/.pocketpc-runtime-probe-${'$'}${'$'}
                if (umask 077 && : > "${'$'}marker" && rm -f "${'$'}marker"); then
                    printf 'tmp_write=ok\n'
                else
                    printf 'tmp_write=failed\n'
                    failed=1
                fi
                printf 'uid='; id -u 2>/dev/null || printf 'unknown\n'
                printf 'home=%s\n' "${'$'}{HOME:-unset}"
                if [ "${'$'}failed" -eq 0 ]; then
                    printf 'rootfs=structurally_ready\nprobe=complete\n'
                    exit 0
                fi
                printf 'rootfs=incomplete\nprobe=failed\n'
                exit 5
            """.trimIndent()
            TOOLCHAIN -> """
                printf 'POCKETPC_TOOLCHAIN_PROBE_V1\n'
                for tool in box64 wine wine64; do
                    if command -v "${'$'}tool" >/dev/null 2>&1; then
                        printf '\ncomponent=%s\npath=' "${'$'}tool"
                        command -v "${'$'}tool"
                        "${'$'}tool" --version
                        result=${'$'}?
                        printf 'version_exit=%s\n' "${'$'}result"
                    else
                        printf '\ncomponent=%s\nstate=missing\n' "${'$'}tool"
                    fi
                done
                printf '\nprobe=complete\napplication_compatibility=not_tested\n'
            """.trimIndent()
        }
}
