# Fase 1 — Análise profunda do `runtime.zip`

**Data da análise:** 2026-10-07
**Fonte:** `https://github.com/deivid22srk/Gtavfiles/releases/download/Files/runtime.zip` (8.091.761 bytes)
**Status da fase:** CONCLUÍDA — com decisão GATE registrada na seção 9.

---

## 1. Inventário e hashes SHA256

| Arquivo | Tamanho (bytes) | SHA256 |
|---|---|---|
| LICENSE.txt | 25.066 | `886a0ead2d89030ee62dbff52b04e47ab91998341295bb9c56fb952b4e081c7a` |
| python.exe | 110.336 | `372c2eae555b344520bf147be0096e009069aeca4e7f78d6aecea6d53158056a` |
| python3.dll | 73.536 | `c57bc21e131857f26db78d1512fbfcf67cac3273ee8b1cd704ed5645ec250884` |
| python312._pth | 28 | `023dc5c098ea903bded3f2d0b126aa9513862b47d5c3977132555c5b2476fb71` |
| python312.dll | 6.988.752 | `c1ce6d603041759061139f482c4b90c4ac9db676e30abf52fda66a794aab1bd0` |
| python312.zip | 5.333.079 | `e67938cd5fa246d57319d2d47a773c4c765f50f54c682345daf702b4ae5e5158` |
| vcruntime140.dll | 126.656 | `d5e4d9a3e835fa679450145d6a7d94e36573a509317111904d9b3712c30d9066` |
| vcruntime140_1.dll | 50.320 | `1f2d41c4aa5db0bc33ebf7b66d72943a817d7ce6cbe880502a9403823633093f` |

Arquitetura: todos os PE são `x86-64` (Machine `0x8664`). Nenhum binário 32 bits.

---

## 2. python312._pth (conteúdo exato)

```
python312.zip
DLLs
.
..
```

### Interpretação do `sys.path` resultante

Um arquivo `._pth` **substitui completamente** a construção normal do `sys.path`
(desativa `site` por padrão e ignora `PYTHONPATH`), restringindo os caminhos à
lista acima, **resolvidos em relação ao diretório do executável**:

1. `python312.zip` — stdlib empacotada, carregada via `zipimport`;
2. `DLLs` — pasta **irmã** do runtime (ex.: `<app>\DLLs\`), onde ficariam os
   `.pyd` (extensões C) do interpretador;
3. `.` — o próprio diretório do runtime (`<app>\runtime\`);
4. `..` — o **diretório pai** (`<app>\`).

`import site` **não está ativo** (não há a linha `#import site` descomentada —
no arquivo oficial existe a linha comentada; aqui ela foi removida).

### Conclusão estrutural

Este `._pth` **não é o oficial** (o oficial 3.12.10 contém apenas
`python312.zip`, `.` e `#import site` comentado — 80 bytes). A entrada `..`
indica que o design original do aplicativo espera que **o script do servidor
esteja no diretório pai** do runtime, e que exista uma pasta `DLLs` irmã com os
módulos de extensão. Ou seja: `runtime.zip` era **uma peça** de um pacote maior,
do tipo:

```
<raiz do app>/
├── (script(s) do servidor .py)   ← carregados via ".."
├── DLLs/                          ← _socket.pyd, _ssl.pyd etc. (ausentes)
└── runtime/
    ├── python.exe
    ├── python312.dll
    └── ...
```

---

## 3. python312.zip — auditoria da stdlib

- **737 arquivos `.py`** extraídos, **196 módulos/pacotes de topo**.
- **Zero módulos customizados**: nenhum `__main__.py`, `sitecustomize.py`,
  `usercustomize.py`, nenhum `.pyc` suspeito, nenhum módulo fora da stdlib
  oficial de um CPython 3.12.
- **Zero dados ocultos**: o fim real do ZIP coincide com o tamanho do arquivo
  (0 bytes anexados após o central directory).
- Diferença para o embeddable oficial 3.12.10 (599 arquivos `.pyc`, 3,8 MB):
  este zip contém **código-fonte `.py`** (5,1 MB) — é por isso que é maior.

**Não há código de servidor dentro de `python312.zip`.**

---

## 4. Identificação da versão e origem dos binários

### 4.1 Versão

