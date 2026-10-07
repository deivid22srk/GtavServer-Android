package com.deivid22srk.gtavserver.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deivid22srk.gtavserver.server.LogBus
import com.deivid22srk.gtavserver.server.LogExporter

/**
 * Visualizador da captura de logs (SERVIDOR + WEBVIEW + APP).
 *
 * A captura é opcional: o switch grava o estado nos prefs. "Exportar" gera um .txt
 * com cabeçalho de ambiente (app, Android, WebView, contagens) e abre o compartilhador
 * — é esse arquivo que permite diagnosticar o travamento do jogo.
 */
@Composable
fun LogsScreen(
    captureEnabled: Boolean,
    onToggleCapture: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val entries by LogBus.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf<String?>(null) } // null = tudo
    var follow by remember { mutableStateOf(true) }
    var lastExport by remember { mutableStateOf<String?>(null) }

    val visible = if (filter == null) entries else entries.filter { it.category == filter }

    // Auto-scroll para o fim enquanto "acompanhar" estiver ativo.
    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) listState.scrollToItem(visible.size - 1)
    }
    // Para de seguir quando o usuário rola manualmente para cima.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && listState.canScrollForward) follow = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // ----- Cabeçalho -----
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Column(Modifier.weight(1f)) {
                Text("Captura de logs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${visible.size} linhas · anel de ${LogBus.MAX_ENTRIES} · " +
                        "${LogBus.droppedCount} descartadas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { LogBus.clear() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Limpar")
            }
        }

        // ----- Switch de captura -----
        Card(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Gravar logs", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Ligue ANTES de reproduzir o problema. Guarda requisições do servidor, " +
                            "console do jogo, erros HTTP e crash do renderer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = captureEnabled, onCheckedChange = onToggleCapture)
            }
        }

        // ----- Ações -----
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = {
                lastExport = LogExporter.exportAndShare(context)
            }) {
                Icon(Icons.Filled.IosShare, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Exportar")
            }
            Spacer(Modifier.width(12.dp))
            AssistChip(
                onClick = { follow = true },
                label = { Text(if (follow) "acompanhando ▼" else "acompanhar") },
            )
            lastExport?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    "gerado: $it",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ----- Filtros -----
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            FilterChip(
                selected = filter == null,
                onClick = { filter = null },
                label = { Text("Tudo") },
            )
            FilterChip(
                selected = filter == LogBus.SERVER,
                onClick = { filter = LogBus.SERVER },
                label = { Text("Servidor") },
            )
            FilterChip(
                selected = filter == LogBus.WEBVIEW,
                onClick = { filter = LogBus.WEBVIEW },
                label = { Text("WebView") },
            )
            FilterChip(
                selected = filter == LogBus.APP,
                onClick = { filter = LogBus.APP },
                label = { Text("App") },
            )
        }

        // ----- Lista -----
        if (visible.isEmpty()) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Text(
                    if (captureEnabled) "Nada capturado ainda." +
                        " Ligue o servidor e abra o jogo." +
                        " As requisições HTTP, o console do wasm e os erros aparecem aqui."
                    else "Captura desligada. Ligue o gravador acima para começar a registrar.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                items(visible) { entry ->
                    val color = when (entry.category) {
                        LogBus.WEBVIEW -> MaterialTheme.colorScheme.tertiary
                        LogBus.APP -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Text(
                        LogBus.format(entry),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}
