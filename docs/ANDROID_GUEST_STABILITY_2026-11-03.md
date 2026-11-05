# PocketPC — comunicação Android ↔ Windows e prevenção de travamentos

Data: 2026-11-03  
Branch: `improve/alpha22-desktop-continuity`  
Estado: **IMPLEMENTED IN SOURCE; CI e execução física ainda não comprovadas nesta revisão.**

## Problemas técnicos identificados

1. A interface Compose chamava `RuntimeDesktopBridge.sendPointer/sendKey/command` no thread principal. A cadeia terminava em `RuntimeDisplayBridgePeer.send...`, que escreve/flush no socket Unix local. Um convidado travado podia atrasar a thread principal e causar uma janela Android sem resposta.
2. O caminho de imagens GDI (BGRA) decodifica cada framebuffer inteiro para `IntArray` e publica o snapshot do compositor; a camada Compose também atualiza `Bitmap`. Um fluxo rápido de imagens grandes pode consumir CPU, largura de banda da memória e orçamento de renderização do Android.
3. O fechamento da sessão chamava `RuntimeDisplayHostProcessor.close()` (sincronizado) **antes** de fechar o socket. Se `processNext()` estivesse preso lendo o socket sob o mesmo lock, o encerramento podia esperar por tempo indeterminado.
4. As decisões automáticas de desempenho provenientes de telemetria de memória/temperatura já existiam, mas antes não controlavam a cadência de ACK da ponte BGRA de software.

## Alterações efetuadas

### 1) Comandos de usuário assíncronos e limitados

`RuntimeDisplayInputDispatcher` usa uma única coroutine `Dispatchers.IO` para transmitir mouse, teclado e comandos de janela. A entrada na fila é não bloqueante e tem limite **128 eventos**.

`RuntimeDisplayInputMailbox`:
- preserva **ordem de admissão** dos comandos enviados;
- combina movimentos adjacentes do mouse se tiverem a mesma janela, modificadores e botões;
- descarta **somente movimentos de ponteiro** em pressão de fila; cliques, tecla DOWN/UP e comandos de janela não são descartados silenciosamente;
- quando há comandos críticos demais e nenhum movimento descartável, **falha fechado** e solicita o encerramento do socket pelo IO, em vez de perder `KEY_UP`/soltar botão.

O sucesso de `sendPointer/sendKey` agora significa **aceito pela fila**, não confirmado como recebido/aplicado pelo Wine. Em falha de escrita, o socket é invalidado e a sessão precisa ser restabelecida. Isso deve ser verificado com cliques/multitoque/teclado físicos no Android.

ACKs de frame e alocação de surfaces **não** são transportados pela fila de input; preservam o protocolo original e seu sequencing no `RuntimeDisplayHostProcessor`.

### 2) Backpressure de imagens de software

`RuntimeDisplayFramePacer` é aplicado **somente** aos eventos `FRAME_READY` da ponte BGRA.

- Primeiro frame sem espera extra.
- FPS de software limitado a 60, ou menos quando o modo manual/automático recomendar.
- Orçamento adicional baseado em tamanho de framebuffer: aproximadamente 60 milhões de pixels copiados por segundo; mínimo de 5 FPS em superfícies extremas.
- A espera acontece **antes** de enviar `FRAME_PRESENTED`, sobre o canal com ACK existente, para não alimentar a produção ilimitada de frames em memória.
- O controle não reduz taxa de leitura de eventos de mouse/teclado, e não modifica o caminho Vulkan/WSI experimental.

Esses valores são conservadores para o caminho BGRA e poderão ser alterados com medições reais. São limites de **produção de frames**, não contadores de FPS físico ou garantia de ausência de travamentos.

### 3) Limite agregado de superfícies

O limite anterior de uma superfície BGRA (até 64 MiB) não impedia
que muitas janelas acumulassem vários gigabytes de arquivos de framebuffer.
`RuntimeDisplaySurfaceRegistry` agora impõe **96 MiB por sessão** para
as superfícies ativas, calculando largura × altura × 4 e validando
**antes** de criar um novo arquivo. Redimensionamentos consideram o
orçamento **após a substituição** da surface anterior; em caso de
recusa, a surface existente não é apagada.