`python312.dll`, `python.exe` e `python3.dll` reportam **ProductVersion/FileVersion 3.12.14**.

### 4.2 Comparação com distribuições públicas

| Canal | Situação |
|---|---|
| python.org (FTP) | `3.12.14` existe **apenas como código-fonte** (`.tar.xz`/`.tgz`). Publicações de binários Windows do 3.12 terminaram no **3.12.10**. |
| NuGet (`python`) | Última 3.12.x publicada: **3.12.10**. |
| python-build-standalone | Distribui `Lib/` como pasta, **não** como `python312.zip`, e **não assina** os binários. |

Comparação byte a byte com o embeddable oficial 3.12.10: **todos os hashes
divergem** (esperado — versão diferente), mas:

- **Imports PE: idênticos** ao oficial (nenhuma DLL extra como `ws2_32` no
  `python.exe` além do padrão; nenhuma função importada adicional);
- **Seções PE: idênticas** ao oficial (incluindo a seção padrão `PyRuntime`);
- **Strings: limpas** — nenhuma URL, IP, porta, rota, `http.server`,
  `socketserver`, framework (`flask`/`fastapi`/`aiohttp`) ou referência a
  GTA/FiveM embutida. Hits iniciais eram falsos positivos
  (`RAGE` ⊂ "ave**rage**", `.bat` ⊂ "itertools.**bat**ched", `socketserver` na
  lista padrão de nomes de módulos usada em sugestões de import, `LAUNCHER` ⊂
  `__PYVENV_LAUNCHER__`, `mp.` ⊂ "ti**mestamp.**").

### 4.3 Assinaturas Authenticode

| Arquivo | Assinado por |
|---|---|
| python.exe / python312.dll / python3.dll | **"OpenAI OpCo, LLC"** (cadeia Microsoft Identity Verification Root CA 2020 / Microsoft ID Verified CS EOC CA 03) |
| vcruntime140.dll / vcruntime140_1.dll | **Microsoft** (Windows Third Party Component CA) — vcruntime 14.44.35211.0, normal |

**A assinatura NÃO é da Python Software Foundation** (binários oficiais do
python.org são assinados pela PSF). Combinado com a ausência de binários
Windows 3.12.11+ em qualquer canal oficial, a conclusão é:

> O núcleo Python do `runtime.zip` é um **build de terceiros do CPython 3.12.14
> x64**, compilado a partir do fonte oficial (que é público), reempacotado em
> formato embeddable e assinado com certificado próprio. Não há nenhuma
> distribuição pública conhecida que corresponda a esses arquivos.

### 4.4 O binário foi modificado para conter o servidor?

**Não há evidência de modificação funcional.** Imports, seções, recursos de
versão e strings batem com CPython stock. O que foi modificado é o
`python312._pth` (item 2) — configuração, não código.

---

## 5. LICENSE.txt

Texto padrão da licença PSF para Python (seção "HISTORY OF THE SOFTWARE",
histórico CWI/BeOpen/CNRI/SMC etc.), 25.066 bytes — a versão reduzida usada
pelo próprio CPython (o embeddable oficial traz 36.874 bytes com anexos
adicionais). Nada customizado.

---

## 6. Procura do código do servidor — cobertura completa

| Local verificado | Resultado |
|---|---|
| Conteúdo de `runtime.zip` (todos os 8 arquivos) | Sem scripts, sem `.pyd`, sem config |
| Stdlib `python312.zip` (737 arquivos) | 100% padrão |
| Binários (strings/imports/seções/versão) | Stock, sem payload embutido |
| Repo `deivid22srk/Gtavfiles` | 1 commit: `README.md` com `# Gtavfiles` (11 bytes) |
| Releases/assets de `Gtavfiles` | 1 release ("Files"), 1 asset: `runtime.zip` |
| Busca de código GitHub na conta (`http.server`, `ThreadingHTTPServer`, `server.py`, `python.exe`, `runtime.zip`) | **0 resultados** |
| Varredura das árvores dos repos candidatos (`GtaV`, `Theft4-Android`, `Winlator-Xmod/Material/componentes`, `GameFluxer`, `rdr-Launcher`) | Nenhum servidor Python HTTP; `Theft4-Android` (port de GTA IV/LibertyRecomp) contém apenas scripts de *build/auditoria* |

