package com.deivid22srk.gtavserver.core

import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Espelho do servidor original (`serve_local.py`):
 * ThreadingHTTPServer + SimpleHTTPRequestHandler com headers de isolamento,
 * byte ranges e POST /data/batch.
 *
 * Paridade de transporte com o original:
 *  - HTTP/1.0, uma requisição por conexão (sem keep-alive);
 *  - SO_REUSEADDR (allow_reuse_address=1);
 *  - backlog 5 (socketserver.TCPServer.request_queue_size = 5);
 *  - uma thread daemon por conexão (ThreadingMixIn.daemon_threads = True).
 */
class MirrorServer(
    /** Raiz servida — equivale a `ROOT = <dir do script>/mirror/playgta5.com`. */
    val root: java.io.File,
    /** "127.0.0.1" (padrão do original) ou outro host de escuta. */
    val host: String = "127.0.0.1",
    /** Porta de escuta; 0 = efêmera (como argparse type=int + ThreadingHTTPServer). */
    port: Int = 8000,
    /** Callback de log em tempo real (formato igual ao BaseHTTPRequestHandler). */
    private val onLog: ((String) -> Unit)? = null,
) {
    val requestedPort: Int = port
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val openSockets = CopyOnWriteArrayList<Socket>()
    private val connCounter = AtomicLong(0)

    @Volatile
    var actualPort: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get()

    /** Idêntico a version_string() do SimpleHTTPRequestHandler 3.12.14. */
    val serverVersion: String = "SimpleHTTP/0.6 Python/3.12.14"

    @Throws(IOException::class)
    fun start() {
        check(!running.get()) { "server already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true // HTTPServer.allow_reuse_address = 1
        try {
            ss.bind(InetSocketAddress(host, requestedPort), 5)
        } catch (e: BindException) {
            try { ss.close() } catch (_: IOException) {}
            throw e
        }
        serverSocket = ss
        actualPort = ss.localPort
        running.set(true)
        // As duas linhas de inicialização do __main__ do serve_local.py.
        log("Local mirror: http://localhost:%d/".format(java.util.Locale.ROOT, actualPort))
        log("Listening on %s:%d".format(java.util.Locale.ROOT, host, actualPort))
        acceptThread = Thread({
            while (running.get()) {
                try {
                    val client = ss.accept()
                    if (!running.get()) { try { client.close() } catch (_: IOException) {}; break }
                    openSockets.add(client)
                    val n = connCounter.incrementAndGet()
                    val t = Thread({
                        try {
                            ConnectionHandler(this@MirrorServer, client).handle()
                        } finally {
                            openSockets.remove(client)
                            try { client.close() } catch (_: IOException) {}
                        }
                    }, "MirrorConn-$n")
                    t.isDaemon = true
                    t.start()
                } catch (e: IOException) {
                    if (running.get()) log("accept error: ${e.message}")
                    break
                }
            }
        }, "MirrorAccept")
        acceptThread!!.isDaemon = true
        acceptThread!!.start()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        for (s in openSockets) {
            try { s.close() } catch (_: IOException) {}
        }
        openSockets.clear()
        log("server stopped")
    }

    internal fun log(line: String) {
        onLog?.invoke(line)
    }
}
