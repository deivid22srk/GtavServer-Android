package com.deivid22srk.gtavserver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilsTest {

    @Test
    fun `mime da tabela padrão`() {
        assertEquals("text/html", MimeTypes.guessType("/x/y/index.html"))
        assertEquals("text/javascript", MimeTypes.guessType("app.js"))
        assertEquals("application/wasm", MimeTypes.guessType("game.wasm"))
        assertEquals("image/png", MimeTypes.guessType("logo.png"))
        assertEquals("text/css", MimeTypes.guessType("/a/style.css"))
        assertEquals("application/json", MimeTypes.guessType("data.json"))
        assertEquals("application/octet-stream", MimeTypes.guessType("chunk.data"))
        assertEquals("application/octet-stream", MimeTypes.guessType("arquivo-sem-ext"))
    }

    @Test
    fun `mime com encoding`() {
        assertEquals("application/gzip", MimeTypes.guessType("a.tar.gz"))
        assertEquals("application/x-tar", MimeTypes.guessType("a.tgz"))
        assertEquals("application/x-tar", MimeTypes.guessType("a.TGZ"))
        assertEquals("application/octet-stream", MimeTypes.guessType("a.Z"))
        assertEquals("application/x-bzip2", MimeTypes.guessType("a.bz2"))
    }

    @Test
    fun `mime case-insensitive no tipo`() {
        assertEquals("image/png", MimeTypes.guessType("LOGO.PNG"))
        // .GZ bate em extensions_map (lower) -> application/gzip
        assertEquals("application/gzip", MimeTypes.guessType("A.GZ"))
    }

    @Test
    fun `splitext igual ao posixpath`() {
        assertEquals(Pair("a.tar", ".gz"), PythonHttp.splitExt("a.tar.gz"))
        assertEquals(Pair(".bashrc", ""), PythonHttp.splitExt(".bashrc"))
        assertEquals(Pair("dir/.hidden", ""), PythonHttp.splitExt("dir/.hidden"))
        assertEquals(Pair("a.b/c", ""), PythonHttp.splitExt("a.b/c"))
        assertEquals(Pair("x", ""), PythonHttp.splitExt("x"))
    }

    @Test
    fun `datas HTTP`() {
        // 1_700_000_000_000 ms = Tue, 14 Nov 2023 22:13:20 GMT
        assertEquals("Tue, 14 Nov 2023 22:13:20 GMT", PythonHttp.formatHttpDate(1_700_000_000_000L))
        val q = PythonHttp.parseHttpDate("Tue, 14 Nov 2023 22:13:20 GMT")
        assertEquals(1_700_000_000_000L, q?.first)
        assertEquals(true, q?.second)
        assertNull(PythonHttp.parseHttpDate("ontem"))
        // fuso não-UTC: parsedate ok, mas isUtc=false (send_head ignora 304)
        val r = PythonHttp.parseHttpDate("Tue, 14 Nov 2023 22:13:20 +0200")
        assertEquals(false, r?.second)
        // RFC850 com ano de 2 dígitos
        val r850 = PythonHttp.parseHttpDate("Tuesday, 14-Nov-23 22:13:20 GMT")
        assertEquals(1_700_000_000_000L, r850?.first)
        // asctime
        val rasc = PythonHttp.parseHttpDate("Tue Nov 14 22:13:20 2023")
        assertEquals(1_700_000_000_000L, rasc?.first)
    }

    @Test
    fun `html escape`() {
        assertEquals("a&amp;b&lt;c&gt;d", PythonHttp.htmlEscape("a&b<c>d"))
        assertEquals("\"aspas\"", PythonHttp.htmlEscape("\"aspas\"")) // quote=False
    }

    @Test
    fun `quote e unquote`() {
        assertEquals("%E3%81%82", PythonHttp.quote("あ"))
        assertEquals("my%20file.bin", PythonHttp.quote("my file.bin"))
        assertEquals("a/b", PythonHttp.quote("a/b"))
        assertEquals("my file.bin", PythonHttp.unquote("my%20file.bin"))
        assertEquals("a+b", PythonHttp.unquote("a+b")) // '+' não decodifica
        assertEquals("\uFFFD", PythonHttp.unquote("%FF")) // byte inválido
    }

    @Test
    fun `parse_qs`() {
        assertEquals(mapOf("gz" to listOf("1")), PythonHttp.parseQs("gz=1"))
        assertEquals(emptyMap(), PythonHttp.parseQs("gz="))
        assertEquals(emptyMap(), PythonHttp.parseQs("gz"))
        // python parse_qs preserva valores duplicados: {'gz': ['1', '1']}
        assertEquals(mapOf("gz" to listOf("1", "1")), PythonHttp.parseQs("gz=1&gz=1"))
        assertEquals(mapOf("a" to listOf("x y")), PythonHttp.parseQs("a=x+y"))
        assertEquals(mapOf("a" to listOf("1"), "b" to listOf("2")), PythonHttp.parseQs("a=1&b=2"))
    }

    @Test
    fun `json decode messages`() {
        try {
            MiniJson.parse("not json")
            kotlin.test.fail()
        } catch (e: MiniJson.JsonDecodeError) {
            assertEquals("Expecting value: line 1 column 1 (char 0)", e.message)
        }
        val arr = MiniJson.parse("""[["a",1,2]]""")
        val inner = (arr as MiniJson.Value.JArray).items[0] as MiniJson.Value.JArray
        assertEquals("a", (inner.items[0] as MiniJson.Value.JString).v)
    }

    @Test
    fun `os error messages`() {
        assertEquals(
            "[Errno 2] No such file or directory: '/x/y'",
            PythonHttp.osErrorMessage(2, "/x/y"))
        assertEquals(
            "[Errno 21] Is a directory: '/x/y'",
            PythonHttp.osErrorMessage(21, "/x/y"))
    }

    @Test
    fun `mensagens do HTTPStatus batem com 3_12`() {
        assertEquals("Request Entity Too Large", HttpStatus.phrase(413))
        assertEquals("Requested Range Not Satisfiable", HttpStatus.phrase(416))
        assertEquals("Nothing matches the given URI", HttpStatus.description(404))
        assertEquals("Entity is too large", HttpStatus.description(413))
        assertEquals("Server does not support this operation", HttpStatus.description(501))
    }

    @Test
    fun `repro do ERROR_MESSAGE_FORMAT`() {
        val page = ConnectionHandler.ERROR_MESSAGE_FORMAT
        assertTrue(page.startsWith("<!DOCTYPE HTML>"))
        assertTrue(page.contains("<p>Error code: %s</p>"))
        assertTrue(page.endsWith("</html>\n"))
    }
}
