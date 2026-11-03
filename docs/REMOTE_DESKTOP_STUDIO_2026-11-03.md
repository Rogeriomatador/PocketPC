# PC remoto / Roblox Studio — etapa inicial (2026-11-03)

**Estado desta alteração:** IMPLEMENTED no código; NOT_EXECUTED em Android real. A integração não demonstra Roblox Studio rodando no celular, nem compatibilidade de Wine, Box64, DXVK ou PRoot. Nenhum APK OTA foi publicado por esta alteração.

## Objetivo e limites

O desktop PocketPC inclui o aplicativo **PC remoto**. Ele abre uma sessão web HTML5 de um gateway de desktop remoto, como [noVNC](https://novnc.com/) ou [Apache Guacamole](https://guacamole.apache.org/), dentro de uma janela do próprio PocketPC. O **PC Windows remoto** é quem executa o Roblox Studio; o Android exibe os pixels e envia comandos do usuário através da página do gateway. Não implementa cliente VNC/RDP nativo, não fornece um servidor remoto e não executa o Studio pelo Wine.

Roblox Studio só é oficialmente disponibilizado para Windows e macOS: https://create.roblox.com/docs/studio/setup.

## Preparar o PC remoto antes de usar

1. No Windows compatível, instale e abra o Roblox Studio.
2. Configure um servidor de desktop remoto **com autenticação**. Para noVNC, um servidor VNC e um proxy WebSocket (por exemplo, websockify) são necessários. O guia oficial descreve a configuração: https://github.com/novnc/noVNC#quick-start. Como alternativa, configure Apache Guacamole para RDP/VNC.
3. Mantenha VNC/RDP e os serviços internos restritos a localhost ou a uma rede privada de confiança. **Não** encaminhe as portas VNC/RDP diretamente para a internet.
4. Disponibilize a página do gateway por **HTTPS com certificado válido**, de preferência numa VPN privada. Uma possibilidade é [Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve) com HTTPS ativado para sua tailnet, apontando para o gateway web local. Serve não é Funnel: evite publicar o acesso remotamente para a internet.
5. Conecte PC e celular à mesma rede privada/VPN autorizada. Confira primeiro se a URL HTTPS funciona no navegador Android e se os WebSockets do gateway se conectam ao computador.
6. Abra **PocketPC → PC remoto**, digite a URL da página do gateway sem parâmetros de consulta ou fragmentos, clique em **Abrir desktop remoto** e autentique-se **na própria página** do gateway. No Windows exibido, inicie o Roblox Studio.
7. Recomenda-se teclado e mouse Bluetooth ou USB. A interface móvel/touch depende do gateway; controle remoto não equivale à compatibilidade do Roblox Studio com Android.

## Restrições intencionais

- O PocketPC guarda somente o endereço-base HTTPS no SharedPreferences local; URLs contendo usuário/senha, parâmetros de consulta ou fragmentos são recusadas no campo de configuração, evitando persistir tokens por engano.
- Navegação interna permite HTTPS (por exemplo, redirecionamentos de login) ou HTTP exclusivamente para loopback local. HTTP de rede LAN ou internet, `file:`, `javascript:`, `intent:` e `data:` não são abertos pela navegação principal.
- A WebView tem JavaScript e DOM Storage habilitados porque clientes HTML5 como noVNC precisam desses recursos. Acesso a arquivos locais, conteúdo local e conteúdo misto (HTTPS/HTTP) estão bloqueados.
- Um erro de certificado HTTPS interrompe a conexão; não há bypass TLS. O PocketPC não gerencia as credenciais do gateway. A WebView/Android pode guardar cookies de sessões web de acordo com sua política.
- **Desconectar** fecha a visualização local, mas não garante encerrar a sessão autenticada no servidor remoto; finalize a sessão também no gateway quando apropriado.
- O caminho `http://localhost` corresponde ao próprio Android, não ao PC. Pode depender das regras de tráfego em claro do Android; para PC remoto use HTTPS.

## Validação necessária antes de considerar funcional

| Gate | Estado desta alteração | Como provar |
|---|---|---|
| Validação de URL (JVM) | IMPLEMENTED; resultado CI pendente | `RemoteDesktopEndpointTest` |
| Build Android e lint | NOT_EXECUTED nesta revisão no momento da implementação | CI Android da PR #6 |
| Janela abre/recarrega/fecha | NOT_EXECUTED | Instalar APK exato no telefone e gravar evidência |
| Gateway noVNC/Guacamole por HTTPS | NOT_EXECUTED | Acessar servidor real em HTTPS/WSS com certificado válido |
| Teclado/mouse/touch | NOT_EXECUTED | Testar dentro de uma sessão remota e registrar eventos |
| Roblox Studio remoto visível e utilizável | NOT_EXECUTED | Abrir Studio no Windows e interagir pela janela PocketPC |
| Roblox Studio *local* em ARM64/Wine | NOT_EXECUTED | Gate separado: PRoot → rootfs → Box64 → Wine → DXVK/Present |

## Código alterado

- `RemoteDesktopEndpoint.kt`: validação e normalização da URL;
- `RemoteDesktopApp.kt`: janela WebView e controles de sessão;
- `RemoteDesktopEndpointTest.kt`: testes JVM;
- `DesktopModels.kt`, `DesktopChrome.kt`, `PocketPcApp.kt`: integração no desktop.

Preservar a PR #6 em DRAFT e não fazer merge na `main` nem publicar OTA até validar build e aparelho. Os resultados anteriores do runtime Windows não são promovidos por este recurso remoto.
