# PocketPC — execução LOCAL de aplicativos Windows

Data: 2026-11-03

## Objetivo de produto

Qualquer usuário com **aparelho Android ARM64 compatível**, armazenamento e recursos suficientes deve poder instalar o PocketPC, abrir um ambiente de desktop, importar um aplicativo Windows x64 e **executá-lo no próprio aparelho, sem depender de outro PC, streaming ou nuvem**.

Este projeto busca uma experiência semelhante à de um PC Windows, mas não promete que o Android passe a inicializar o kernel Windows, nem instala Windows 10/11 nativamente. O desktop é uma aplicação Android; aplicativos Win32 são executados através de um **runtime de compatibilidade**.

**Roblox Studio é uma meta avançada, não um pré-requisito para considerar o runtime básico funcional.** A documentação oficial da Roblox disponibiliza o Studio para Windows/macOS, não Android. Mesmo que o sistema execute um EXE simples, isso não garante o Studio.

## Arquitetura pretendida e evidência exigida

```text
PocketPC (aplicativo Android, janela desktop real)
  ├── PocketDrive, EXE/DLLs/dados e entrada por mouse/teclado/toque
  └── PRoot (processos Linux isolados sem root; disponibilidade sujeita à política do Android)
       └── Rootfs Linux ARM64
            └── Box64 (tradução da CPU x86-64 para ARM64)
                 └── Wine x86_64 (APIs Win32)
                      ├── GDI (primeiras janelas de programas simples)
                      └── D3D11 -> DXVK/Vulkan -> apresentação na janela PocketPC
```

Os blocos não são substitutos de uma licença ou instalação real do Windows: Wine implementa interfaces de compatibilidade. A partir de cada etapa, testes no **aparelho**, com identificação do APK e versão do runtime, são necessários. Não tratar builds CI, modelos de interface ou prints de terceiros como prova de execução local.

## Sequência de gates (sem falsos positivos)

1. **Host Android ARM64:** APK instalado, biblioteca nativa presente, ABI e permissão de execução verificadas.
2. **PRoot → shell/rootfs:** iniciar `/bin/sh` dentro do rootfs ARM64 e registrar stdout, exit code, erros e identidade do pacote PRoot. A build de validação não deve promover automaticamente o candidato PRoot para produção.
3. **Box64:** executar um ELF de teste x86_64 dentro do guest e confirmar código de saída; não usar apenas `box64 --version` como prova de Win32.
4. **Wine x64:** executar um programa PE64 *headless* de smoke, incluindo `CreateProcess`/IPC onde aplicável.
5. **Janela GDI real:** abrir aplicativo Win64 simples e registrar janela/pixels retornados pela bridge, mouse, teclado e gerenciamento de processo.
6. **Gráficos 3D:** D3D11/DXVK/Vulkan WSI, `Present` contínuo, recursos AHardwareBuffer e interação sem travar; marcar separadamente CPU, GPU, renderização e input.
7. **Aplicativo real (como Roblox Studio):** baixar o instalador oficial, identificar PE/arquivos adjacentes, validar instalação ou uso, processo, login, interface de edição 3D e estabilidade, sempre respeitando as políticas do software. Não contornar verificações de integridade ou proteções anti-trapaça.

## Distinções importantes

- **Roblox Player ≠ Roblox Studio.** Os testes do Player não provam Studio, e o Studio não pode herdar o estado de compatibilidade do Player. A classificação do alvo na interface deve informar essa diferença.
- Um arquivo `.exe` reconhecido **não** é um aplicativo validado. `READY` do host, PRoot, Wine ou DXVK sozinho não confirma lançamento do aplicativo.
- Um celular com Android 32-bit, uma ABI incompatível, pouco armazenamento ou bloqueios específicos do fabricante pode não atender aos requisitos. `Qualquer celular` é o objetivo de experiência e distribuição, **não** uma promessa universal de hardware.
- Não adicionar acesso remoto como requisito de funcionamento do PocketPC.
- A `main` e o canal de atualização Home Test não recebem versões que não passaram pelos gates definidos. A PR de desenvolvimento deve permanecer draft até provas novas.

## Estado nesta revisão

- Interface Android desktop e roteamento para arquivo de PC: implementados previamente.
- Entrada de perfil gráfico GDI/DXVK e suporte a DLLs/dados ao importar programas: implementados previamente; execução no dispositivo não comprovada.
- Nome do Studio separado do Player na avaliação de compatibilidade: atualizado nesta revisão.
- Etapas PRoot, Box64, Wine, renderização D3D11 e Roblox Studio no aparelho: **NOT_EXECUTED** sem nova evidência física. Não promover nenhum gate.

## Primeiro teste prático no celular

Usar uma build de **validação** identificada por commit e checksum, com rootfs e PRoot atestados, executar o **probe Shell** presente em Runtimes. Registrar estado, saída e erro. Se passar, testar Box64, Wine e GDI **nessa ordem**. Depois tentar um EXE x64 simples importado da pasta do programa. Investigar o *primeiro* bloqueio concreto em vez de simular o Windows com interface web.

Documentação externa:

- https://create.roblox.com/docs/studio/setup
- https://github.com/ptitSeb/box64/blob/main/docs/WINE.md
