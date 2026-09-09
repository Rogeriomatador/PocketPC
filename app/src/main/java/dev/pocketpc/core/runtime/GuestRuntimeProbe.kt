package dev.pocketpc.core.runtime

/** Fixed, non-interactive guest commands. A completed probe is not application compatibility. */
enum class GuestRuntimeProbe(val label: String, val description: String) {
    SHELL("Shell Linux", "Executa o shell, identifica a arquitetura e mostra a pasta do guest."),
    ROOTFS("Rootfs Linux", "Verifica diretórios essenciais, /proc, /dev, escrita em /tmp e identidade do guest sem alterar o sistema."),
    TOOLCHAIN("Box64 / Wine", "Consulta as ferramentas instaladas nos overlays confiáveis do PocketPC."),
    BOX64_SMOKE("Box64 x86-64", "Executa um ELF x86-64 mínimo e estático através do Box64; não testa Wine nem Roblox."),
    DISPLAY_BRIDGE_SMOKE("Bridge x86-64 ↔ Android", "Executa um cliente x86-64 pelo Box64 e exige handshake autenticado com o broker ARM64 do PocketPC."),
    WINE_SMOKE("Wine Win64", "Executa um PE64 mínimo pelo Wine através do Box64, usando um prefixo isolado; não testa gráficos nem Roblox."),
    WINE_POCKETPC_WINDOW_SMOKE("Wine PocketPC window", "Carrega winepocketpc.drv, pinta uma janela GDI no framebuffer compartilhado e valida pointer/teclado de volta ao Win32."),
    D3D11_SMOKE("D3D11 → Vulkan", "Força DXVK nativo e cria um dispositivo D3D11; sucesso comprova a ponte gráfica básica, não Roblox."),
    D3D11_PRESENT_SMOKE("D3D11 Present", "Cria janela, swapchain e chama Present(); sucesso comprova apresentação básica via DXVK."),
    WINDOWS_PROCESS_SMOKE("Processos / IPC", "Cria um processo Win64 filho e confirma IPC por pipe anônimo no Wine."),
    WINSOCK_SMOKE("Winsock", "Valida WSAStartup, socket e resolução de localhost no Wine; não prova internet externa."),
    WINMM_AUDIO_API_SMOKE("WinMM áudio", "Consulta a API WinMM e enumera saídas quando existirem; não prova reprodução de áudio."),
    RAW_INPUT_API_SMOKE("Raw Input", "Consulta a API Raw Input e enumera dispositivos; não prova entrega de eventos.");

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
            DISPLAY_BRIDGE_SMOKE -> """
                printf 'POCKETPC_DISPLAY_BRIDGE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 41; }
                [ -x /opt/pocketpc/box64/share/tests/display-bridge-smoke-x86_64 ] || { printf 'bridge_client=missing\nprobe=failed\n'; exit 42; }
                [ -n "$POCKETPC_DISPLAY_SOCKET" ] || { printf 'bridge_socket=missing\nprobe=failed\n'; exit 43; }
                [ -n "$POCKETPC_DISPLAY_TOKEN" ] || { printf 'bridge_token=missing\nprobe=failed\n'; exit 44; }
                [ -n "$POCKETPC_DISPLAY_RUNTIME_SHA256" ] || { printf 'bridge_runtime_id=missing\nprobe=failed\n'; exit 45; }
                if /opt/pocketpc/box64/bin/box64 /opt/pocketpc/box64/share/tests/display-bridge-smoke-x86_64; then
                    printf 'display_bridge_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'display_bridge_smoke=failed\nprobe=failed\n'
                exit 46
            """.trimIndent()
            WINE_SMOKE -> """
                printf 'POCKETPC_WINE_WIN64_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 10; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 11; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-win64-smoke.exe ] || { printf 'win64_smoke=missing\nprobe=failed\n'; exit 12; }
                mkdir -p /home/pocket/windows-prefixes/smoke || exit 13
                if WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-win64-smoke.exe; then
                    printf 'wine_win64_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'wine_win64_smoke=failed\nprobe=failed\n'
                exit 14
            """.trimIndent()
            WINE_POCKETPC_WINDOW_SMOKE -> """
                printf 'POCKETPC_WINE_DRIVER_WINDOW_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 73; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 74; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-window-smoke.exe ] || { printf 'window_smoke=missing\nprobe=failed\n'; exit 75; }
                [ -n "$POCKETPC_DISPLAY_SOCKET" ] || { printf 'bridge_socket=missing\nprobe=failed\n'; exit 76; }
                [ -n "$POCKETPC_DISPLAY_TOKEN" ] || { printf 'bridge_token=missing\nprobe=failed\n'; exit 77; }
                [ -n "$POCKETPC_DISPLAY_RUNTIME_SHA256" ] || { printf 'bridge_runtime_id=missing\nprobe=failed\n'; exit 78; }
                mkdir -p /home/pocket/windows-prefixes/smoke || exit 79
                if ! WINEDEBUG=-all WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine reg.exe add 'HKCU\\Software\\Wine\\Drivers' /v Graphics /t REG_SZ /d pocketpc /f >/dev/null; then
                    printf 'wine_graphics_driver_config=failed\nprobe=failed\n'
                    exit 80
                fi
                printf 'wine_graphics_driver_config=pocketpc\n'
                if WINEDEBUG=-all WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-window-smoke.exe; then
                    printf 'wine_pocketpc_driver_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'wine_pocketpc_driver_smoke=failed\nprobe=failed\n'
                exit 81
            """.trimIndent()
            D3D11_SMOKE -> """
                printf 'POCKETPC_D3D11_VULKAN_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 15; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 16; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-d3d11-smoke.exe ] || { printf 'd3d11_smoke=missing\nprobe=failed\n'; exit 17; }
                [ -f /home/pocket/.pocketpc/windows-layers/dxvk/3.0.2/DEPLOYMENT.tsv ] || { printf 'dxvk=not_deployed\nprobe=failed\n'; exit 18; }
                if WINEDLLOVERRIDES='d3d11=n;dxgi=n' WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-d3d11-smoke.exe; then
                    printf 'd3d11_dxvk_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'd3d11_dxvk_smoke=failed\nprobe=failed\n'
                exit 19
            """.trimIndent()
            D3D11_PRESENT_SMOKE -> """
                printf 'POCKETPC_D3D11_PRESENT_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 36; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 37; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-d3d11-present-smoke.exe ] || { printf 'present_smoke=missing\nprobe=failed\n'; exit 38; }
                [ -f /home/pocket/.pocketpc/windows-layers/dxvk/3.0.2/DEPLOYMENT.tsv ] || { printf 'dxvk=not_deployed\nprobe=failed\n'; exit 39; }
                if ! WINEDEBUG=-all WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine reg.exe add 'HKCU\\Software\\Wine\\Drivers' /v Graphics /t REG_SZ /d pocketpc /f >/dev/null; then
                    printf 'wine_graphics_driver_config=failed\nprobe=failed\n'
                    exit 40
                fi
                printf 'wine_graphics_driver_config=pocketpc\n'
                if WINEDLLOVERRIDES='d3d11=n;dxgi=n' WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-d3d11-present-smoke.exe; then
                    printf 'd3d11_present_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'd3d11_present_smoke=failed\nprobe=failed\n'
                exit 41
            """.trimIndent()
            WINDOWS_PROCESS_SMOKE -> """
                printf 'POCKETPC_WINDOWS_PROCESS_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 20; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 21; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-process-ipc-smoke.exe ] || { printf 'process_smoke=missing\nprobe=failed\n'; exit 22; }
                if WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-process-ipc-smoke.exe; then
                    printf 'windows_process_ipc_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'windows_process_ipc_smoke=failed\nprobe=failed\n'
                exit 23
            """.trimIndent()
            WINSOCK_SMOKE -> """
                printf 'POCKETPC_WINSOCK_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 24; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 25; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-winsock-smoke.exe ] || { printf 'winsock_smoke=missing\nprobe=failed\n'; exit 26; }
                if WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-winsock-smoke.exe; then
                    printf 'winsock_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'winsock_smoke=failed\nprobe=failed\n'
                exit 27
            """.trimIndent()
            WINMM_AUDIO_API_SMOKE -> """
                printf 'POCKETPC_WINMM_AUDIO_API_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 28; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 29; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-winmm-audio-api-smoke.exe ] || { printf 'audio_smoke=missing\nprobe=failed\n'; exit 30; }
                if WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-winmm-audio-api-smoke.exe; then
                    printf 'winmm_audio_api_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'winmm_audio_api_smoke=failed\nprobe=failed\n'
                exit 31
            """.trimIndent()
            RAW_INPUT_API_SMOKE -> """
                printf 'POCKETPC_RAW_INPUT_API_SMOKE_PROBE_V1\n'
                [ -x /opt/pocketpc/box64/bin/box64 ] || { printf 'box64=missing\nprobe=failed\n'; exit 32; }
                [ -x /opt/pocketpc/wine/bin/wine ] || { printf 'wine=missing\nprobe=failed\n'; exit 33; }
                [ -f /opt/pocketpc/wine/share/tests/pocketpc-raw-input-api-smoke.exe ] || { printf 'input_smoke=missing\nprobe=failed\n'; exit 34; }
                if WINEPREFIX=/home/pocket/windows-prefixes/smoke WINEARCH=win64 /opt/pocketpc/box64/bin/box64 /opt/pocketpc/wine/bin/wine /opt/pocketpc/wine/share/tests/pocketpc-raw-input-api-smoke.exe; then
                    printf 'raw_input_api_smoke=passed\nprobe=complete\n'
                    exit 0
                fi
                printf 'raw_input_api_smoke=failed\nprobe=failed\n'
                exit 35
            """.trimIndent()
        }
}
