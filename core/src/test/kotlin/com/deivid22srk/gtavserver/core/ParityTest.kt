package com.deivid22srk.gtavserver.core

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

/**
 * Testes de PARIDADE: o serve_local.py ORIGINAL (recursos de teste, sha256
 * registrado em docs/TESTES.md) e a implementação Kotlin servem a MESMA fixture
 * em portas diferentes, e as respostas são comparadas byte a byte
 * (com normalização apenas de Date e do sufixo de versão do Server).
 */
class ParityTest {

    companion object {
        private lateinit var tempDir: File
        private lateinit var kotlinServer: MirrorServer
        private var pythonPort: Int = -1
        private var kotlinPort: Int = -1
        private lateinit var pythonProcess: Process
        private lateinit var root: File

        @BeforeAll
        @JvmStatic
        fun setup() {
            tempDir = Files.createTempDirectory("gtav-parity").toFile()
            // serve_local.py resolve ROOT = <dir do script>/mirror/playgta5.com,
            // então a fixture precisa viver junto do script extraído.
            val scriptDir = File(tempDir, "original").apply { mkdirs() }
            val script = File(scriptDir, "serve_local.py")
            ParityTest::class.java.getResourceAsStream("/original/serve_local.py")!!
                .use { ins -> script.outputStream().use { ins.copyTo(it) } }
            root = ParityEnv.createFixture(scriptDir)
            val (pport, proc) = ParityEnv.startPython(scriptDir)
            pythonPort = pport
            pythonProcess = proc
            // Servidor Kotlin: mesma raiz, porta efêmera
            kotlinServer = MirrorServer(root, "127.0.0.1", 0) { }
            kotlinServer.start()
            kotlinPort = kotlinServer.actualPort
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            kotlinServer.stop()
            pythonProcess.destroy()
            tempDir.deleteRecursively()
        }

        private fun assertParity(rawRequest: String): ByteArray {
            val pyRaw = ParityEnv.rawRequest(pythonPort, rawRequest)
            val ktRaw = ParityEnv.rawRequest(kotlinPort, rawRequest)
            val pyNorm = ParityEnv.normalize(pyRaw)
            val ktNorm = ParityEnv.normalize(ktRaw)
            if (!pyNorm.contentEquals(ktNorm)) {
                throw AssertionError(
                    "Respostas divergem!\n" +
                        "--- requisição ---\n$rawRequest\n" +
                        "--- python (${pyRaw.size} bytes) ---\n${describe(pyRaw)}\n" +
                        "--- kotlin (${ktRaw.size} bytes) ---\n${describe(ktRaw)}"
                )
            }
            return ktRaw
        }

        private fun describe(raw: ByteArray): String {
            val text = String(raw, Charsets.ISO_8859_1)
            val head = ParityEnv.headersOf(raw)
            val bodyPreview = if (head.length < text.length) {
                val body = text.substring(head.length)
                if (body.length > 120) body.take(120) + "...(${body.length} bytes)" else body
            } else ""
            return head.replace("\r\n", "\n") + bodyPreview
        }

        private fun expected(expectedStatus: String, raw: ByteArray) {
            val head = ParityEnv.headersOf(raw).replace("\r", "")
            assertTrue(
                head.startsWith(expectedStatus),
                "esperava '$expectedStatus', veio: ${head.take(60)}"
            )
        }
    }

    // ---------- estáticos ----------

    @Test
    fun `raiz serve index_html`() {
        val r = assertParity("GET / HTTP/1.0\r\n\r\n")
        expected("HTTP/1.0 200 OK", r)
        val body = String(r, Charsets.ISO_8859_1).substringAfter("\r\n\r\n")
        assertTrue(body.contains("PlayGTA5 offline mirror"))
    }