O orçamento de framebuffer não é o limite total de RAM consumida, já que
os arrays de pixels, Bitmaps, Wine e driver possuem custos independentes.
Este limite é por sessão/peer, não é um orçamento global para todos os
processos do aparelho; múltiplas sessões ainda exigem medição física.

`RuntimeDisplaySurfaceRegistryTest` verifica limite, não vazamento
de arquivo e redimensionamento transacional.

### 4) Encerramento seguro

`RuntimeDisplaySessionController.close()` fecha o canal de input e o socket do peer **antes** de aguardar o encerramento dos recursos sincronizados. Assim, uma leitura bloqueante do socket pode ser interrompida, evitando o ciclo de espera no desligamento. Requer teste de concorrência real no dispositivo.

### 5) Atualização consistente do compositor

`RuntimeDisplaySessionController` agora sincroniza as operações de
`compositor.apply(...)`, `applyExternalVulkanFrame(...)`, a publicação do
snapshot e o `clear()` no encerramento usando o mesmo lock.

Sem essa exclusão mútua, a coroutine do socket Wine e a rota gráfica Vulkan
poderiam atualizar o modelo compartilhado de janelas simultaneamente.
O lock protege a **consistência do modelo**, não prova GPU, FPS ou execução de
janelas físicas. Testes com frames concorrentes e troca de sessão ainda são
necessários no aparelho.

## Testes adicionados

- `RuntimeDisplaySurfaceRegistryTest`: limite agregado de surfaces, recusa sem vazamento e substituição transacional.

- `RuntimeDisplayInputMailboxTest`: combinar movimentos, preservar clique e KEY_UP, não misturar janelas, descartar apenas movimento, encerrar em saturação.
- `RuntimeDisplayInputDispatcherTest`: simular uma escrita de socket bloqueada e verificar que a thread chamadora consegue enviar KEY_UP sem esperar pela conclusão da escrita; fila cheia de comandos essenciais dispara desligamento.
- `RuntimeDisplayFramePacerTest`: primeiro frame, limite 60, limite manual, redução proporcional a 1080p/4096², recuo do relógio monotônico.

## Gates físicos obrigatórios (não executados nesta implementação)

1. Instalar APK da **mesma revisão**, verificar identidade/hash, executar shell/rootfs, Box64 e Wine/Win64 sem confundir build com execução real.
2. Abrir janela Win64 GDI e testar entrada intensa: mouse e teclado simultâneos, pressionar e soltar botões, trocar foco, fechar janela.
3. Simular convidado sem leitura do socket: verificar UI Android responsiva e encerramento sem ANR.
4. Acompanhar `dumpsys meminfo`, `adb logcat`, memória livre, status térmico, latência e drops; comparar antes/depois com a mesma cena, resolução e aparelho.
5. Testar sessão real de 15+ minutos, background/foreground, troca de orientação e fechar/reabrir sessão.
6. Testar RTX/D3D11/WSI como gate *separado*. Uma janela GDI não comprova Roblox Studio.

## Limitações conhecidas

- A fila evita escrita de socket no thread principal **no caminho integrado**; outras rotas Android ainda precisam ser auditadas antes de prometer ausência de ANRs.
- `Bitmap.setPixels`/Compose ainda pode causar custo de UI; o limite de imagens reduz frequência, mas não elimina alocação, GC ou problemas de driver.
- Não existe garantia universal de desempenho: CPU, RAM, térmica, Android, restrições de fabricantes e tamanho dos programas variam.
- Os resultados dependem do protocolo real Wine ↔ PocketPC e não promovem gates de Roblox Studio, DXVK ou Vulkan.
- Não criar cliente de acesso remoto nem depender de outro computador para executar apps Windows.

## Próximas melhorias após medição

- Medir fila, latência p50/p95/p99, tempo máximo de frame, memória, drops e casos de saturação sem coletar dados sensíveis.
- Manter um indicador de sessão responsiva na UI e botão de parada que não bloqueie o thread principal.
- Se necessário, otimizar conversão BGRA→Bitmap fora da UI e os buffers de surface, com teste visual para tearing/frames inválidos.
- Ajustar política conforme perfis de aparelhos, respeitando o retorno de pressão térmica/memória do Android.
