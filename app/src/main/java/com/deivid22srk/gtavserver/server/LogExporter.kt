package com.deivid22srk.gtavserver.server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.webkit.WebView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exporta a captura de logs ([LogBus]) como arquivo .txt e abre o seletor de
 * compartilhamento do Android (Drive, WhatsApp, e-mail, salvar em arquivos etc.).
 *
 * O cabeçalho inclui as informações de ambiente que importam para diagnosticar o
 * travamento do jogo — principalmente a versão do WebView/Chromium (SharedArrayBuffer
 * e crossOriginIsolated dependem dela) e o estado de isolamento do servidor.
 */
object LogExporter {

    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun deviceInfo(context: Context): String = buildString {
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val appVersion = if (pkg != null) {
            val vName = pkg.versionName ?: "?"
            val vCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
            "$vName (versionCode $vCode)"
        } else "?"
        append("app              : GtavServer-Android $appVersion\n")
        append("android          : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("dispositivo      : ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("webview          : ${webViewVersion()}\n")
        append("servidor url     : ${ServerBus.url.value ?: "(desligado)"}\n")
        val startAt = LogBus.captureStartedAt
        append("captura ligada em: ")
        append(if (startAt > 0L) fileNameFormat.format(Date(startAt)) else "(não registrada)")
        append("\n")
        val counts = LogBus.countsByCategory()
        val total = counts.values.sum()
        append(
            "linhas capturadas: $total " +
                "(SERVIDOR=${counts[LogBus.SERVER] ?: 0}, " +
                "WEBVIEW=${counts[LogBus.WEBVIEW] ?: 0}, " +
                "APP=${counts[LogBus.APP] ?: 0})\n"
        )
        append("descartadas      : ${LogBus.droppedCount} " +
            "(captura desligada e/ou anel de ${LogBus.MAX_ENTRIES} cheio)\n")
    }

    private fun webViewVersion(): String = try {
        val current = if (Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        if (current != null) "${current.packageName} ${current.versionName}"
        else "versão indisponível (API < 26)"
    } catch (_: Exception) {
        "não foi possível obter"
    }

    /** Escreve o arquivo de exportação em cacheDir/exports e devolve o File. */
    fun writeExportFile(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // limpa exportações antigas (são temporárias por natureza)
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, LogBus.exportFileName())
        val header = buildString {
            appendLine("# GtavServer-Android — captura de logs (servidor + webview)")
            appendLine("# gerado em       : ${fileNameFormat.format(Date())}")
            appendLine("# " + deviceInfo(context).replace("\n", "\n# "))
            appendLine("# " + "-".repeat(72))
        }
        val body = LogBus.snapshot().joinToString(separator = "\n", postfix = "\n") { LogBus.format(it) }
        file.writeText(header + body, Charsets.UTF_8)
        return file
    }

    /** Abre o compartilhador do sistema com o arquivo gerado. */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "GtavServer-Android — logs")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Exportar logs"))
    }

    /** Exporta e compartilha de uma vez; devolve o nome do arquivo gerado. */
    fun exportAndShare(context: Context): String {
        val file = writeExportFile(context)
        share(context, file)
        return file.name
    }
}