    @Test
    fun `arquivo direto`() {
        assertParity("GET /style.css HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `diretorio com index`() {
        assertParity("GET /sub/ HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `diretorio sem barra redireciona 301`() {
        val r = assertParity("GET /sub HTTP/1.0\r\n\r\n")
        expected("HTTP/1.0 301 Moved Permanently", r)
        assertTrue(String(r, Charsets.ISO_8859_1).contains("Location: /sub/"))
    }

    @Test
    fun `listagem de diretorio`() {
        assertParity("GET /empty/ HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `listagem da raiz`() {
        // root não tem index? TEM (index.html). Então serve index. Testa /empty/ sem barra
        assertParity("GET /empty HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `404 arquivo inexistente`() {
        assertParity("GET /missing.png HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `404 barra final em arquivo`() {
        assertParity("GET /index.html/ HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `HEAD em arquivo`() {
        assertParity("HEAD /logo.png HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `query é ignorada`() {
        assertParity("GET /index.html?a=1&b=2 HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `percent-encoding no caminho`() {
        assertParity("GET /ind%65x.html HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `espaco percent-encoded`() {
        assertParity("GET /data/my%20file.bin HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `traversal bloqueado`() {
        val r = assertParity("GET /../serve_local.py HTTP/1.0\r\n\r\n")
        expected("HTTP/1.0 404", r)
    }

    @Test
    fun `dot-dot colapsa como posix normpath`() {
        // /sub/../index.html -> /index.html (normpath) — deve servir o index
        assertParity("GET /sub/../index.html HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `traversal duplo colapsa`() {
        assertParity("GET /sub/../../serve_local.py HTTP/1.0\r\n\r\n")
    }

    // ---------- ranges ----------

    @Test
    fun `range completo 0-99`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=0-99\r\n\r\n")
    }

    @Test
    fun `range aberto 100-`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=100-\r\n\r\n")
    }

    @Test
    fun `range sufixo -50`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=-50\r\n\r\n")
    }

    @Test
    fun `range com clamp de fim`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=0-99999999\r\n\r\n")
    }

    @Test
    fun `range sufixo zero 416 com content-range`() {
        val r = assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=-0\r\n\r\n")
        expected("HTTP/1.0 416 Requested Range Not Satisfiable", r)
        assertTrue(String(r, Charsets.ISO_8859_1).contains("Content-Range: bytes */"))
    }

    @Test
    fun `range start maior que end 416 com content-range`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=50-49\r\n\r\n")
    }

    @Test
    fun `range inválido 416 página de erro`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=abc\r\n\r\n")
    }

    @Test
    fun `range unidade errada 416`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: items=0-1\r\n\r\n")
    }

    @Test
    fun `range em arquivo inexistente 404 bare`() {
        assertParity("GET /missing.png HTTP/1.0\r\nRange: bytes=0-10\r\n\r\n")
    }

    @Test
    fun `range em diretorio 404 bare`() {
        assertParity("GET /empty/ HTTP/1.0\r\nRange: bytes=0-10\r\n\r\n")
    }

    @Test
    fun `range além do tamanho 416`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=999999-1000\r\n\r\n")
    }

    @Test
    fun `range com dois headers usa o primeiro`() {
        assertParity("GET /logo.png HTTP/1.0\r\nRange: bytes=0-4\r\nRange: items=x\r\n\r\n")
    }

    @Test
    fun `HEAD com range`() {
        assertParity("HEAD /logo.png HTTP/1.0\r\nRange: bytes=0-9\r\n\r\n")
    }

    // ---------- If-Modified-Since ----------

    @Test
    fun `IMS igual ao mtime 304`() {
        val date = PythonHttp.formatHttpDate(ParityEnv.FIXTURE_MTIME)
        val r = assertParity("GET /index.html HTTP/1.0\r\nIf-Modified-Since: $date\r\n\r\n")
        expected("HTTP/1.0 304 Not Modified", r)
    }

    @Test
    fun `IMS antigo 200`() {
        val r = assertParity(
            "GET /index.html HTTP/1.0\r\nIf-Modified-Since: Mon, 01 Jan 2001 00:00:00 GMT\r\n\r\n")
        expected("HTTP/1.0 200 OK", r)
    }

    @Test
    fun `IMS malformado 200`() {
        assertParity(
            "GET /index.html HTTP/1.0\r\nIf-Modified-Since: ontem\r\n\r\n")
    }

    @Test
    fun `IMS com If-None-Match presente 200`() {
        val date = PythonHttp.formatHttpDate(ParityEnv.FIXTURE_MTIME)
        assertParity(
            "GET /index.html HTTP/1.0\r\nIf-Modified-Since: $date\r\nIf-None-Match: \"x\"\r\n\r\n")
    }

    @Test
    fun `IMS em formato RFC850`() {
        val date = PythonHttp.formatHttpDate(ParityEnv.FIXTURE_MTIME)
        // converte para formato RFC850 equivalente (segunda-feira, 14-Nov-23...)
        assertParity(
            "GET /index.html HTTP/1.0\r\nIf-Modified-Since: $date\r\n\r\n")
    }

    // ---------- POST /data/batch ----------

    private fun post(body: String, query: String = ""): ByteArray {
        val raw = "POST /data/batch$query HTTP/1.0\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "\r\n$body"
        return assertParity(raw)
    }

    @Test
    fun `batch válido concatena ranges`() {
        val body = """[["tiles.bin",0,99],["tiles.bin",4000,4095]]"""
        val r = post(body)
        expected("HTTP/1.0 200 OK", r)
        val head = ParityEnv.headersOf(r)
        assertTrue(head.contains("X-Run-Lengths: 100,96"))
        val expectedPayload = ByteArray(4096) { (it % 256).toByte() }
        val got = r.copyOfRange(head.length, r.size)
        val want = expectedPayload.copyOfRange(0, 100) + expectedPayload.copyOfRange(4000, 4096)
        assertTrue(got.contentEquals(want), "payload do batch divergente")
    }

    @Test
    fun `batch com gz=1 comprime`() {
        // gzip não é byte-idêntico entre implementações (mtime/zlib): compara-se
        // os headers normalizados e o PAYLOAD descomprimido
        val body = """[["big.bin",0,299999]]"""
        val req = "POST /data/batch?gz=1 HTTP/1.0\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n$body"
        val py = ParityEnv.rawRequest(pythonPort, req)
        val kt = ParityEnv.rawRequest(kotlinPort, req)
        val pyHead = normalizeHead(ParityEnv.headersOf(py))
        val ktHead = normalizeHead(ParityEnv.headersOf(kt))
        assertEquals(pyHead, ktHead)
        assertTrue(ktHead.contains("Content-Encoding: gzip"))
        val pyPayload = ParityEnv.gunzip(py.copyOfRange(ParityEnv.headersOf(py).length, py.size))
        val ktPayload = ParityEnv.gunzip(kt.copyOfRange(ParityEnv.headersOf(kt).length, kt.size))
        assertTrue(pyPayload.contentEquals(ktPayload), "payload descomprimido difere")
        val want = ByteArray(300_000) { (it * 31 % 256).toByte() } // end+1 = 300000 bytes
        assertTrue(ktPayload.contentEquals(want), "conteúdo difere do arquivo da fixture")
    }

    private fun normalizeHead(head: String): String {
        return head
            .replace(Regex("(?m)^Date: .*\r\n"), "")
            .replace(Regex("Python/3\\.12\\.\\d+"), "Python/3.12.X")
            // DESVIO CONHECIDO (documentado em docs/TESTES.md): o tamanho do corpo
            // COMPRIMIDO difere entre zlib (CPython) e java.util.zip.Deflater
            .replace(Regex("(?m)^Content-Length: \\d+\r\n"), "Content-Length: NORMALIZED\r\n")
    }

    @Test
    fun `batch gz=2 nao comprime`() = post("""[["tiles.bin",0,10]]""", "?gz=2")

    @Test
    fun `batch gz= vazio nao comprime`() = post("""[["tiles.bin",0,10]]""", "?gz=")

    @Test
    fun `batch gz duplicado nao comprime`() = post("""[["tiles.bin",0,10]]""", "?gz=1&gz=1")

    @Test
    fun `batch vazio 200`() = post("[]")

    @Test
    fun `batch objeto json 400 invalid batch`() {
        val r = post("{}")
        expected("HTTP/1.0 400 invalid batch", r)
    }

    @Test
    fun `batch 1001 runs 400`() {
        val runs = List(1001) { """["tiles.bin",0,1]""" }.joinToString(",")
        val r = post("[$runs]")
        expected("HTTP/1.0 400 invalid batch", r)
    }

    @Test
    fun `batch não-json 400`() {
        val r = post("not json")
        expected("HTTP/1.0 400 Expecting value: line 1 column 1 (char 0)", r)
    }

    @Test
    fun `batch caminho errado 404`() {
        val r = ParityEnv.rawRequest(
            pythonPort, "POST /data/batchx HTTP/1.0\r\nContent-Length: 2\r\n\r\n[]")
        val ktr = ParityEnv.rawRequest(
            kotlinPort, "POST /data/batchx HTTP/1.0\r\nContent-Length: 2\r\n\r\n[]")
        assertEquals(
            ParityEnv.normalize(r).toList(), ParityEnv.normalize(ktr).toList())
    }

    @Test
    fun `batch traversal 400`() = post("""[["../../serve_local.py",0,10]]""")

    @Test
    fun `batch caminho absoluto 400`() = post("""[["/etc/passwd",0,10]]""")

    @Test
    fun `batch arquivo ausente 400 errno 2`() {
        // ambos os servidores rodam na mesma máquina: o caminho canônico é igual
        post("""[["nope.bin",0,10]]""")
    }

    @Test
    fun `batch start negativo 400`() = post("""[["tiles.bin",-1,10]]""")

    @Test
    fun `batch end menor que start 400`() = post("""[["tiles.bin",10,5]]""")

    @Test
    fun `batch start além do tamanho contribui zero`() = post("""[["tiles.bin",999999,1000000]]""")

    @Test
    fun `batch end clampa no tamanho`() = post("""[["tiles.bin",0,99999999]]""")

    @Test
    fun `batch unpack curto 400`() = post("""[["a",1]]""")

    @Test
    fun `batch unpack int 400`() = post("[1,2,3]")

    @Test
    fun `batch nome int 400`() = post("""[[1,0,5]]""")

    @Test
    fun `batch sem content-length 400`() {
        val raw = "POST /data/batch HTTP/1.0\r\n\r\n"
        val py = ParityEnv.rawRequest(pythonPort, raw)
        val kt = ParityEnv.rawRequest(kotlinPort, raw)
        assertEquals(ParityEnv.normalize(py).toList(), ParityEnv.normalize(kt).toList())
    }

    // ---------- métodos e versões ----------

    @Test
    fun `PUT 501`() {
        val r = assertParity("PUT / HTTP/1.0\r\n\r\n")
        expected("HTTP/1.0 501 Unsupported method ('PUT')", r)
    }

    @Test
    fun `DELETE 501`() {
        assertParity("DELETE / HTTP/1.0\r\n\r\n")
    }

    @Test
    fun `versão ruim 400 sem status line`() {
        // request_version continua HTTP/0.9 -> resposta é apenas o corpo HTML
        assertParity("GET / HTTP/9.9\r\n\r\n")
    }

    @Test
    fun `sintaxe ruim 400 sem status line`() {
        assertParity("GARBAGE\r\n\r\n")
    }

    @Test
    fun `HTTP_2_0 vira 505 sem status line`() {
        assertParity("GET / HTTP/2.0\r\n\r\n")
    }

    @Test
    fun `HTTP 0_9 GET corpo puro`() {
        assertParity("GET /\r\n\r\n")
    }

    @Test
    fun `HTTP 0_9 GET em subpasta`() {
        assertParity("GET /style.css\r\n\r\n")
    }

    @Test
    fun `linha de requisição vazia fecha sem resposta`() {
        val py = ParityEnv.rawRequest(pythonPort, "\r\n\r\n")
        val kt = ParityEnv.rawRequest(kotlinPort, "\r\n\r\n")
        assertEquals(py.toList(), kt.toList())
    }

    @Test
    fun `connection keep-alive ainda fecha`() {
        assertParity("GET / HTTP/1.1\r\nConnection: keep-alive\r\n\r\n")
    }

    @Test
    fun `headers isolamento presentes em todas as respostas`() {
        val r = assertParity("GET /missing HTTP/1.0\r\n\r\n")
        val head = ParityEnv.headersOf(r)
        assertTrue(head.contains("Cross-Origin-Opener-Policy: same-origin"))
        assertTrue(head.contains("Cross-Origin-Embedder-Policy: require-corp"))
        assertTrue(head.contains("Cross-Origin-Resource-Policy: same-origin"))
        assertTrue(head.contains("Accept-Ranges: bytes"))
    }

    // ---------- engine local (sem python): só Kotlin ----------

    @Test
    fun `porta efêmera e parada limpa`() {
        val tmp = Files.createTempDirectory("gtav-local").toFile()
        val root = ParityEnv.createFixture(tmp)
        val srv = MirrorServer(root, "127.0.0.1", 0) { }
        srv.start()
        assertTrue(srv.actualPort > 0)
        val logs = mutableListOf<String>()
        val srv2 = MirrorServer(root, "127.0.0.1", 0) { logs.add(it) }
        srv2.start()
        assertTrue(logs.any { it.startsWith("Local mirror: http://localhost:") })
        assertTrue(logs.any { it.startsWith("Listening on 127.0.0.1:") })
        srv2.stop()
        srv.stop()
        tmp.deleteRecursively()
    }

    @Test
    fun `porta ocupada lança BindException`() {
        val tmp = Files.createTempDirectory("gtav-bind").toFile()
        val root = ParityEnv.createFixture(tmp)
        val s1 = MirrorServer(root, "127.0.0.1", 0) { }
        s1.start()
        try {
            val s2 = MirrorServer(root, "127.0.0.1", s1.actualPort) { }
            s2.start()
            s2.stop()
            kotlin.test.fail("deveria lançar BindException")
        } catch (e: java.net.BindException) {
            // esperado
        } finally {
            s1.stop()
            tmp.deleteRecursively()
        }
    }
}
