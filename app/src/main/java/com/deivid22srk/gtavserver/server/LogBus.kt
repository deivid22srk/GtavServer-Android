package com.deivid22srk.gtavserver.server

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Barramento único de captura de logs para diagnóstico (inclusive do travamento
 * "Loading audio metadata" / mutex do wasm).
 *
 * Três categorias:
 *  - SERVIDOR: linhas reais do MirrorServer (formato BaseHTTPRequestHandler) + ciclo de vida;
 *  - WEBVIEW : console.log/warn/error do jogo, recursos carregados, erros HTTP/rede,
 *              progresso do Chromium e morte do processo de renderização;
 *  - APP     : eventos do próprio app (telas, orientação, exportações).
 *
 * A captura é OPCIONAL e controlada pelo usuário ([captureEnabled]). Enquanto desligada,
 * os eventos NÃO são gravados — apenas um contador é mantido. O buffer é um anel de
 * [MAX_ENTRIES] linhas com timestamp em milissegundos, thread-safe (o servidor atende
 * cada conexão em uma thread própria).
 */
object LogBus {
    const val SERVER = "SERVIDOR"
    const val WEBVIEW = "WEBVIEW"
    const val APP = "APP"

    const val MAX_ENTRIES = 6000

    /** Chave da captura — o usuário liga/desliga na tela de logs. */
    val captureEnabled = MutableStateFlow(false)

    /** Hora em que a captura foi ligada (para o cabeçalho do arquivo exportado). */
    @Volatile
    var captureStartedAt: Long = 0L
        private set

    /** Eventos descartados por estarem desligados ou por estouro do anel. */
    @Volatile
    var droppedCount: Long = 0L
        private set

    data class Entry(val timeMillis: Long, val category: String, val message: String)

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Limita a frequência de republicação para a UI (o jogo dispara milhares de requests). */
    private const val PUBLISH_INTERVAL_MS = 250L
    private var lastPublishNanos = 0L

    /** Estado observável (lista imutável) para a UI — republicado no máx. a cada 250 ms. */
    val entries = MutableStateFlow<List<Entry>>(emptyList())

    /**
     * Registra um evento. Sempre conta; grava no anel somente se a captura estiver ligada
     * (comportamento pedido: "opção que o usuário poderá ativar").
     */
    fun log(category: String, message: String) {
        val trimmed = if (message.length > 4000) message.take(4000) + " …(truncado)" else message
        val entry = Entry(System.currentTimeMillis(), category, trimmed)
        if (!captureEnabled.value) {
            synchronized(lock) { droppedCount++ }
            return
        }
        val snapshot: List<Entry>
        var publish = false
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > MAX_ENTRIES) {
                ring.removeFirst()
                droppedCount++
            }
            val now = System.nanoTime()
            if (now - lastPublishNanos >= PUBLISH_INTERVAL_MS * 1_000_000) {
                lastPublishNanos = now
                publish = true
                snapshot = ring.toList()
            } else {
                snapshot = emptyList()
            }
        }
        if (publish) entries.value = snapshot
    }

    /** Cópia completa e atual do anel — usada pela EXPORTAÇÃO (nunca pela UI). */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun setCapture(enabled: Boolean) {
        if (enabled) captureStartedAt = System.currentTimeMillis()
        captureEnabled.value = enabled
        log(APP, if (enabled) "captura de logs LIGADA" else "captura de logs desligada")
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        entries.value = emptyList()
    }

    fun format(entry: Entry): String =
        "${timeFormat.format(Date(entry.timeMillis))} [${entry.category}] ${entry.message}"

    /** Nome de arquivo para a exportação atual. */
    fun exportFileName(): String = "gtavserver-logs-${fileFormat.format(Date())}.txt"

    /** Contagens por categoria para o cabeçalho do export. */
    fun countsByCategory(): Map<String, Int> =
        snapshot().groupingBy { it.category }.eachCount()
}
