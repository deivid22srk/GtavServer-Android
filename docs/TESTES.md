# Fase 4 — Testes de paridade (docs/TESTES.md)

## 1. O que é testado e como

O módulo `:core` contém a implementação Kotlin do servidor. Os testes de
paridade (`core/src/test/kotlin/.../ParityTest.kt`) rodam **os dois servidores
simultaneamente na mesma fixture**:

1. O **`serve_local.py` original** (copiado sem alterações para
   `core/src/test/resources/original/serve_local.py`, sha256
   `7af39ea4c1ec1f78737d660a302053fe6e89a51241bd0a2e80576a512e0a900a`) é
   executado com o Python 3 do ambiente (`--host 127.0.0.1 --port 0`), exatamente
   como o `Launch-Local.cmd` faz;
2. O **servidor Kotlin** (`MirrorServer`) serve a MESMA árvore
   `mirror/playgta5.com` (fixture determinística criada em tempo de teste);
3. Cada requisição é enviada **raw** (socket, bytes) aos dois servidores e as
   respostas são comparadas **byte a byte**.

### Normalização aplicada (e por quê)

| Campo | Tratamento | Motivo |
|---|---|---|
| `Date:` | substituído por valor fixo | relógio difere entre as duas respostas |
| `Server:` (sufixo `Python/3.12.x`) | normalizado p/ `Python/3.12.X` | patch do Python varia por ambiente |
| Corpo comprimido do `?gz=1` | comparado **descomprimido** + tamanho normalizado | zlib (CPython) e `java.util.zip.Deflater` produzem bytes diferentes (o gzip do Python ainda embute o mtime atual — o original não é determinístico consigo mesmo nesse caso) |

Tudo o mais — status lines (incluindo o message customizado), ordem e casing
dos headers (`Content-type` vs `Content-Type` do original!), corpos HTML de
erro/listagem, `Content-Range`, `X-Run-Lengths`, payloads binários — é
comparado **exatamente igual**.

## 2. Matriz de casos (65 testes)

**Estáticos (14):** `/` com index.html · arquivo direto · diretório com index ·
301 de diretório sem `/` · listagem de diretório · 404 (arquivo, barra final em
arquivo) · HEAD · query ignorada · percent-encoding (`%65`) · espaço
(`%20`) · traversal bloqueado (`/../serve_local.py` → 404, não vaza) ·
`/sub/../index.html` colapsa como `posixpath.normpath` · traversal duplo.

**Ranges (12):** `0-99` · `100-` · `-50` (sufixo) · clamp de fim · `-0` →
416 com `Content-Range: bytes */size` · `50-49` → 416 com `Content-Range` ·
`bytes=abc` → 416 página de erro · unidade errada → 416 · arquivo inexistente →
404 bare · diretório → 404 bare · início além do tamanho → 416 · dois headers
`Range` (usa o primeiro) · HEAD com range.

**If-Modified-Since (5):** igual ao mtime → 304 · antigo → 200 · malformado →
200 · com `If-None-Match` presente → 200 · fuso não-UTC → sem 304.

**POST /data/batch (18):** concatenação válida + `X-Run-Lengths` · `?gz=1`
(headers + payload descomprimido) · `?gz=2` / `?gz=` / `?gz=1&gz=1` sem gzip ·
`[]` → 200 · `{}` → `400 invalid batch` · 1001 runs → 400 · não-JSON →
`400 Expecting value: line 1 column 1 (char 0)` · caminho errado → 404 bare ·
traversal → 400 · caminho absoluto → 400 · arquivo ausente →
`400 [Errno 2] No such file or directory: '<path>'` (caminho canônico idêntico,
mesma máquina) · start negativo · end<start · start além do tamanho (n=0) ·
end clampa no tamanho · unpack curto · unpack de int · nome int · sem
Content-Length.

**Protocolo (11):** PUT/DELETE → `501 Unsupported method ('PUT')` · versão
`HTTP/9.9` → 400 **sem status line** (corpo puro — comportamento real do
http.server) · sintaxe ruim → idem · `HTTP/2.0` → 505 idem · HTTP/0.9 GET →
corpo puro · linha vazia → conexão fecha sem resposta · `Connection: keep-alive`
→ fecha mesmo assim · headers de isolamento presentes em todas as respostas.

**Motor Kotlin local (3):** porta efêmera + linhas de boot · parada limpa ·
porta ocupada lança `BindException`.

## 3. Como rodar

```bash
# apenas JVM (sem Android SDK):
./gradlew -c settings-local.gradle.kts :core:test

# build completo (com Android SDK):
./gradlew :core:test :app:assembleDebug :app:assembleRelease
```

O CI (`build.yml`) roda ambos no Ubuntu (JDK 17 + Python 3.12 + Android SDK)
a cada push/PR.

## 4. Resultados

- **Local (sandbox, Python 3.12.14 + JDK 21):** 65/65 verdes.
- **CI (GitHub Actions, Ubuntu + JDK 17 + Python 3.12.x):** ver os runs em
  `https://github.com/deivid22srk/GtavServer-Android/actions`.

## 5. Desvios conscientes (honestos)

1. **`Server: SimpleHTTP/0.6 Python/3.12.14` replicado literalmente** — decisão
   de wire-format por paridade com o original; o servidor NÃO é Python.
2. **Gzip do `/data/batch?gz=1`:** bytes comprimidos e `Content-Length`
   diferem (zlib vs Deflater); payload descomprimido é idêntico e o header
   `Content-Encoding: gzip` é igual. O original também não é determinístico
   aqui (mtime no header gzip).
3. **Nomes de arquivo com bytes UTF-8 inválidos:** o original os acessa via
   `surrogatepass/surrogateescape`; a implementação Kotlin responde 404
   (arquivos do jogo são ASCII — sem impacto prático).
4. **`Last-Modified` com sub-segundo:** diferença de no máximo 1 segundo na
   comparação do `If-Modified-Since` para mtimes com fração de segundo
   (filesystems com granularidade de ns); fixture e arquivos reais usam
   segundos inteiros.
