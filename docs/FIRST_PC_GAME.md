# Primeiro jogo Windows: Pocket Mines

## O que foi construído

Pocket Mines é um jogo original de Campo Minado com interface Win32/GDI, compilado para PE32+ x86-64. Ele não é um APK, não transmite jogos de outro computador e não depende de Direct3D.

O GitHub Actions compila o EXE com MinGW-w64, verifica o subsistema de interface gráfica, a arquitetura e o hash SHA-256. O resultado fica disponível como artefato do fluxo "PocketPC First PC Game - Win64 GDI".

**Estado de execução Android:** NOT_EXECUTED. Um compilador ter gerado um EXE não significa que o PRoot/Box64/Wine já consiga apresentar a janela desse jogo no telefone.

## Como obter o jogo

1. Abra GitHub Actions no repositório PocketPC.
2. Selecione "PocketPC First PC Game - Win64 GDI".
3. Abra uma execução com resultado success na branch improve/alpha22-desktop-continuity.
4. Baixe o artefato "PocketPC-Pocket-Mines-Win64-GDI".
5. Confirme que ele contém pocket-mines-win64.exe, o arquivo SHA-256 e o JSON com evidências.
6. Coloque o EXE em P:\Games no PocketDrive.

Build local com MinGW-w64 instalado:

    python3 scripts/test-build-pocket-mines.py
    python3 scripts/build-pocket-mines.py --output-dir /tmp/pocket-mines
    cd /tmp/pocket-mines && sha256sum -c pocket-mines-win64.exe.sha256

## Roteiro de teste no celular

O app Alpha 21 mostrado nas capturas do celular não incorpora ainda o runtime experimental da Alpha 22. Primeiro é preciso um APK experimental compatível, devidamente instalado e testado.

1. Abra o EXE em Arquivos do PocketPC. Ele deve ser encaminhado para Runtimes, não para o instalador de APK do Android.
2. Selecione o perfil "Aplicativo clássico (GDI)"; para Pocket Mines esse perfil já é sugerido.
3. Prepare os pacotes PRoot, rootfs ARM64, Box64 e Wine mediante validação de origem e integridade.
4. Execute os probes reais de rootfs, Box64, Wine e display bridge em sequência.
5. Se os gates permitirem, autorize uma tentativa controlada e observe o resultado real no aparelho.
6. Verifique abertura, pixels, clique, mudança de modo, marcação, reinício e encerramento.

## Controles do Pocket Mines

- Toque/clique numa casa para abrir; o primeiro toque é seguro.
- Toque na faixa superior "TOUCH: OPEN/FLAG" para alternar abrir/marcar.
- Botão direito do mouse: marcar/desmarcar uma casa.
- Teclas F ou Espaço: alternar modo.
- Teclas R ou F2: reiniciar partida.
- Escape: fechar janela.

## Evidências diferentes

- PE64_BUILD_PASS: executável produzido e assinatura PE/Hash verificados no CI.
- BOX64_PASS: tradução x86-64 comprovada no telefone.
- WINE_PASS: smoke Windows executado no telefone.
- GDI_BRIDGE_PASS: janela e entrada Win32 confirmadas.
- GAMEPLAY_PASS: jogo visto e jogado dentro do PocketPC no telefone.

Nenhum resultado de CI deve virar GAMEPLAY_PASS. Processos iniciados ou janelas simuladas também não comprovam jogo funcional.