### Nota sobre integridade funcional do runtime

Como o pacote **não inclui nenhum `.pyd`**, este runtime, sozinho, não consegue
importar módulos de extensão básicos para um servidor HTTP (`import socket`
falharia sem um diretório `DLLs/` irmão). Isso reforça que o pacote original do
qual `runtime.zip` foi extraído continha mais componentes — incluindo
presumivelmente o script do servidor.

---

## 7. O que é possível afirmar SEM o script

| Item | Status |
|---|---|
| Porta/host | **Desconhecidos** (nenhuma porta no runtime; nada embutido) |
| Protocolo | Provável HTTP local (`http.server` é o padrão em launchers assim), mas **não confirmado** |
| Rotas/endpoints | **Desconhecidos** |
| Formato de leitura da pasta selecionada | **Desconhecido** |
| Dependências | Stdlib suficiente existe no zip (`http`, `socketserver`, `json`, `zipfile`), mas o uso real é desconhecido |
| CORS/MIME/range/concorrência | **Desconhecidos** |

Conforme a regra absoluta nº 0 do projeto e a Fase 1 §4 ("Se o script do
servidor não estiver disponível, PARE e me peça o arquivo"), **é proibido
inventar a lógica do servidor**. As seções seguintes desta análise ficam
condicionadas ao recebimento do script original.

---

## 8. Fase 2 — Considerações preliminares de execução no Android

(Pré-análise; a decisão final A vs B depende do script.)

- `python.exe`/`python312.dll` são **Windows x64** — não rodam em Android
  (nem via Winlator de forma confiável para um serviço local persistente).
- **Opção A (reimplementação nativa Kotlin)**: viável se o servidor for HTTP
  simples com stdlib (`http.server`); paridade garantida por testes
  comparativos byte a byte.
- **Opção B (CPython no Android via Chaquopy)**: mantém o script original sem
  alterações; custo: app ~15–25 MB maiores e dependência da Chaquopy
  (compatível com 3.12 e `targetSdk 35`); paridade trivial (é o mesmo
  interpretador).

Critério de decisão (a aplicar quando o script for fornecido): número de rotas,
uso de módulos nativos, streaming de arquivos, e exigência de paridade byte a
byte.

---

## 9. Resolução do GATE — o script foi encontrado (`gta.zip`)

Na sequência, o usuário forneceu o segundo asset da mesma release:
`https://github.com/deivid22srk/Gtavfiles/releases/download/Files/gta.zip` (5.117 bytes), contendo:

| Arquivo | Papel |
|---|---|
| `serve_local.py` | **O servidor original** (133 linhas, só stdlib) |
| `Launch-Local.cmd` | `runtime\python.exe serve_local.py --host 127.0.0.1 --port 8000 --open` |
| `Launch-LAN.cmd` | `runtime\python.exe serve_local.py --host 0.0.0.0 --port 8080` |
| `Setup-LAN-Firewall.cmd/.ps1` | Regra de firewall `PlayGTA5-Offline-LAN-8080` (TCP 8080, sub-rede local) |
| `README.md` | Documentação: espelho offline de **playgta5.com** (jogo WebGPU/WebAssembly), arquivado em 2026-10-06, build `8b0b5899ed` |

A estrutura completa do pacote original era, portanto:

```
<raiz do app>/
├── serve_local.py
├── Launch-Local.cmd / Launch-LAN.cmd / Setup-LAN-Firewall.*
├── mirror/playgta5.com/     ← o site arquivado (NÃO incluído em nenhum zip)
│   └── data/                ← binários lidos pelo POST /data/batch
└── runtime/                 ← o runtime.zip da Fase 1
```

O SHA256 do `serve_local.py` original é
`7af39ea4c1ec1f78737d660a302053fe6e89a51241bd0a2e80576a512e0a900a`
(copiado **sem alterações** para os recursos de teste do módulo `:core`).

---

## 10. Protocolo exato do servidor (serve_local.py 3.12.14)

### 10.1 Visão geral

- `ThreadingHTTPServer((host, port), Handler)` — thread por conexão (daemon),
  `allow_reuse_address=1` (SO_REUSEADDR), backlog padrão do socketserver = 5;
- `protocol_version = "HTTP/1.0"` — **sem keep-alive**; uma requisição por conexão;
- `SimpleHTTPRequestHandler` com `directory=str(ROOT)` onde
  `ROOT = <dir do script>/mirror/playgta5.com`;
- MIME customizado: `application/wasm` para `.wasm` e `text/javascript` para `.js`;
- Header `end_headers()` sobrescrito — **toda** resposta (incluindo erros) recebe:
  - `Cross-Origin-Opener-Policy: same-origin`
  - `Cross-Origin-Embedder-Policy: require-corp`
  - `Cross-Origin-Resource-Policy: same-origin`
  - `Accept-Ranges: bytes`
- Linhas de boot: `Local mirror: http://localhost:<porta>/` e
  `Listening on <host>:<porta>`.

### 10.2 GET/HEAD (send_head)

1. **Sem `Range`** → comportamento do `SimpleHTTPRequestHandler` 3.12:
   - diretório sem `/` final → `301` com `Location` (query preservada) e
     `Content-Length: 0`;
   - diretório com `/` → serve `index.html`/`index.htm` ou **listagem HTML**
     (template do CPython, ordenação case-insensitive, `/` para pastas, `@` para
     links simbólicos, `Content-type: text/html; charset=utf-8`);
   - `translate_path`: remove query/fragmento, `unquote` (percent-decode UTF-8,
     fallback `replace`), colapso tipo `posixpath.normpath` — `..` **não** escapa
     da raiz (componentes `.`/`..` são ignorados após normalização);
   - arquivo com `/` final → `404 "File not found"`;
   - `If-Modified-Since` (só sem `If-None-Match`): compara com o mtime (fuso UTC;
     `parsedate_to_datetime` aceita RFC1123/RFC850/asctime; fuso não-UTC → sem 304);
     igual ou mais novo → `304` (Server/Date + headers globais, sem corpo);
   - `200`: `Server: SimpleHTTP/0.6 Python/3.12.14`, `Date` (GMT), `Content-type`
     (casing do original!), `Content-Length`, `Last-Modified` + globais.
2. **Com `Range`** (override do mirror; **apenas arquivos** — diretório → `404`
   bare; arquivo inexistente → `404` bare):
   - regex completa `bytes=(\d*)-(\d*)`; inválida ou vazia → `send_error(416)`
     (página de erro padrão com `Connection: close`);
   - início: `int(m1)` ou, no sufixo, `max(0, size - int(m2))`;
     fim: `min(size-1, int(m2))` **apenas** na forma `N-M`; nas formas aberta/
     sufixo é `size-1`;
   - `start >= size` ou `end < start` → `416` **com** `Content-Range: bytes */size`
     e `Content-Length: 0` (sem página de erro);
   - válido → `206` com `Content-Type`, `Content-Range: bytes s-e/size`,
     `Content-Length: e-s+1` e corpo servido em chunks de 1 MiB.
   - Observação de paridade: sem `Last-Modified` no caminho de range e sem
     `If-Modified-Since` — é assim no original.

### 10.3 POST /data/batch (engine batch I/O)

- Caminho exato `/data/batch` (após remover query); outro caminho → `send_error(404)` bare;
- `Content-Length` (default `0`); **> 1 MiB → `413`** (página de erro padrão);
- corpo: JSON **array** de `[nome, start, end]`:
  - não-JSON → `400` com a mensagem do `JSONDecodeError`
    (ex.: `Expecting value: line 1 column 1 (char 0)`);
  - não-array ou > 1000 runs → `400 invalid batch`;
  - item não-iterável → `400 cannot unpack non-iterable <tipo> object`;
  - item com tamanho ≠ 3 → `400 (not enough|too many) values to unpack...`;
  - `nome` não-str → `400 unsupported operand type(s) for /: 'PosixPath' and '<tipo>'`;
  - caminho resolvido canonicamente e obrigado a ficar dentro de `ROOT/data`
    (absolute substitui o join; traversal → `400 invalid file/range`);
  - `start < 0` ou `end < start` → `400 invalid file/range`;
  - arquivo ausente → `400 [Errno 2] No such file or directory: '<caminho>'`
    (o `stat()` é do CPython; diretório → `[Errno 21] Is a directory`);
  - `n = max(0, min(end+1, size) - start)` por run; soma > **64 MiB** →
    `400 batch exceeds 64 MiB`;
  - resposta `200`: `Content-Type: application/octet-stream`,
    `Content-Length`, `X-Run-Lengths: n1,n2,...` (concatenação dos trechos);
  - query `?gz=1` **exato** (`parse_qs`; `gz=2`, `gz=` vazio, `gz=1&gz=1` → SEM
    compressão) → `gzip.compress(body, compresslevel=1)` +
    `Content-Encoding: gzip` (o tamanho comprimido e o mtime do gzip variam —
    ver docs/TESTES.md);
  - `Content-Length` não-numérico → `int()` lança fora do try → traceback e
    **conexão fechada sem resposta** (comportamento do original).

### 10.4 Erros de parse (BaseHTTPRequestHandler)

- `request_version` inicia como `HTTP/0.9`: erros ANTES do parse da versão
  (linha com 1 palavra, versão inválida, HTTP/2.x) respondem **apenas o corpo
  HTML**, sem status line/headers — particularidade real do http.server;
- linha > 65536 bytes → `414` (aí COM status line, pois `request_version = ''`);
- header line > 65536 bytes ou > 100 headers → `431` ("Line too long" /
  "Too many headers");
- método sem handler (ex.: PUT/DELETE) → `501 Unsupported method ('PUT')`
  (o método entra no status line);
- HTTP/0.9 explícito (`GET /path`) → corpo puro, sem headers;
- template de erro: `DEFAULT_ERROR_MESSAGE` do CPython, UTF-8,
  `Content-Type: text/html;charset=utf-8` (sem espaço, casing do original);
  `HEAD` não escreve corpo.

### 10.5 Portas e hosts

- Local (padrão): `127.0.0.1:8000` com `--open` (abre o navegador);
- LAN: `0.0.0.0:8080` + regra de firewall (não aplicável ao app Android, que
  serve localhost);
- porta custom via `--port`.

### 10.6 O que o servidor NÃO tem

Sem autenticação, sem CORS dinâmico (os headers de isolamento são fixos),
sem cache próprio (só `If-Modified-Since`), sem compressão de estáticos
(somente o `?gz=1` do batch), sem WebSocket/TCP cru, sem logging em arquivo
(apenas stderr).

---

## 11. Fase 2 — DECISÃO TÉCNICA: Opção A (reimplementação nativa em Kotlin)

**Escolha: Opção A** — reimplementação nativa fiel em **Kotlin puro**
(módulo `:core`, sem dependências Android), dentro de um Foreground Service.

Justificativa:

1. O protocolo é pequeno e determinístico (estáticos + ranges + 1 endpoint
   JSON) — cabe inteiro em ~700 linhas de Kotlin auditáveis;
2. Paridade **testável**: o `serve_local.py` original roda no CI (Ubuntu +
   Python 3.12) e as respostas são comparadas **byte a byte** contra a
   implementação Kotlin na mesma fixture (65 testes; ver docs/TESTES.md);
3. Opção B (Chaquopy/CPython no Android) exigiria reempacotar o runtime de
   terceiros (assinado por entidade não-oficial) dentro do APK, somaria
   ~20 MB, e ainda dependeria dos `.pyd` ausentes — o pacote original nem
   funcionaria sem a pasta `DLLs/` que não existe;
4. Sem JNI/NDK: 100% Kotlin ⇒ compatível com qualquer ABI
   (arm64-v8a, armeabi-v7a, x86_64) sem compilação nativa;
5. Fidelidade máxima ao original inclusive nos detalhes de configuração de
   socket (SO_REUSEADDR, backlog 5, thread por conexão daemon, HTTP/1.0).

Desvios conhecidos e conscientes (documentados também em docs/TESTES.md):
`Server: SimpleHTTP/0.6 Python/3.12.14` é replicado literalmente por paridade
de wire-format (header de compatibilidade, não de procedência); gzip do batch
tem bytes diferentes (zlib vs java.util.zip.Deflater) mas payload descomprimido
idêntico; nomes de arquivo com bytes UTF-8 inválidos respondem 404 no app
(no original, com `surrogateescape`, podem ser acessados).
