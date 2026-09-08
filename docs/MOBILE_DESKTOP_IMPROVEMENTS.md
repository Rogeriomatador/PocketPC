# Desktop adaptado ao celular — alpha22

Data: 2026-09-08. Alterações de interface: IMPLEMENTED. Experimentos abaixo: DESIGN.
Os resultados de compilação e testes devem ser consultados no PR que acompanha esta revisão.

## Problema confirmado

O log enviado do Windows identifica a revisão
`2d214e4176cca1ce5ede72a2453637a194dea259` e falha em
`:app:compileDebugKotlin`: referências não resolvidas a `MaterialTheme` e `Spacer`
em `DesktopAppearance.kt`. Os imports foram corrigidos. O preflight e as
políticas Python passarem não demonstrava que o Kotlin compilava. Nenhum APK ou
resultado físico é atribuído àquela execução incompleta.

## Mudanças implementadas

- Desktop e menu usam as dimensões medidas depois dos insets do sistema e do
  teclado. Janelas flutuantes são limitadas novamente quando esse espaço muda.
- Controles principais do navegador, cabeçalhos e barra de tarefas têm áreas
  de 48 dp. O navegador compacto deixa voltar, endereço e menu na mesma linha;
  avançar e recarregar permanecem acessíveis no menu. Abas têm identidade estável,
  lista lazy e rolagem para a seleção atual.
- O menu Iniciar tem pesquisa, nomes maiores, estado de busca vazia e rolagem
  do conteúdo inteiro quando há pouca altura. As superfícies claras/escuras têm
  cores explícitas; a janela ativa tem borda e sombra distintas.
- Voltar do Android percorre o histórico do navegador ativo; no desktop fecha
  seus menus ou minimiza a janela ativa. Enviar um endereço fecha o teclado.
  Redirecionamentos não substituem o texto enquanto o endereço está em edição.
- Progresso e callbacks de navegação são associados à aba de origem. Botões
  refletem o histórico disponível. Falhas de carregamento principais mostram
  uma opção de tentar novamente. WebViews acompanham pausa/retomada da atividade.
- Animação do wallpaper lê seu estado na fase de desenho. Não há animação do
  preset quando existe imagem personalizada ou uma janela compacta/maximizada
  cobrindo o desktop. Ainda não há medição de economia de energia ou latência.

Essas mudanças aprimoram o desktop do PocketPC. Não implementam execução de
aplicativos Windows, uma assistente de voz ou controle de outros aplicativos.

## Tecnologias e próximos experimentos

| Tecnologia documentada | Aplicação concreta no PocketPC | Evidência necessária |
| --- | --- | --- |
| [Layouts adaptativos](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes) e [alvos de toque](https://support.google.com/accessibility/android/answer/7101858?hl=en) | Espaço disponível e controles principais de 48 dp já aplicados; os limites 700/500 dp são escolhas do desktop PocketPC, não as classes padrão do Android. | Testes em retrato, paisagem, tela dividida, teclado aberto e fonte ampliada. |
| [Fases do Compose](https://developer.android.com/develop/ui/compose/performance/phases) | Leitura da animação transferida para desenho e pausa quando o desktop está coberto. | Comparar frames, recomposições e consumo com wallpaper animado; sem porcentagens estimadas. |
| [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview) e [Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview) | Gerar perfis reais das jornadas abrir Iniciar, Arquivos e navegador; medir inicialização e rolagem em uma variante apropriada. Não há perfil próprio gerado nesta mudança. | Perfil produzido a partir do APK testado, traces e medições repetidas no mesmo aparelho. Não copiar percentuais de outros apps. |
| [ADPF](https://developer.android.com/games/optimize/adpf) | Experimento futuro: adaptar efeitos e tarefas do próprio PocketPC ao estado térmico e ao orçamento de cada frame. Não altera limites térmicos nem governa jogos externos. | Confirmar APIs no aparelho, comparar estabilidade sustentada e consumo com fallback quando indisponíveis. |

A hipótese de pesquisa é combinar visibilidade das janelas, interação recente e
estado térmico para dar prioridade ao aplicativo ativo e reduzir trabalho
decorativo. É uma proposta de integração de técnicas existentes, não uma
descoberta inédita comprovada. Primeiro medir cada sinal isoladamente; depois
avaliar a combinação. Uma política mais complexa só vale a pena se vencer a
pausa simples já implementada sem prejudicar entrada ou continuidade da sessão.

Comunicação com armazenamento, aplicativos e displays deve continuar pela
arquitetura descrita em [Bridge Architecture Research](BRIDGE_ARCHITECTURE_RESEARCH.md).
Não é necessário adicionar um serviço privilegiado para melhorar o desktop.

## Roteiro de verificação no aparelho

1. Compilar com `PocketPC-Test-Windows.bat` na branch do PR, usando a assinatura
   existente. Confirmar o SHA no resultado antes de atribuir qualquer PASS.
2. Em retrato e paisagem, abrir Iniciar, pesquisar, abrir Arquivos e navegador.
   Com teclado aberto, conferir que o campo e o menu podem ser alcançados por
   rolagem e que os botões não ficam sob recortes/barras do Android.
3. Criar três abas, alternar rapidamente durante carregamento, fechar a ativa,
   voltar/avançar, enviar um endereço e testar uma página sem conexão.
   Conferir rótulos de acessibilidade e áreas de toque com TalkBack/Scanner.
4. Minimizar/restaurar, girar o celular e retornar ao app. Os metadados das abas
   são restauráveis; o histórico completo do WebView continua apenas em memória.
5. Em área ampla, arrastar/redimensionar janelas, mudar o tamanho disponível e
   confirmar cabeçalhos alcançáveis e conteúdo acima da barra de tarefas.
   Testar mouse, teclado físico e foco com Tab/Enter.
6. Repetir em temas claro/escuro, fonte ampliada e wallpaper personalizado.
   Comparar animação visível, coberta e retomada. Usar o
   [plano de benchmark](BENCHMARK_PLAN.md) antes de declarar ganho de desempenho.
