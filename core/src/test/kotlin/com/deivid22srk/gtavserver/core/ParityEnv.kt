package com.deivid22srk.gtavserver.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files

/**
 * Infraestrutura dos testes de paridade:
 *  - FixtureMirror: cria a árvore `mirror/playgta5.com` determinística;
 *  - PythonMirror: executa o serve_local.py ORIGINAL (recursos de teste, sem
 *    modificações) com o Python 3 do ambiente, exatamente como o Launch-Local.cmd;
 *  - RawHttp: cliente HTTP bruto para comparação byte a byte.
 */
object ParityEnv {

    /** Cria a fixture do mirror em `baseDir` e devolve a raiz servida. */
    fun createFixture(baseDir: File): File {
        val root = File(baseDir, "mirror/playgta5.com").apply { mkdirs() }
        val data = File(root, "data").apply { mkdirs() }

        fun write(f: File, bytes: ByteArray, mtimeMs: Long = FIXTURE_MTIME) {
            f.writeBytes(bytes)
            f.setLastModified(mtimeMs)
        }

        write(File(root, "index.html"), ("<html><head><title>PlayGTA5 mirror</title></head>" +
            "<body><h1>PlayGTA5 offline mirror</h1><script src=\"/js/app.js\"></script>" +
            "</body></html>\n").toByteArray())
        write(File(root, "style.css"), "body { margin: 0; background: #111; }\n".toByteArray())
        write(File(root, "game.wasm"), byteArrayOf(
            0.toByte(), 'a'.code.toByte(), 115.toByte(), 109.toByte(), 1.toByte(),
            0.toByte(), 0.toByte(), 0.toByte()) +
            ByteArray(300) { (it % 251).toByte() })
        write(File(root, "logo.png"), byteArrayOf(
            (-119).toByte(), 80.toByte(), 78.toByte(), 71.toByte()) +
            ByteArray(1000) { (it * 7 % 256).toByte() })
        write(File(root, "favicon.ico"), ByteArray(64) { it.toByte() })
        File(root, "js").mkdirs()
        write(File(root, "js/app.js"), "console.log('mirror ok');\n".toByteArray())
        File(root, "empty").mkdirs()
        File(root, "sub").mkdirs()
        write(File(root, "sub/index.html"), "<h1>sub index</h1>\n".toByteArray())
        write(File(root, "data/tiles.bin"), ByteArray(4096) { (it % 256).toByte() })
        write(File(root, "data/big.bin"), ByteArray(300_000) { (it * 31 % 256).toByte() })
        write(File(root, "data/my file.bin"), "conteúdo com espaço\n".toByteArray(Charsets.UTF_8))
        write(File(data, "packed.bin"), ByteArray(8192) { ((it * 13) % 256).toByte() })
        return root
    }

    const val FIXTURE_MTIME = 1_700_000_000_000L // segundos inteiros -> datas estáveis

    /** Executa o serve_local.py original com --port 0 e devolve (porta, processo). */
    fun startPython(scriptDir: File): Pair<Int, Process> {
        val py = listOf("python3", "python").firstOrNull { exe ->
            try {
                val p = ProcessBuilder(exe, "--version").start()
                val ok = p.waitFor() == 0
                p.destroy()
                ok
            } catch (e: Exception) {
                false
            }
        } ?: throw IllegalStateException("Python 3 não encontrado no ambiente de teste")
        val pb = ProcessBuilder(
            py, File(scriptDir, "serve_local.py").absolutePath,
            "--host", "127.0.0.1", "--port", "0"
        )
        pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        pb.environment()["PYTHONUNBUFFERED"] = "1"
        pb.redirectErrorStream(false)
        val proc = pb.start()
        // lê stderr em background (tracebacks)
        val errThread = Thread {
            try {
                proc.errorStream.copyTo(ByteArrayOutputStream())
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
        val port = waitForPort(proc.inputStream)
        return Pair(port, proc)
    }

    private fun waitForPort(stdout: InputStream): Int {
        val buf = ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 30_000
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            val available = stdout.available()
            if (available > 0) {
                val chunk = ByteArray(available)
                stdout.read(chunk)
                buf.write(chunk)
                sb.append(String(chunk))
                val m = Regex("Listening on 127\\.0\\.0\\.1:(\\d+)").find(sb.toString())
                if (m != null) return m.groupValues[1].toInt()
            } else {
                Thread.sleep(50)
            }
        }
        throw IllegalStateException("servidor python não iniciou: $sb")
    }

    /** Cliente HTTP bruto: envia bytes, lê a resposta completa até EOF. */
    fun rawRequest(port: Int, rawRequest: String, timeoutMs: Int = 10_000): ByteArray {
        return rawRequest(port, rawRequest.toByteArray(Charsets.ISO_8859_1), timeoutMs)
    }

    fun rawRequest(port: Int, raw: ByteArray, timeoutMs: Int = 10_000): ByteArray {
        val s = Socket("127.0.0.1", port)
        s.soTimeout = timeoutMs
        try {
            // ATENÇÃO: fechar o OutputStream fecha o socket — usar shutdownOutput
            val out = s.getOutputStream()
            out.write(raw)
            out.flush()
            s.shutdownOutput()
            val outBuf = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            s.getInputStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n == -1) break
                    outBuf.write(buf, 0, n)
                }
            }
            return outBuf.toByteArray()
        } finally {
            s.close()
        }
    }

    /**
     * Normaliza bytes de resposta para comparação:
     *  - linha Date: substituída por valor fixo (relógio difere entre servidores);
     *  - sufixo de versão do Server (Python/3.12.x -> Python/3.12.X).
     */
    fun normalize(raw: ByteArray): ByteArray {
        var text = String(raw, Charsets.ISO_8859_1)
        text = text.replace(Regex("Python/3\\.12\\.\\d+"), "Python/3.12.X")
        text = text.replace(Regex("Date: [A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"),
            "Date: NORMALIZED")
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    fun gunzip(raw: ByteArray): ByteArray {
        val ins = java.util.zip.GZIPInputStream(raw.inputStream())
        return ins.use { it.readBytes() }
    }

    fun headersOf(raw: ByteArray): String {
        val text = String(raw, Charsets.ISO_8859_1)
        val idx = text.indexOf("\r\n\r\n")
        return if (idx == -1) text else text.substring(0, idx + 4)
    }
}
