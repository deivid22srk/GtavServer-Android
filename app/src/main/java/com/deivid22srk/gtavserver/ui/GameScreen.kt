package com.deivid22srk.gtavserver.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.deivid22srk.gtavserver.server.LogBus

/**
 * Jogo em WebView HORIZONTAL EM TELA CHEIA (paisagem imersiva — as barras do sistema
 * ficam escondidas e a atividade trava em landscape).
 *
 * Tudo que a página imprime (console.log/warn/error — inclusive o log do wasm) é
 * espelhado no LogBus como WEBVIEW, junto com erros HTTP/rede, recursos carregados,
 * progresso do Chromium e morte do processo de renderização. Com a captura ligada e a
 * exportação, dá para ver exatamente o que aconteceu em torno do travamento
 * ("Loading audio metadata" / mutex).
 */
@Composable
fun GameScreen(
    gameUrl: String,
    onExit: () -> Unit,
    onEnableCapture: () -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    var mainFrameError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showCaptureHint by remember { mutableStateOf(!LogBus.captureEnabled.value) }

    DisposableEffect(gameUrl) {
        LogBus.log(LogBus.APP, "tela do jogo aberta ($gameUrl) — landscape imersivo")
        if (!LogBus.captureEnabled.value) {
            LogBus.log(LogBus.APP, "dica: ligue a captura de logs para diagnosticar travamentos")
        }
        onDispose { LogBus.log(LogBus.APP, "tela do jogo fechada") }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
    ) {
        if (rendererGone) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RendererGoneCard(
                    onReload = {
                        rendererGone = false
                        mainFrameError = null
                        progress = 0
                        reloadKey++
                    },
                    onExit = onExit,
                )
            }
        } else {
            key(reloadKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        createGameWebView(
                            ctx,
                            onProgress = { p ->
                                progress = p
                            },
                            onRendererGone = {
                                rendererGone = true
                            },
                            onMainFrameError = { desc ->
                                mainFrameError = desc
                            },
                        ).also { created ->
                            webView = created
                            created.loadUrl(gameUrl)
                        }
                    },
                    onRelease = { wv ->
                        if (webView === wv) webView = null
                        wv.stopLoading()
                        wv.destroy()
                    },
                )
            }
        }

        // Progresso do Chromium durante a primeira carga (o loader do jogo é o do jogo).
        if (!rendererGone && progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
            )
        }

        // Lembrete discreto para ligar a captura (o objetivo é diagnosticar o travamento).
        if (showCaptureHint && !rendererGone) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Captura de logs desligada",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(12.dp))
                    FilledTonalButton(onClick = {
                        onEnableCapture()
                        showCaptureHint = false
                    }) {
                        Text("Ligar", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(onClick = { showCaptureHint = false }) {
                        Text("Ignorar", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Erro na carga principal (DNS/rede/conexão recusada etc.).
        mainFrameError?.let { desc ->
            if (!rendererGone) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Falha ao carregar o jogo",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.size(12.dp))
                        Row {
                            Button(onClick = {
                                mainFrameError = null
                                progress = 0
                                reloadKey++
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Tentar de novo")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RendererGoneCard(onReload: () -> Unit, onExit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.padding(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "O processo de renderização do WebView morreu",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Isso normalmente é falta de memória no wasm (OOM) ou crash do Chromium. " +
                    "O evento foi gravado no log com o horário exato — exporte os logs para " +
                    "análise. Tentar de novo costuma resolver; fechar outros apps ajuda.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(16.dp))
            Row {
                Button(onClick = onReload) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Recarregar")
                }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onExit) { Text("Sair") }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createGameWebView(
    context: Context,
    onProgress: (Int) -> Unit,
    onRendererGone: () -> Unit,
    onMainFrameError: (String) -> Unit,
): WebView {
    var lastProgressMilestone = -1
    return WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    setBackgroundColor(Color.BLACK)
    keepScreenOn = true // tela acesa durante o jogo (o servidor tem o wake lock próprio)

    with(settings) {
        javaScriptEnabled = true
        domStorageEnabled = true // localStorage/IndexedDB — o jogo usa os dois
        databaseEnabled = true
        mediaPlaybackRequiresUserGesture = false // autoplay de áudio/vídeo permitido
        cacheMode = WebSettings.LOAD_DEFAULT
        useWideViewPort = true
        loadWithOverviewMode = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        textZoom = 100 // ignora a fonte do sistema (evita layout quebrado no jogo)
    }
    if (Build.VERSION.SDK_INT >= 25) {
        // Evita o renderer ser despriorizado/limpo enquanto a partida roda.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    }

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Loga marcos de 20% (0/20/40/60/80/100) sem poluir o buffer.
            val milestone = newProgress / 20
            if (newProgress == 100 || milestone != lastProgressMilestone) {
                lastProgressMilestone = milestone
                LogBus.log(LogBus.WEBVIEW, "progresso do chromium: $newProgress%")
            }
            onProgress(newProgress)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> "erro"
                ConsoleMessage.MessageLevel.WARNING -> "aviso"
                ConsoleMessage.MessageLevel.TIP -> "dica"
                ConsoleMessage.MessageLevel.DEBUG -> "debug"
                else -> "log"
            }
            val source = consoleMessage.sourceId()?.substringAfterLast('/') ?: ""
            val line = consoleMessage.lineNumber()
            val where = if (source.isEmpty()) "" else " ($source:$line)"
            LogBus.log(LogBus.WEBVIEW, "[console.$level] ${consoleMessage.message()}$where")
            return true
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            val host = url.host ?: return false
            if (host == "127.0.0.1" || host == "localhost") return false
            // Links externos abrem no navegador; o jogo fica no mirror local.
            LogBus.log(LogBus.APP, "link externo aberto no navegador: $url")
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            LogBus.log(LogBus.WEBVIEW, "página iniciada: $url")
        }

        override fun onPageFinished(view: WebView, url: String) {
            LogBus.log(LogBus.WEBVIEW, "página concluída: $url")
        }

        override fun onLoadResource(view: WebView, url: String) {
            LogBus.log(LogBus.WEBVIEW, "recurso: ${url.takeLast(160)}")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            val desc = error.description?.toString() ?: "erro desconhecido"
            LogBus.log(
                LogBus.WEBVIEW,
                "ERRO de rede ${request.url} → $desc (código ${error.errorCode})"
            )
            if (request.isForMainFrame) onMainFrameError(desc)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            LogBus.log(
                LogBus.WEBVIEW,
                "ERRO http ${errorResponse.statusCode} ${request.method} ${request.url}"
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            LogBus.log(
                LogBus.WEBVIEW,
                "!!! processo de renderização do WebView MORREU (didCrash=${detail.didCrash()}) " +
                    "— típico de OOM no wasm; horário registrado para cruzar com o log do servidor"
            )
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            onRendererGone()
            return true
        }
    }

    // Inicializa o motor já na cor preta (evita flash branco antes do primeiro frame).
    (context as? Activity)?.window?.let { w ->
        w.navigationBarColor = Color.BLACK
        @Suppress("DEPRECATION")
        w.statusBarColor = Color.BLACK
    }
    setLayerType(View.LAYER_TYPE_HARDWARE, null)
}
}
