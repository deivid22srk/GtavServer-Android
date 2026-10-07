# GtavServer-Android

App Android que **substitui o pacote Windows original** (`runtime.zip` +
`serve_local.py`) do espelho offline de playgta5.com: um servidor HTTP local
real, rodando no próprio celular, que serve a pasta que você escolher com
**paridade byte a byte** com o servidor Python original — provada por testes
automatizados que executam o `serve_local.py` original e o servidor Kotlin
sobre a mesma árvore de arquivos e comparam as respostas brutas.

- **Reimplementação nativa fiel (Opção A)** — Kotlin 100% (sem JNI/NDK,
  compatível com arm64-v8a, armeabi-v7a e x86_64);
- **Zero código simulado/mock** — o servidor realmente escuta em
  `127.0.0.1:8000`, abre sockets reais e responde como o original;
- **Seleção de pasta SEM SAF** — navegador de arquivos próprio
  (`java.io.File` + `MANAGE_EXTERNAL_STORAGE` no Android 11+,
  `READ_EXTERNAL_STORAGE` no Android ≤ 10), começando em `/storage/emulated/0`
  e cobrindo volumes externos (`/storage/XXXX-XXXX`);
- **Foreground Service real** (`specialUse`) com notificação persistente +
  ação "Parar", `PARTIAL_WAKE_LOCK`, `START_STICKY` e pedido de ignorar
  otimização de bateria — o servidor sobrevive ao app em segundo plano e à
  morte do processo;
- UI **Jetpack Compose / Material You** (cores dinâmicas no Android 12+,
  tema escuro) com logs de requisições em tempo real, copiar URL e abrir no
  navegador;
- **Jogo dentro do app**: WebView horizontal em **tela cheia imersiva**
  (landscape, barras do sistema escondidas), com configurações adequadas para
  wasm (JS, DOM storage, autoplay de mídia, prioridade máxima do renderer);
- **Captura de logs opcional** (o usuário liga/desliga): grava o log do
  **servidor** (requisições reais) + o log da **WebView** (console do
  jogo/wasm, erros HTTP, recursos, crash do renderer) em um anel de 6.000
  linhas e **exporta** um `.txt` via seletor do sistema — feito para
  diagnosticar o travamento em 66% ("Loading audio metadata").

## Como usar

1. Instale o APK (CI → Artifacts, ou uma Release com tag `v*`);
2. Conceda o acesso a arquivos (tela "Todos os arquivos" no Android 11+);
3. Escolha a pasta do espelho (a que contém `index.html` do site arquivado —
   no pacote original era `mirror/playgta5.com`);
4. Toque em **Iniciar servidor**; abra `http://127.0.0.1:8000/` no navegador
   (ou use o botão "Testar no navegador");
5. Ou toque em **Abrir jogo (tela cheia, horizontal)** para jogar dentro do
   app em WebView paisagem imersiva (voltar sai do jogo; os comandos são os
   do item 6 abaixo);
6. Controles do jogo (do README original): Espaço abre a seleção de mapas,
   `5` seleciona o mapa GTA V, `6` o mapa `env_test` (GTA VI), Enter entra no
   Story Mode. URLs diretas: `/?mode=sandbox` e `/?mode=sandbox&map=env_test`.

> O app serve apenas `127.0.0.1` (como o `Launch-Local.cmd`). A porta padrão é
> **8000** e pode ser trocada na tela principal.

## Diagnosticando o travamento em 66% ("Loading audio metadata")

Sintoma relatado: o jogo para em 66%, na etapa "Loading audio metadata", e o
log do wasm mostra a thread principal esperando em `Create Lock [mutex]`.
Como o servidor é idêntico byte a byte ao original (65 testes de paridade),
o travamento não parece estar no protocolo HTTP — as hipóteses principais,
em ordem de probabilidade, e como a captura ajuda a confirmar cada uma:

1. **Versão do WebView/Chromium** — `SharedArrayBuffer`/`crossOriginIsolated`
   (que o wasm pthread usa) exige Chromium ≥ 96 e os headers COOP/COEP (que o
   servidor já envia). Se o WebView do aparelho for velho, a espera por mutex
   trava. *Na captura:* veja a linha `webview:` do cabeçalho do export.
2. **Arquivo de áudio faltando ou corrompido na pasta espelhada** — se um
   fetch nunca responde/404, a promessa do loader nunca resolve e a main
   thread fica na trava. *Na captura:* linhas `ERRO http 404` (WEBVIEW) e a
   última requisição SERVIDOR antes do silêncio — a que não recebe resposta
   é a suspeita.
3. **Política de áudio do WebView** — o `AudioContext` só sai do estado
   `suspended` após um toque em alguns aparelhos. *Na captura:* avisos de
   autoplay/console de áudio. Solução paliativa: toque na tela assim que o
   jogo abrir.
