package dev.pocketpc.core.runtime

/** Fixed, non-interactive guest commands. A completed probe is not application compatibility. */
enum class GuestRuntimeProbe(val label: String, val description: String) {
    SHELL("Shell Linux", "Executa o shell, identifica a arquitetura e mostra a pasta do guest."),
    ROOTFS("Rootfs Linux", "Verifica diretórios essenciais, /proc, /dev, escrita em /tmp e identidade do guest sem alterar o sistema."),
    TOOLCHAIN("Box64 / Wine", "Consulta as ferramentas instaladas nos overlays confiáveis do PocketPC."),
    BOX64_SMOKE("Box64 x86-64", "Executa um ELF x86-64 mínimo e estático através do Box64; não testa Wine nem Roblox.");

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
                printf 'POCKETPC_ROOTFS_PROBE_V2\n'
                [ -d /bin ] || { printf 'path=/bin state=missing\n'; exit 5; }
                printf 'path=/bin state=present\n'
                [ -d /etc ] || { printf 'path=/etc state=missing\n'; exit 5; }
                printf 'path=/etc state=present\n'
                [ -d /usr ] || { printf 'path=/usr state=missing\n'; exit 5; }
                printf 'path=/usr state=present\n'
                [ -d /tmp ] || { printf 'path=/tmp state=missing\n'; exit 5; }
                printf 'path=/tmp state=present\n'
                [ -d /proc ] || { printf 'kernel_path=/proc state=missing\n'; exit 5; }
                printf 'kernel_path=/proc state=present\n'
                [ -d /dev ] || { printf 'kernel_path=/dev state=missing\n'; exit 5; }
                printf 'kernel_path=/dev state=present\n'
                rm -f /tmp/.pocketpc-runtime-probe
                if (umask 077 && : > /tmp/.pocketpc-runtime-probe && rm -f /tmp/.pocketpc-runtime-probe); then
                    printf 'tmp_write=ok\n'
                else
                    printf 'tmp_write=failed\nrootfs=incomplete\nprobe=failed\n'
                    exit 5
                fi
                printf 'uid='
                id -u 2>/dev/null || printf 'unknown\n'
                env | grep '^HOME=' || printf 'HOME=unset\n'
                printf 'rootfs=structurally_ready\nprobe=complete\n'
            """.trimIndent()
            TOOLCHAIN -> """
                printf 'POCKETPC_TOOLCHAIN_PROBE_V3\n'
                printf '\ncomponent=box64\n'
                if [ -x /opt/pocketpc/box64/bin/box64 ]; then
                    printf 'path=/opt/pocketpc/box64/bin/box64\n'
                    if /opt/pocketpc/box64/bin/box64 --version; then
                        printf 'version_exit=0\n'
                    else
                        printf 'version_exit=nonzero\n'
                    fi
                else
                    printf 'state=missing_or_not_executable\n'
                fi
                printf '\ncomponent=wine\n'
                if [ -x /opt/pocketpc/wine/bin/wine ]; then
                    printf 'path=/opt/pocketpc/wine/bin/wine\n'
                    if /opt/pocketpc/wine/bin/wine --version; then
                        printf 'version_exit=0\n'
                    else
                        printf 'version_exit=nonzero\n'
                    fi
                else
                    printf 'state=missing_or_not_executable\n'
                fi
                printf '\nprobe=complete\napplication_compatibility=not_tested\n'
            """.trimIndent()
            BOX64_SMOKE -> """
                printf 'POCKETPC_BOX64_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 7; }
                [ -x /opt/pocketpc/box64/share/tests/box64-smoke-x86_64 ] || { printf 'smoke=missing\nprobe=failed\n'; exit 8; }
                if /opt/pocketpc/box64/bin/box64 /opt/pocketpc/box64/share/tests/box64-smoke-x86_64; then
                    printf 'box64_x86_64_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'box64_x86_64_smoke=failed\nprobe=failed\n'
                exit 9
            """.trimIndent()
        }
}
