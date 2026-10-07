package com.deivid22srk.gtavserver.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Réplica fiel do protocolo do `serve_local.py` (SimpleHTTPRequestHandler 3.12.14
 * + overrides do mirror): request line/headers com os mesmos limites, translate_path,
 * send_head (com index_pages, 301 de diretório, 304 If-Modified-Since), list_directory,
 * byte ranges customizados, POST /data/batch, send_error com o template padrão e os
 * headers de isolamento globais (COOP/COEP/CORP/Accept-Ranges via end_headers).
 *
 * Particularidades do BaseHTTPRequestHandler replicadas de propósito:
 *  - request_version começa em "HTTP/0.9": erros ANTES do parse da versão recebem
 *    apenas o corpo HTML (sem status line/headers), como no original;
 *  - request line > 65536 bytes: request_version vira "" e a resposta 414 É completa;
 *  - send_error(int(x)) do header Content-Length inválido no POST encerra a conexão
 *    sem resposta (traceback no original);
 *  - 404 do path de range é bare (frase "Not Found"), o do path normal tem
 *    message "File not found".
 */
class ConnectionHandler(
    private val server: MirrorServer,
    private val socket: Socket,
) {
    companion object {
        const val MAX_REQUEST_LINE = 65537 // python: readline(65537)
        const val MAX_HEADER_LINE = 65536 // http.client._MAXLINE
        const val MAX_HEADERS = 100 // http.client._MAXHEADERS
        const val MAX_BATCH_BODY = 1024 * 1024
        const val MAX_BATCH_TOTAL = 64L * 1024 * 1024
        const val MAX_BATCH_RUNS = 1000
        val INDEX_PAGES = arrayOf("index.html", "index.htm")

        // DEFAULT_ERROR_MESSAGE do http/server.py 3.12.14 (placeholders %s na ordem:
        // code, message, code, explain)
        const val ERROR_MESSAGE_FORMAT: String =
            "<!DOCTYPE HTML>\n" +
                "<html lang=\"en\">\n" +
                "    <head>\n" +
                "        <meta charset=\"utf-8\">\n" +
                "        <title>Error response</title>\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <h1>Error response</h1>\n" +
                "        <p>Error code: %s</p>\n" +
                "        <p>Message: %s.</p>\n" +
                "        <p>Error code explanation: %s - %s.</p>\n" +
                "    </body>\n" +
                "</html>\n"
    }

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val headerBuffer = StringBuilder()

    // Estado do request em processamento (equivalente aos atributos do handler python)
    private var requestVersion = "HTTP/0.9" // default_request_version
    private var command = ""
    private var pathStr = ""
    private var requestLineForLog = ""

    fun handle() {
        try {
            handleOneRequest()
        } catch (_: IOException) {
            // conexão interrompida (cliente sumiu / stop()) — igual ao original
        } catch (e: Exception) {
            server.log("handler error: $e")
        } finally {
            try { output.flush() } catch (_: IOException) {}
        }
    }

    private fun handleOneRequest() {
        val rawLine = readLineCapped(MAX_REQUEST_LINE) ?: return // EOF silencioso
        if (rawLine.size > MAX_REQUEST_LINE - 1) {
            // python: requestline='', request_version='', command='' -> 414 COM status line
            requestVersion = ""
            command = ""
            requestLineForLog = ""
            sendError(414)
            return
        }
        val requestLine = String(rawLine, Charsets.ISO_8859_1).trimEnd('\r', '\n')
        if (!parseRequest(requestLine)) return // erro respondido ou fechamento silencioso
        when (command) {
            "GET" -> doGet()
            "HEAD" -> doHead()
            "POST" -> doPost()
            else -> sendError(501, "Unsupported method (${PythonHttp.pyRepr(command)})", null)
        }
        output.flush()
    }

    // ---------- parse_request (BaseHTTPRequestHandler) ----------

    private fun parseRequest(requestLine: String): Boolean {
        requestLineForLog = requestLine
        val words = requestLine.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false // fecha sem resposta (python: return False)
        if (words.size >= 3) {
            val version = words.last()
            var ok = version.startsWith("HTTP/")
            if (ok) {
                val base = version.substringAfter("HTTP/")
                val parts = base.split(".")
                ok = parts.size == 2 &&
                    parts.all { it.isNotEmpty() && it.all { c -> c.isDigit() } } &&
                    parts.all { it.length <= 10 }
            }
            if (!ok) {
                // request_version continua "HTTP/0.9" -> resposta SEM status line
                sendError(400, "Bad request version (${PythonHttp.pyRepr(version)})", null)
                return false
            }
            val major = version.substringAfter("HTTP/").split(".")[0].toInt()
            if (major >= 2) {
                val base = version.substringAfter("HTTP/")
                sendError(505, "Invalid HTTP version ($base)", null)
                return false
            }
            requestVersion = version
        }
        if (words.size !in 2..3) {
            sendError(400, "Bad request syntax (${PythonHttp.pyRepr(requestLine)})", null)
            return false
        }
        command = words[0]
        pathStr = words[1]
        if (words.size == 2) { // HTTP/0.9 explícito
            if (command != "GET") {
                sendError(400, "Bad HTTP/0.9 request type (${PythonHttp.pyRepr(command)})", null)
                return false
            }
        }
        // gh-87389: reduz '//' inicial para '/'
        if (pathStr.startsWith("//")) pathStr = "/" + pathStr.trimStart('/')
        val headers = readHeaders() ?: return false // erro 431 já enviado
        this.headers = headers
        return true
    }

    private var headers: Map<String, String> = emptyMap()

    private fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
    private fun hasHeader(name: String): Boolean = headers.containsKey(name.lowercase(Locale.ROOT))

    // ---------- leitura de headers (http.client.parse_headers) ----------

    private fun readHeaders(): Map<String, String>? {
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val lineBytes = readLineCapped(MAX_HEADER_LINE + 1) ?: break
            if (lineBytes.size > MAX_HEADER_LINE) {
                sendError(
                    431, "Line too long",
                    "got more than $MAX_HEADER_LINE bytes when reading header line"
                )
                return null
            }
            val line = String(lineBytes, Charsets.ISO_8859_1).trimEnd('\r', '\n')
            if (line.isEmpty()) break // fim dos headers
            if (line.startsWith(" ") || line.startsWith("\t")) {
                // obs-fold: continuação do header anterior (email.feedparser)
                if (headers.isNotEmpty()) {
                    val lastKey = headers.keys.last()
                    headers[lastKey] = headers[lastKey]!! + " " + line.trim()
                }
                continue
            }
            count++
            if (count > MAX_HEADERS) {
                sendError(431, "Too many headers", "got more than $MAX_HEADERS headers")
                return null
            }
            val idx = line.indexOf(':')
            if (idx <= 0) continue // linha sem ':' é ignorada pelo parser do email
            val name = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (!headers.containsKey(name.lowercase(Locale.ROOT))) {
                headers[name.lowercase(Locale.ROOT)] = value
            }
        }
        return headers
    }

    /** readline com limite. */
    private fun readLineCapped(maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var read = 0
        while (read < maxBytes) {
            val b = input.read()
            if (b == -1) {
                if (read == 0) return null
                break
            }
            out.write(b)
            read++
            if (b == '\n'.code) break
        }
        return out.toByteArray()
    }

    private fun readExact(count: Long): ByteArray {
        val out = ByteArray(count.toInt())
        var off = 0
        while (off < count) {
            val n = input.read(out, off, (count - off).toInt())
            if (n == -1) throw IOException("EOF no corpo da requisição")
            off += n
        }
        return out
    }

    // ---------- envio de resposta ----------

    private fun logRequest(code: Int) {
        server.log("${socket.inetAddress?.hostAddress ?: "?"} - - [${PythonHttp.formatLogDate()}] " +
            "\"$requestLineForLog\" $code -")
    }

    private fun sendResponse(code: Int, message: String? = null) {
        logRequest(code)
        sendResponseOnly(code, message)
        sendHeader("Server", server.serverVersion)
        sendHeader("Date", PythonHttp.formatHttpDate(System.currentTimeMillis()))
    }

    private fun sendResponseOnly(code: Int, messageIn: String?) {
        if (requestVersion == "HTTP/0.9") return
        val message = messageIn ?: HttpStatus.phrase(code)
        headerBuffer.append("HTTP/1.0 $code $message\r\n")
    }

    private fun sendHeader(name: String, value: String) {
        if (requestVersion == "HTTP/0.9") return
        headerBuffer.append("$name: $value\r\n")
    }

    /** override end_headers do serve_local.py: headers de isolamento globais. */
    private fun endHeaders() {
        if (requestVersion == "HTTP/0.9") return
        sendHeader("Cross-Origin-Opener-Policy", "same-origin")
        sendHeader("Cross-Origin-Embedder-Policy", "require-corp")
        sendHeader("Cross-Origin-Resource-Policy", "same-origin")
        sendHeader("Accept-Ranges", "bytes")
        headerBuffer.append("\r\n")
        output.write(headerBuffer.toString().toByteArray(Charsets.ISO_8859_1))
        headerBuffer.setLength(0)
    }

    private fun sendError(code: Int, messageIn: String? = null, explainIn: String? = null) {
        val shortmsg = HttpStatus.phrase(code)
        val longmsg = HttpStatus.description(code)
        val message = messageIn ?: shortmsg
        val explain = explainIn ?: longmsg
        server.log("${socket.inetAddress?.hostAddress ?: "?"} - - [${PythonHttp.formatLogDate()}] " +
            "code $code, message $message")
        sendResponse(code, message)
        sendHeader("Connection", "close")
        var body: ByteArray? = null
        if (code >= 200 && code != 204 && code != 205 && code != 304) {
            val content = formatErrorPage(code, message, explain)
            body = content.toByteArray(Charsets.UTF_8)
            sendHeader("Content-Type", "text/html;charset=utf-8")
            sendHeader("Content-Length", body.size.toString())
        }
        endHeaders()
        if (command != "HEAD" && body != null) {
            output.write(body)
        }
    }

    /** DEFAULT_ERROR_MESSAGE % {...} com substituição sequencial e literal de %s. */
    private fun formatErrorPage(code: Int, message: String, explain: String): String {
        val args = listOf(code.toString(), PythonHttp.htmlEscape(message), code.toString(),
            PythonHttp.htmlEscape(explain))
        var out = ERROR_MESSAGE_FORMAT
        for (a in args) out = out.replaceFirst("%s", Regex.escapeReplacement(a))
        return out
    }

    // ---------- do_GET / do_HEAD / send_head ----------

    private fun doGet() {
        val source = sendHead()
        if (source != null) {
            try {
                copyfile(source, output)
            } finally {
                source.close()
            }
        }
        output.flush()
    }

    private fun doHead() {
        val source = sendHead()
        source?.close()
        output.flush()
    }

    private fun sendHead(): InputStream? {
        val fsPath = translatePath(pathStr)
        val path = File(fsPath)

        // --- override do serve_local.py: byte ranges (Path.is_file) ---
        val rangeHeader = header("Range")
        if (rangeHeader != null && rangeHeader.isNotEmpty()) {
            if (!path.isFile) {
                sendError(404) // bare: frase "Not Found"
                output.flush()
                return null
            }
            val size = path.length()
            val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(rangeHeader)
            if (m == null || (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty())) {
                sendError(416)
                output.flush()
                return null
            }
            val g1 = m.groupValues[1]
            val g2 = m.groupValues[2]
            val start: Long = if (g1.isNotEmpty()) g1.toLong()
            else maxOf(0L, size - g2.toLong())
            val end: Long = if (g1.isNotEmpty() && g2.isNotEmpty()) minOf(size - 1, g2.toLong())
            else size - 1
            if (start >= size || end < start) {
                sendResponse(416)
                sendHeader("Content-Range", "bytes */$size")
                sendHeader("Content-Length", "0")
                endHeaders()
                output.flush()
                return null
            }
            sendResponse(206)
            sendHeader("Content-Type", MimeTypes.guessType(fsPath))
            sendHeader("Content-Range", "bytes $start-$end/$size")
            sendHeader("Content-Length", (end - start + 1).toString())
            endHeaders()
            output.flush()
            return RangeFileSource(path, start, end)
        }

        // --- send_head original (sem Range) ---
        if (path.isDirectory) {
            val (partsPath, query) = splitUrlQuery(pathStr)
            if (!partsPath.endsWith("/")) {
                sendResponse(301)
                val newUrl = partsPath + "/" + (query?.let { "?$it" } ?: "")
                sendHeader("Location", newUrl)
                sendHeader("Content-Length", "0")
                endHeaders()
                output.flush()
                return null
            }
            for (index in INDEX_PAGES) {
                val index = File(path, index)
                if (index.isFile) {
                    return serveFile(index)
                }
            }
            return listDirectory(path)
        }
        // Issue17324: barra final em caminho de arquivo -> 404
        if (fsPath.endsWith("/")) {
            sendError(404, "File not found", null)
            output.flush()
            return null
        }
        if (!path.isFile) {
            sendError(404, "File not found", null)
            output.flush()
            return null
        }
        return serveFile(path)
    }

    private fun serveFile(file: File): InputStream? {
        val ctype = MimeTypes.guessType(file.absolutePath)
        if (hasHeader("If-Modified-Since") && !hasHeader("If-None-Match")) {
            val parsed = PythonHttp.parseHttpDate(header("If-Modified-Since")!!)
            if (parsed != null) {
                val (imsMillis, isUtc) = parsed
                if (isUtc) {
                    val lastModif = PythonHttp.floorToSecond(file.lastModified())
                    if (lastModif <= imsMillis) {
                        sendResponse(304)
                        endHeaders()
                        output.flush()
                        return null
                    }
                }
            }
        }
        sendResponse(200)
        sendHeader("Content-type", ctype)
        sendHeader("Content-Length", file.length().toString())
        sendHeader("Last-Modified", PythonHttp.formatHttpDate(file.lastModified()))
        endHeaders()
        output.flush()
        return file.inputStream().buffered()
    }

    private class RangeFileSource(file: File, start: Long, private val endIncl: Long) :
        InputStream() {
        private val raf = java.io.RandomAccessFile(file, "r")
        private var remaining = endIncl - start + 1

        init {
            raf.seek(start)
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raf.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n != -1) remaining -= n
            return n
        }

        override fun close() = raf.close()
    }

    private fun copyfile(source: InputStream, out: OutputStream) {
        val buf = ByteArray(1024 * 1024)
        while (true) {
            val n = source.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        out.flush()
    }

    // ---------- translate_path ----------

    /** Réplica de SimpleHTTPRequestHandler.translate_path (3.12.14). */
    internal fun translatePath(urlPath: String): String {
        var path = urlPath.substringBefore('?')
        path = path.substringBefore('#')
        val trailingSlash = path.trimEnd().endsWith("/")
        val unquoted = PythonHttp.unquote(path)
        // posixpath.normpath: colapsa 'a/../', remove './'; depois o filtro do
        // translate_path ignora componentes '.'/'..' residuais (nenhum em path absoluto)
        val normWords = posixNormPathWords(unquoted)
        var result = server.root.absolutePath.trimEnd('/')
        for (word in normWords) {
            // python: ignora componentes com dirname ou '.'/'..'
            if (word == "." || word == "..") continue
            result += "/" + word
        }
        if (trailingSlash) result += "/"
        return result
    }

    /**
     * posixpath.normpath reduzido a componentes (paths URL são absolutos):
     * '/a/../b' -> [b]; '/a/../../b' -> [b]; '/a/./c' -> [a, c]; '/..' -> [].
     */
    private fun posixNormPathWords(path: String): List<String> {
        val absolute = path.startsWith("/")
        val out = mutableListOf<String>()
        for (comp in path.split('/')) {
            when {
                comp.isEmpty() || comp == "." -> {}
                comp == ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") {
                        out.removeAt(out.size - 1)
                    } else if (!absolute) {
                        out.add("..")
                    }
                    // absolute: '..' além da raiz é descartado
                }
                else -> out.add(comp)
            }
        }
        return out
    }

    private fun splitUrlQuery(url: String): Pair<String, String?> {
        val noFragment = url.substringBefore('#')
        return if (noFragment.contains('?')) {
            Pair(noFragment.substringBefore('?'), noFragment.substringAfter('?'))
        } else Pair(noFragment, null)
    }

    // ---------- list_directory ----------

    private fun listDirectory(path: File): InputStream? {
        val list = path.listFiles()
        if (list == null) {
            sendError(404, "No permission to list directory", null)
            output.flush()
            return null
        }
        val names = list.map { it.name }.sortedWith(compareBy { it.lowercase(Locale.ROOT) })
        var displaypath = try {
            PythonHttp.unquote(pathStr)
        } catch (_: Exception) {
            pathStr
        }
        displaypath = PythonHttp.htmlEscape(displaypath)
        val enc = "utf-8" // sys.getfilesystemencoding()
        val title = "Directory listing for $displaypath"
        val r = mutableListOf(
            "<!DOCTYPE HTML>",
            "<html lang=\"en\">",
            "<head>",
            "<meta charset=\"$enc\">",
            "<title>$title</title>\n</head>",
            "<body>\n<h1>$title</h1>",
            "<hr>\n<ul>",
        )
        val byName = list.associateBy { it.name }
        for (name in names) {
            val entry = byName[name]!!
            var displayname = name
            var linkname = name
            if (entry.isDirectory) {
                displayname = "$name/"
                linkname = "$name/"
            }
            if (isSymlink(entry)) {
                displayname = "$name@"
            }
            val li = "<li><a href=\"" + PythonHttp.quote(linkname) + "\">" +
                PythonHttp.htmlEscape(displayname) + "</a></li>"
            r.add(li)
        }
        r.add("</ul>\n<hr>\n</body>\n</html>\n")
        val encoded = r.joinToString("\n").toByteArray(Charsets.UTF_8)
        sendResponse(200)
        sendHeader("Content-type", "text/html; charset=$enc")
        sendHeader("Content-Length", encoded.size.toString())
        endHeaders()
        output.flush()
        return encoded.inputStream()
    }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(Paths.get(f.absolutePath))
    } catch (e: Exception) {
        false
    }

    // ---------- do_POST: /data/batch ----------

    private fun doPost() {
        val (urlPath, query) = splitUrlQuery(pathStr)
        if (urlPath != "/data/batch") {
            sendError(404)
            output.flush()
            return
        }
        val lengthStr = header("Content-Length") ?: "0"
        val length = lengthStr.toLongOrNull()
        if (length == null) {
            // python: int() fora do try -> traceback -> conexão fechada SEM resposta
            server.log("ValueError: invalid literal for int() with base 10: '${lengthStr}'")
            throw IOException("bad content-length")
        }
        if (length > MAX_BATCH_BODY) {
            sendError(413)
            output.flush()
            return
        }
        try {
            val bodyBytes = if (length <= 0) ByteArray(0) else readExact(length)
            if (bodyBytes.size >= 3 && bodyBytes[0] == 0xEF.toByte() &&
                bodyBytes[1] == 0xBB.toByte() && bodyBytes[2] == 0xBF.toByte()
            ) {
                throw MiniJson.JsonDecodeError(
                    "Unexpected UTF-8 BOM (decode using utf-8-sig): line 1 column 1 (char 0)")
            }
            val runsJson = MiniJson.parse(String(bodyBytes, Charsets.UTF_8))
            val runs = runsJson as? MiniJson.Value.JArray
                ?: throw BatchValueError("invalid batch")
            if (runs.items.size > MAX_BATCH_RUNS) throw BatchValueError("invalid batch")
            val selected = mutableListOf<Triple<File, Long, Long>>()
            val dataRoot = canonical(File(server.root, "data"))
            for (item in runs.items) {
                val triple = item as? MiniJson.Value.JArray
                    ?: throw BatchTypeError("cannot unpack non-iterable ${pyTypeName(item)} object")
                if (triple.items.size != 3) {
                    if (triple.items.size < 3) {
                        throw BatchValueError(
                            "not enough values to unpack (expected 3, got ${triple.items.size})")
                    } else {
                        throw BatchValueError("too many values to unpack (expected 3)")
                    }
                }
                val nameV = triple.items[0]
                val startV = triple.items[1]
                val endV = triple.items[2]
                val name = (nameV as? MiniJson.Value.JString)?.v
                    ?: throw BatchTypeError(
                        "unsupported operand type(s) for /: 'PosixPath' and '${pyTypeName(nameV)}'")
                val start = asNumber(startV)
                val end = asNumber(endV)
                // (data_root / name): caminho absoluto substitui; vazio = data_root
                val candidate = if (name.startsWith("/")) File(name) else File(dataRoot, name)
                val file = canonical(candidate)
                val rel = file.absolutePath
                val rootPfx = dataRoot.absolutePath
                val inside = rel == rootPfx || rel.startsWith(rootPfx + "/")
                if (!inside || start < 0 || end < start) throw BatchValueError("invalid file/range")
                if (file.isDirectory) throw BatchOSError(21, file.absolutePath)
                if (!file.exists()) throw BatchOSError(2, file.absolutePath)
                val size = file.length()
                val n = maxOf(0L, minOf(end + 1, size) - start)
                selected.add(Triple(file, start, n))
            }
            val total = selected.sumOf { it.third }
            if (total > MAX_BATCH_TOTAL) throw BatchValueError("batch exceeds 64 MiB")
            val concat = ByteArrayOutputStream()
            for ((file, start, n) in selected) {
                if (n <= 0) continue
                file.inputStream().use { ins ->
                    var remaining = start
                    val buf = ByteArray(8192)
                    while (remaining > 0) {
                        val got = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (got == -1) break
                        remaining -= got
                    }
                    remaining = n
                    while (remaining > 0) {
                        val got = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (got == -1) break
                        concat.write(buf, 0, got)
                        remaining -= got
                    }
                }
            }
            var body = concat.toByteArray()
            val gz = PythonHttp.parseQs(query ?: "").get("gz") == listOf("1")
            if (gz) {
                body = gzipLevel1(body)
            }
            sendResponse(200)
            sendHeader("Content-Type", "application/octet-stream")
            sendHeader("Content-Length", body.size.toString())
            sendHeader("X-Run-Lengths", selected.joinToString(",") { it.third.toString() })
            if (gz) sendHeader("Content-Encoding", "gzip")
            endHeaders()
            output.write(body)
            output.flush()
        } catch (e: BatchValueError) {
            sendError(400, e.message ?: "", null)
            output.flush()
        } catch (e: BatchTypeError) {
            sendError(400, e.message ?: "", null)
            output.flush()
        } catch (e: BatchOSError) {
            sendError(400, PythonHttp.osErrorMessage(e.errno, e.path), null)
            output.flush()
        } catch (e: MiniJson.JsonDecodeError) {
            sendError(400, e.message ?: "", null)
            output.flush()
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            sendError(400, e.message ?: "invalid batch", null)
            output.flush()
        }
    }

    private fun asNumber(v: MiniJson.Value): Long = when (v) {
        is MiniJson.Value.JInt -> v.v
        is MiniJson.Value.JBool -> if (v.v) 1L else 0L
        is MiniJson.Value.JDouble -> {
            if (v.v == Math.floor(v.v) && !v.v.isInfinite()) v.v.toLong()
            else throw BatchTypeError("integer argument expected, got float")
        }
        is MiniJson.Value.JString ->
            throw BatchTypeError("'<' not supported between instances of 'str' and 'int'")
        else -> throw BatchTypeError("an integer is required")
    }

    private fun pyTypeName(v: MiniJson.Value): String = when (v) {
        is MiniJson.Value.JNull -> "NoneType"
        is MiniJson.Value.JBool -> "bool"
        is MiniJson.Value.JInt -> "int"
        is MiniJson.Value.JDouble -> "float"
        is MiniJson.Value.JString -> "str"
        is MiniJson.Value.JArray -> "list"
        is MiniJson.Value.JObject -> "dict"
    }

    private fun canonical(f: File): File = try {
        File(f.canonicalPath)
    } catch (e: IOException) {
        File(f.absolutePath)
    }

    /** gzip.compress(body, compresslevel=1) — mesmo nível; mtime do header = 0. */
    private fun gzipLevel1(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val gz = object : GZIPOutputStream(bos, 512, false) {
            init {
                // nowrap=true (obrigatório: gzip usa DEFLATE cru), nível 1 = BEST_SPEED
                this.def = Deflater(Deflater.BEST_SPEED, true)
            }
        }
        gz.use { it.write(data) }
        return bos.toByteArray()
    }

    private class BatchValueError(msg: String) : Exception(msg)
    private class BatchTypeError(msg: String) : Exception(msg)
    private class BatchOSError(val errno: Int, val path: String) : Exception()
}