4. **OOM do renderer** — wasm grande + vários workers podem estourar
   memória; o app detecta e registra `processo de renderização MORREU`.

Como capturar e enviar:

1. Na tela principal (ou no card do jogo), ligue o switch **Captura de logs**
   ANTES de reproduzir;
2. Abra o jogo e espere travar em 66%;
3. Volte, abra **Ver logs → Exportar** (ou **Exportar** direto no card) e
   envie o `.txt` gerado — o cabeçalho já traz app, Android, versão do
   WebView e contagens; o corpo cruza no tempo o console do jogo com as
   requisições reais do servidor.

## Estrutura

```
core/                     # motor do servidor — Kotlin puro, sem Android
  src/main/kotlin/.../core/
    MirrorServer.kt       # ThreadingHTTPServer equivalente (HTTP/1.0, backlog 5,
                          # SO_REUSEADDR, thread por conexão)
    ConnectionHandler.kt  # SimpleHTTPRequestHandler + overrides do serve_local.py
                          # (ranges, /data/batch, headers COOP/COEP/CORP)
    PythonHttp.kt         # réplicas: formatdate/parsedate, html.escape, quote/
                          # unquote, parse_qs, splitext, mensagens de OSError
    MimeTypes.kt          # tabelas de Lib/mimetypes.py 3.12.14 verbatim
    HttpStatus.kt         # HTTPStatus phrases/descriptions 3.12.14
    MiniJson.kt           # parser JSON com mensagens do json.JSONDecodeError
  src/test/               # 65 testes, incl. PARIDADE vs serve_local.py original
app/                      # UI + integração Android
  .../server/ServerService.kt  # Foreground Service (specialUse) + wake locks
  .../server/LogBus.kt         # captura opcional SERVIDOR/WEBVIEW/APP (anel 6k)
  .../server/LogExporter.kt    # export .txt com cabeçalho de ambiente + share
  .../ui/                 # Compose Material You (status, jogo em tela cheia,
                          # navegador de pastas, visualizador de logs)
  AndroidManifest.xml     # permissões, network_security_config, property FGS
docs/ANALISE.md           # engenharia reversa completa (runtime.zip + gta.zip)
docs/TESTES.md            # metodologia e matriz dos testes de paridade
.github/workflows/build.yml
```

## Paridade (resumo)

O protocolo replicado 1:1 do original (CPython 3.12.14):

- `ThreadingHTTPServer` em `127.0.0.1:8000`, HTTP/1.0, sem keep-alive;
- Headers de isolamento **em todas as respostas** (incl. erros):
  `COOP: same-origin`, `COEP: require-corp`, `CORP: same-origin`,
  `Accept-Ranges: bytes`;
- Estáticos com `index.html`/`index.htm`, listagem de diretórios idêntica,
  301 de diretório sem barra, 304 por `If-Modified-Since`,
  MIME pela tabela do CPython (`.wasm` → `application/wasm`);
- Byte ranges: `206`/`416` exatamente como o override do `serve_local.py`
  (sufixo `-N`, formas abertas, `Content-Range: bytes */size` nos 416 do caminho
  custom, página de erro nos 416 de regex inválida);
- `POST /data/batch`: JSON `[[nome, start, end], ...]`, máx. 1 MiB de corpo,
  1000 runs, 64 MiB de leitura, sandbox em `ROOT/data`, `X-Run-Lengths`,
  `?gz=1` (exato) com gzip nível 1;
- Página de erro padrão do CPython, mensagens idênticas (`Expecting value:
  line 1 column 1 (char 0)`, `[Errno 2] No such file or directory: '...'`,
  `Unsupported method ('PUT')`, ...), incluindo as particularidades
  (respostas sem status line para erros de parse, `Content-type` com casing
  original etc.).

Detalhes e desvios conscientes: `docs/ANALISE.md` §11 e `docs/TESTES.md` §5.

## Desenvolvimento

```bash
# Testes de paridade sem Android SDK:
./gradlew -c settings-local.gradle.kts :core:test

# Build completo:
./gradlew :core:test :app:assembleDebug :app:assembleRelease
```

Requisitos: JDK 17, Android SDK 35 (para o app), Python 3 (para os testes de
paridade). O APK release é assinado com a debug keystore para ser instalável
direto; troque o `signingConfig` se quiser publicar na Play.

## Créditos e contexto

- Espelho original: `playgta5.com` (arquivado em 2026-10-06, build
  `8b0b5899ed`); servidor: `serve_local.py` (stdlib pura);
- `runtime.zip` é um build de terceiros do CPython 3.12.14 x64 para Windows
  (análise completa em `docs/ANALISE.md`);
- Este projeto reimplementa a parte do servidor para Android; o conteúdo do
  jogo/espelho pertence aos respectivos donos e NÃO está incluído aqui.
